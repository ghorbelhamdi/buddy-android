package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A shell in Buddy's Linux on a real pseudo-terminal. It lives on when the Terminal screen closes,
 * so you can come back to it ({@link #current}).
 */
final class TermSession implements TermEmulator.Host {
    interface Listener {
        void onScreenChanged();

        void onTitleChanged(String title);

        void onExited(int code);

        void onBell();
    }

    /** Open sessions, oldest first (the Terminal screen shows them as tabs), and the one in front. */
    static final java.util.List<TermSession> all = new java.util.ArrayList<>();
    static TermSession current;
    private static int counter;
    /** Tab number: the tab is "shell 1", "shell 2"… until the program sets a title. */
    final int number;

    final TermEmulator emulator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private Listener listener;
    private int fd = -1, pid;
    private FileOutputStream out;
    /** Must stay referenced: when it's garbage-collected it closes the pty and the shell gets a hang-up. */
    private ParcelFileDescriptor pty;
    volatile boolean exited;
    volatile int exitCode;
    private boolean redrawPosted;

    private TermSession(int rows, int cols) {
        emulator = new TermEmulator(rows, cols, this);
        number = ++counter;
    }

    String label() {
        String t = emulator.title;
        return t == null || t.trim().isEmpty() ? BuddyApp.str(R.string.terminal_shell_name, number) : t.trim();
    }

    /** Starts a login shell in Buddy's Linux, as a new tab. Linux must be installed. */
    static TermSession start(Context c, int rows, int cols) throws IOException {
        return start(c, rows, cols, null);
    }

    /** Same, running {@code first} in the login shell before handing it over (e.g. "opencode auth login"). */
    static TermSession start(Context c, int rows, int cols, String first) throws IOException {
        String shell = "cd /root; if command -v bash >/dev/null; then exec bash -l; else exec sh -l; fi";
        if (first != null) {
            // bash -l -c runs the profile (PATH), then the command, then stays as an interactive shell
            shell = "cd /root; exec bash -l -c '" + (first + "; exec bash -l").replace("'", "'\\''") + "'";
        }
        TermSession s = launch(c, rows, cols, shell);
        all.add(s);
        current = s;
        return s;
    }

    /** A session that isn't a tab (e.g. the hidden Claude Code that does voice dictation). */
    static TermSession startHidden(Context c, int rows, int cols, String shell) throws IOException {
        return launch(c, rows, cols, shell);
    }

    private static TermSession launch(Context c, int rows, int cols, String shell) throws IOException {
        TermSession s = new TermSession(rows, cols);
        Helper.sync(c); // the shell profile (/etc/profile.d/buddy.sh) comes with the helper files
        String lib = c.getApplicationInfo().nativeLibraryDir;
        List<String> cmd = Linux.command(c, "/bin/sh", "-c", shell);
        String[] args = cmd.toArray(new String[0]);
        java.io.File tmp = new java.io.File(c.getCacheDir(), "proot");
        tmp.mkdirs();
        String[] env = {
                "PROOT_LOADER=" + lib + "/libproot-loader.so",
                "PROOT_TMP_DIR=" + tmp.getAbsolutePath(),
                "LD_LIBRARY_PATH=" + lib,
                "PATH=/system/bin",
        };
        int[] r = Pty.start(args[0], args, env, c.getFilesDir().getAbsolutePath(), rows, cols);
        s.pid = r[1];
        ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(r[0]); // owns the pty from here on
        s.pty = pfd;
        s.fd = pfd.getFd();
        s.out = new FileOutputStream(pfd.getFileDescriptor());
        final FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[16384];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    try {
                        s.emulator.append(buf, n);
                        s.changed();
                    } catch (RuntimeException e) {
                        // a bug on odd output must not take down the whole app (bubble, phone tools, agents)
                        android.util.Log.e("Buddy", "terminal output", e);
                    }
                }
            } catch (IOException ignored) {
                // the pty closes (EIO) when the shell and everything it started have exited
            }
            s.ended(s.exitCode);
        }, "term-reader");
        reader.start();
        // Only for the exit code: Android's own process reaper may collect the child first (waitpid then
        // fails), so the session ends when the pty closes (reader above), not here.
        Thread waiter = new Thread(() -> {
            int code = Pty.waitFor(s.pid);
            if (code >= 0) s.exitCode = code;
        }, "term-waiter");
        waiter.start();
        return s;
    }

    private void ended(int code) {
        if (exited) return;
        exited = true;
        main.post(() -> {
            if (listener != null) listener.onExited(code);
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Coalesce redraws: at most one pending per frame. */
    private void changed() {
        synchronized (this) {
            if (redrawPosted) return;
            redrawPosted = true;
        }
        main.post(() -> {
            synchronized (this) {
                redrawPosted = false;
            }
            if (listener != null) listener.onScreenChanged();
        });
    }

    void resize(int rows, int cols) {
        emulator.resize(rows, cols);
        if (fd >= 0 && !exited) Pty.resize(fd, rows, cols);
        changed();
    }

    // ---------------------------------------------------------------- input

    @Override
    public void write(byte[] b) {
        if (exited || out == null) return;
        writer.execute(() -> {
            try {
                out.write(b);
            } catch (IOException ignored) {
            }
        });
    }

    void write(String s) {
        write(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Pasted text: bracketed if the program asked for it, newlines as Enter. */
    void paste(String text) {
        String t = text.replace("\r\n", "\r").replace('\n', '\r');
        if (emulator.bracketedPaste) t = "\033[200~" + t.replace("\033[201~", "") + "\033[201~";
        write(t);
    }

    @Override
    public void onTitle(String title) {
        main.post(() -> {
            if (listener != null) listener.onTitleChanged(title);
        });
    }

    @Override
    public void onBell() {
        main.post(() -> {
            if (listener != null) listener.onBell();
        });
    }

    /** Ends the shell (and everything it started). */
    void finish() {
        if (!exited) Pty.hangup(pid);
        all.remove(this);
        if (current == this) current = all.isEmpty() ? null : all.get(all.size() - 1);
    }
}
