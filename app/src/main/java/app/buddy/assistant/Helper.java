package app.buddy.assistant;

import android.content.Context;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs Buddy's helper script (assets/linux/buddy-run) inside the built-in Linux. The helper streams
 * the agent's progress back to the app over the local server (POST /event), like before.
 */
final class Helper {
    private static final String[][] FILES = {
            // asset, path inside Linux, mode
            {"linux/buddy-run", "/root/bin/buddy-run", "700"},
            {"linux/codex-remote", "/root/bin/codex-remote", "700"}, // the Codex session (CodexRemote)
            {"linux/buddy-browser", "/root/bin/buddy-browser", "700"}, // the background browser (Browser)
            {"linux/profile.sh", "/etc/profile.d/buddy.sh", "644"},
            {"linux/arecord", "/usr/local/bin/arecord", "755"},
            {"linux/asound-cards", "/etc/buddy/asound-cards", "644"},
            {"linux/arecord", "/usr/local/bin/rec", "755"}, // SoX's recorder, same arguments that matter (-r, -c)
            {"linux/system-prompt.md", "/root/.buddy/system-prompt.md", "600"},
            {"linux/GUIDE.md", "/root/.buddy/GUIDE.md", "644"}, // Buddy's user guide, for questions about Buddy itself
            {"linux/browser-prompt.md", "/root/.buddy/browser-prompt.md", "600"},
            {"linux/lite-prompt.md", "/root/.buddy/lite-prompt.md", "600"}, // a lite chat's short prompt (see Lite)
            {"linux/onphone-get", "/root/bin/onphone-get", "700"}, // fetches models on this phone (see OnPhone)
            {"linux/AGENTS.md", "/root/.buddy/work/AGENTS.md", "600"},
            {"linux/android-app/SKILL.md", "/root/.buddy/work/.claude/skills/android-app/SKILL.md", "600"},
            {"linux/android-app/build-apk.sh", "/root/.buddy/work/.claude/skills/android-app/build-apk.sh", "700"},
            {"linux/android-app/setup-toolchain.sh", "/root/.buddy/work/.claude/skills/android-app/setup-toolchain.sh", "700"},
    };
    private static final ExecutorService pool = Executors.newCachedThreadPool();
    private static int syncedFor = -1;

    private Helper() {
    }

    /** Whether chats can run: Linux is installed (each agent's own readiness is checked by Account). */
    static boolean ready(Context c) {
        return Linux.installed(c);
    }

    /** Runs `buddy-run <token> args…` in the background. Throws with a readable message if Linux isn't set up. */
    static void run(Context c, String... args) {
        run(c, (java.util.function.IntConsumer) null, args);
    }

    /** buddy-run's exit status when the chat's earlier run is still going (it then leaves that run alone). */
    static final int BUSY = 75;

    /**
     * gone: called (on the helper's thread) once that helper process has ended, with its exit status, or -1 if it
     * could not be started; may be null.
     */
    static void run(Context c, final java.util.function.IntConsumer gone, String... args) {
        runFor(c, null, gone, args);
    }

    /**
     * As run, for a chat's own run: the helper (and through it the agent) gets a token of its own that stands for
     * that chat and ends with the helper, not Buddy's token (see McpServer.grant).
     */
    static void runFor(Context c, String chat, final java.util.function.IntConsumer gone, String... args) {
        final Context app = c.getApplicationContext();
        if (!Linux.installed(app)) {
            throw new IllegalStateException(app.getString(R.string.helper_linux_missing)); // shown to the user
        }
        final String[] cmd = new String[args.length + 3];
        cmd[0] = "/bin/bash";
        cmd[1] = "/root/bin/buddy-run";
        final String runToken = chat == null ? null : McpServer.grant(chat);
        cmd[2] = runToken != null ? runToken : Prefs.token(app);
        System.arraycopy(args, 0, cmd, 3, args.length);
        pool.execute(() -> {
            Process p = null;
            try {
                sync(app);
                writePermissions(app);
                p = Linux.builder(app, cmd).start();
                p.getOutputStream().close();
                // the helper reports through the local server; its own output is only kept for debugging
                File log = new File(app.getCacheDir(), "helper.log");
                byte[] buf = new byte[8192];
                try (InputStream in = p.getInputStream()) {
                    try (OutputStream out = new FileOutputStream(log, log.length() < 200_000)) {
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } catch (IOException e) { // the log can't be written (no room): the helper works on, its output is dropped
                        android.util.Log.w("Buddy", "helper log", e);
                        while (in.read(buf) > 0) continue;
                    }
                }
                p.waitFor();
            } catch (Exception e) {
                android.util.Log.w("Buddy", "helper failed", e);
            } finally {
                // only a helper that has really ended (or never started) is reported as gone
                if (p == null || !p.isAlive()) McpServer.revoke(runToken);
                if (gone != null && (p == null || !p.isAlive())) gone.accept(p == null ? -1 : p.exitValue());
            }
        });
    }

