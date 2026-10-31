package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A Codex session with remote control: Codex's app-server inside Buddy's Linux, so Codex here can be used from the
 * ChatGPT app (Codex) on this phone or on a computer, with the phone tools. It runs linux/codex-remote, which also hands
 * out pairing codes: pairing has to run in the server's own proot (its socket path is too long for Android, so proot
 * moves it where only that proot finds it). Codex's own background service can't run under proot (it needs the boot
 * time, which Android hides), so Buddy runs the server itself and restarts it if it stops.
 */
final class CodexRemote {
    enum State { OFF, STARTING, ACTIVE, ERROR }

    interface Listener {
        void onCodexRemoteChanged();
    }

    static volatile State state = State.OFF;
    static volatile String detail = "";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static Process proc;
    private static OutputStream commands; // the script's input: one command per line; closing it ends the session
    private static long lastStart;
    private static int quickFails;
    private static boolean starting;
    /** Who waits for the pairing code asked for last, and its time limit. */
    private static BiConsumer<JSONObject, String> pairDone;
    private static final Runnable pairTimeout = () -> paired(null, "no answer");

    private CodexRemote() {
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
            for (Listener l : new ArrayList<>(listeners)) l.onCodexRemoteChanged();
        });
    }

    /** Start if switched on and not running (called on service start and from Settings). */
    static void ensure(Context c) {
        final Context app = c.getApplicationContext();
        synchronized (CodexRemote.class) {
            if (!Prefs.codexRemote(app) || proc != null || starting) return;
            if (!SetupState.planSignedIn(app, "codex")) {
                set(State.ERROR, app.getString(R.string.setup_set_up_first, "Codex"));
                return;
            }
            starting = true;
        }
        set(State.STARTING, "");
        new Thread(() -> run(app)).start();
    }

    static void stop() {
        Process p;
        OutputStream o;
        synchronized (CodexRemote.class) {
            p = proc;
            o = commands;
            proc = null;
            commands = null;
        }
        if (o != null) {
            try {
                o.close();
            } catch (IOException ignored) {
            }
        }
        if (p != null) p.destroy();
        quickFails = 0; // turning it off and on again starts with fresh retries
        paired(null, "stopped");
        set(State.OFF, "");
    }

    private static void run(Context app) {
        String prev = "", last = ""; // its last two lines, for the error
        try {
            Helper.sync(app);
            Process p = Linux.builder(app, "/bin/sh", "/root/bin/codex-remote", Prefs.token(app)).start();
            synchronized (CodexRemote.class) {
                starting = false;
                if (!Prefs.codexRemote(app)) {
                    p.destroy();
                    return;
                }
                proc = p;
                commands = p.getOutputStream();
            }
            lastStart = System.currentTimeMillis();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "").trim();
                    // Codex warns that bubblewrap is missing on every start; its sandbox can't run under proot anyway
                    if (line.isEmpty() || line.contains("bubblewrap")) continue;
                    if (line.startsWith("BUDDY_PAIR ")) {
                        paired(line.substring(11), null);
                    } else if (line.startsWith("BUDDY_PAIR_ERROR ")) {
                        paired(null, line.substring(17));
                    } else if (line.startsWith("BUDDY_READY") || line.contains("next_status=Connected")) {
                        quickFails = 0;
                        if (state != State.ACTIVE) set(State.ACTIVE, "");
                    } else {
                        prev = last;
                        last = line;
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            if (Prefs.codexRemote(app)) set(State.ERROR, app.getString(R.string.remote_start_failed, e.getMessage()));
        }
        paired(null, (prev + " " + last).trim());
        boolean wanted;
        synchronized (CodexRemote.class) {
            starting = false;
            wanted = proc != null && Prefs.codexRemote(app);
            proc = null;
            commands = null;
        }
        if (!wanted) return;
        // it stopped on its own: start again, backing off if it keeps failing right away
        if (System.currentTimeMillis() - lastStart < 30_000) quickFails++;
        if (quickFails >= 3) {
            set(State.ERROR, app.getString(R.string.codex_keeps_stopping, (prev + " " + last).trim()));
            return;
        }
        set(State.STARTING, "");
        main.postDelayed(() -> ensure(app), 3000L * (quickFails + 1));
    }

    /**
     * Asks the running server for a pairing code, for ChatGPT on a phone or a computer. done runs on the main thread
     * with the answer (manualPairingCode, expiresAt in seconds…) or, when that failed, null and the reason.
     */
    static void pair(BiConsumer<JSONObject, String> done) {
        OutputStream o;
        synchronized (CodexRemote.class) {
            o = commands;
            if (o == null || state != State.ACTIVE) {
                main.post(() -> done.accept(null, "not running"));
                return;
            }
            pairDone = done;
        }
        main.removeCallbacks(pairTimeout);
        main.postDelayed(pairTimeout, 60_000);
        new Thread(() -> {
            try {
                synchronized (o) {
                    o.write("pair\n".getBytes(StandardCharsets.UTF_8));
                    o.flush();
                }
            } catch (IOException e) {
                paired(null, e.getMessage());
            }
        }).start();
    }

    private static void paired(String json, String error) {
        BiConsumer<JSONObject, String> d;
        synchronized (CodexRemote.class) {
            d = pairDone;
            pairDone = null;
        }
        if (d == null) return;
        main.removeCallbacks(pairTimeout);
        JSONObject code = null;
        String why = error;
        if (json != null) {
            try {
                code = new JSONObject(json);
                if (code.optString("manualPairingCode").isEmpty()) {
                    code = null;
                    why = json;
                }
            } catch (Exception e) {
                why = json;
            }
        }
        final JSONObject c = code;
        final String w = why == null || why.isEmpty() ? "?" : why;
        main.post(() -> d.accept(c, w));
    }
}
