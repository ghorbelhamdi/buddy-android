package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Claude Code session with Remote Control, running inside Buddy's Linux, so it can be continued from the
 * Claude app (Code) anywhere. Claude Code's interactive mode needs a terminal, so it runs under `script`
 * (a hidden pseudo-terminal). Buddy restarts it if it stops and resumes the same conversation.
 */
final class RemoteSession {
    enum State { OFF, STARTING, ACTIVE, ERROR }

    interface Listener {
        void onRemoteChanged();
    }

    /** Its own folder, so `--continue` resumes this conversation and never a bubble chat. */
    static final String DIR = "/root/remote";

    static volatile State state = State.OFF;
    static volatile String detail = "", url = null;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static Process proc;
    private static OutputStream keepOpen; // the terminal's input; closing it would end the session
    private static long lastStart;
    private static int quickFails;

    private RemoteSession() {
    }

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void set(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onRemoteChanged();
        });
    }

    static synchronized boolean running() {
        return proc != null;
    }

    /** Start if switched on and not running (called on service start and from Settings). */
    static void ensure(Context c) {
        final Context app = c.getApplicationContext();
        if (!Prefs.remoteSession(app) || running()) return;
        if (!SetupState.signedIn(app, "claude")) {
            set(State.ERROR, "Sign in to Claude Code first.");
            return;
        }
        set(State.STARTING, "Starting…");
        new Thread(() -> run(app)).start();
    }

    static void stop() {
        Process p;
        synchronized (RemoteSession.class) {
            p = proc;
            proc = null;
            keepOpen = null;
        }
        if (p != null) p.destroy();
        url = null;
        set(State.OFF, "");
    }

    private static void run(Context app) {
        try {
            prepare(app);
            boolean resume = hasConversation(app);
            String cmd = "stty cols 100 rows 40; exec claude --remote-control \"" + Prefs.remoteName(app) + "\""
                    + (resume ? " --continue" : "");
            Process p = Linux.builder(app, "/bin/sh", "-c",
                    "cd " + DIR + " && exec script -qfc '" + cmd.replace("'", "'\\''") + "' /root/.buddy/remote.log").start();
            synchronized (RemoteSession.class) {
                if (!Prefs.remoteSession(app)) {
                    p.destroy();
                    return;
                }
                proc = p;
                keepOpen = p.getOutputStream();
            }
            lastStart = System.currentTimeMillis();
            Pattern u = Pattern.compile("https://claude\\.ai/code/session_[A-Za-z0-9_]+");
            StringBuilder tail = new StringBuilder();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    tail.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (tail.length() > 20000) tail.delete(0, tail.length() - 10000);
                    String plain = tail.toString().replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
                    Matcher m = u.matcher(plain);
                    if (state != State.ACTIVE && (m.find() || plain.contains("/rc active"))) {
                        Matcher all = u.matcher(plain);
                        while (all.find()) url = all.group();
                        quickFails = 0;
                        set(State.ACTIVE, "");
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            set(State.ERROR, "Couldn't start: " + e.getMessage());
        }
        boolean wanted;
        synchronized (RemoteSession.class) {
            wanted = proc != null && Prefs.remoteSession(app);
            proc = null;
            keepOpen = null;
        }
        url = null;
        if (!wanted) return;
        // it stopped on its own: start again, backing off if it keeps failing right away
        if (System.currentTimeMillis() - lastStart < 30_000) quickFails++;
        if (quickFails >= 3) {
            set(State.ERROR, "The session keeps stopping. Turn it off and on again, or check the Claude Code sign-in.");
            return;
        }
        set(State.STARTING, "Restarting…");
        main.postDelayed(() -> ensure(app), 3000L * (quickFails + 1));
    }

    /** First-run answers, folder trust and the phone tools, so the hidden terminal never waits on a question. */
    private static void prepare(Context app) throws Exception {
        String token = Prefs.token(app);
        String url = "http://127.0.0.1:" + McpServer.PORT + "/mcp";
        String out = Linux.exec(app, "set -e; command -v script >/dev/null || apk add -q util-linux-misc; mkdir -p " + DIR + ";"
                + " f=/root/.claude.json; [ -s $f ] || echo '{}' > $f;"
                + " jq --arg d " + DIR + " --arg u " + url + " --arg a 'Bearer " + token + "'"
                + " '.hasCompletedOnboarding=true | .theme=(.theme // \"dark\") | .fullscreenUpsellSeenCount=99"
                + " | .hasSeenAutoDefaultNotice=true | .projects[$d].hasTrustDialogAccepted=true"
                + " | .projects[$d].hasCompletedProjectOnboarding=true"
                + " | .mcpServers.phone={type:\"http\", url:$u, headers:{Authorization:$a}}' $f > /tmp/claude.json"
                + " && cat /tmp/claude.json > $f && echo PREP_OK", 300);
        if (!out.contains("PREP_OK")) throw new Exception(out.trim());
    }

    private static boolean hasConversation(Context app) {
        File d = new File(Linux.root(app), "root/.claude/projects/-root-remote");
        String[] f = d.list((dir, name) -> name.endsWith(".jsonl"));
        return f != null && f.length > 0;
    }
}