    /** The user's App permissions choices, which buddy-run appends to the agent's instructions. */
    static void writePermissions(Context c) {
        // the background browser's instructions go along only while it's on (buddy-run looks for this file)
        File on = new File(Linux.root(c), "root/.buddy/browser-on");
        try {
            if (Browser.available(c)) on.createNewFile();
            else on.delete();
        } catch (IOException ignored) {
        }
        StringBuilder trusted = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : Prefs.appSafetyAll(c).entrySet()) {
            if ("trust".equals(e.getValue())) trusted.append("- ").append(appName(c, e.getKey())).append(" (").append(e.getKey()).append(")\n");
        }
        String text = "";
        if (trusted.length() > 0) text += "Apps the user set to Trust:\n" + trusted;
        if (Prefs.autonomousDefault(c)) {
            text += (text.isEmpty() ? "" : "\n") + "The user also trusts every app they haven't set, except messaging, "
                    + "social, calls, email, shopping, payment and banking apps.\n";
        }
        StringBuilder never = new StringBuilder();
        for (Guard.Rule r : Guard.rules(c, "allow")) never.append("- “").append(r.label).append("” in ")
                .append(r.pkg == null ? "all apps" : appName(c, r.pkg)).append("\n");
        if (never.length() > 0) {
            text += (text.isEmpty() ? "" : "\n") + "Buttons the user marked \"Never ask\" (tap them without confirming "
                    + "when the task calls for it):\n" + never;
        }
        if (Prefs.agentsTapBuddy(c)) {
            text += (text.isEmpty() ? "" : "\n") + "The user switched on \"Let agents tap Buddy's screens\" in Buddy's "
                    + "Settings. So, unlike the rule above, you may open Buddy and use its screens when the user asks: "
                    + "the Chats list, a chat, and the main Settings screen. These parts stay the user's own, and a tap "
                    + "there answers BLOCKED: the bubble and its cards, approval cards, a chat's Linux tools chip and "
                    + "Chat options (with its scheduled task), and the screens for App permissions, API keys, MCP "
                    + "servers, Notification rules, Scheduled, Skills, Background mode, Backup, GitHub and sign-in. "
                    + "When the user asks for something there, say it's theirs to do; that isn't them asking you to "
                    + "break a rule.\n";
        }
        File f = new File(Linux.root(c), "root/.buddy/permissions.md");
        try {
            if (text.isEmpty()) {
                f.delete();
            } else {
                try (OutputStream o = new FileOutputStream(f)) {
                    o.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            android.util.Log.w("Buddy", "permissions not written", e);
        }
    }

    private static String appName(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Copies the helper files again now (Check Buddy's fix when Linux still has an older copy). */
    static synchronized void resync(Context c) throws IOException {
        syncedFor = -1;
        sync(c);
    }

    /** Copies the helper files from the APK into Linux once per app version. */
    static synchronized void sync(Context c) throws IOException {
        Mirrors.apply(c); // each time: the download server may have been changed, and the phone's DNS with the network
        if (syncedFor == BuildInfo.VERSION_CODE && new File(Linux.root(c), "root/bin/buddy-run").exists()) return;
        File root = Linux.root(c);
        for (String[] f : FILES) {
            File out = new File(root, f[1].substring(1));
            out.getParentFile().mkdirs();
            if (f[1].equals(AGENTS)) {
                mergeAgents(c, out, f[0], Integer.parseInt(f[2], 8));
                continue;
            }
            try (InputStream in = c.getAssets().open(f[0]); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            try {
                Os.chmod(out.getPath(), Integer.parseInt(f[2], 8));
            } catch (Exception ignored) {
            }
        }
        new File(root, "root/.buddy/sessions").mkdirs();
        syncedFor = BuildInfo.VERSION_CODE;
    }

    private static final String AGENTS = "/root/.buddy/work/AGENTS.md";
    static final String AGENTS_START = "<!-- buddy:start (Buddy keeps this part up to date; your own instructions go outside it) -->",
            AGENTS_END = "<!-- buddy:end -->";

    /**
     * The workspace's AGENTS.md is the agents' shared instructions file, and users add their own to it (#61): only
     * Buddy's part, between the markers, is rewritten. A file from before the markers gets its copy of the template
     * swapped for the marked part; one without it gets the part on top. Everything else is kept.
     */
    private static void mergeAgents(Context c, File out, String asset, int mode) throws IOException {
        String template;
        try (InputStream in = c.getAssets().open(asset)) {
            template = new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        String ours = AGENTS_START + "\n" + template + "\n" + AGENTS_END;
        String now = null;
        if (out.exists()) {
            try (InputStream in = new java.io.FileInputStream(out)) {
                now = new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        String merged = mergeAgents(now, template, ours);
        if (merged.equals(now)) return;
        // written next to it, then put in its place: the user's own text never sits in a half-written file
        File tmp = new File(out.getParentFile(), ".AGENTS.md.buddy-tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(merged.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            o.getFD().sync();
        }
        try {
            Os.chmod(tmp.getPath(), mode);
        } catch (Exception ignored) {
        }
        if (!tmp.renameTo(out)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("AGENTS.md could not be updated");
        }
    }

    /** The file's text with Buddy's part (ours) in place of the old one; now: the file's text, or null if missing. */
    static String mergeAgents(String now, String template, String ours) {
        if (now == null || now.trim().isEmpty()) return ours + "\n";
        int s = now.indexOf("<!-- buddy:start"), e = s < 0 ? -1 : now.indexOf(AGENTS_END, s);
        if (s >= 0 && e >= 0) return now.substring(0, s) + ours + now.substring(e + AGENTS_END.length());
        int t = now.indexOf(template);
        if (t >= 0) return now.substring(0, t) + ours + now.substring(t + template.length());
        // Buddy's old copy changed by hand: kept as it is (the user's lines may be in it), Buddy's part goes on top
        return ours + "\n\n" + now;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
