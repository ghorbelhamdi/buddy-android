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
            {"linux/system-prompt.md", "/root/.buddy/system-prompt.md", "600"},
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
        final Context app = c.getApplicationContext();
        if (!Linux.installed(app)) {
            throw new IllegalStateException("Buddy's Linux isn't installed yet. Open Settings to set it up.");
        }
        final String[] cmd = new String[args.length + 3];
        cmd[0] = "/bin/bash";
        cmd[1] = "/root/bin/buddy-run";
        cmd[2] = Prefs.token(app);
        System.arraycopy(args, 0, cmd, 3, args.length);
        pool.execute(() -> {
            try {
                sync(app);
                Process p = Linux.builder(app, cmd).start();
                p.getOutputStream().close();
                // the helper reports through the local server; its own output is only kept for debugging
                File log = new File(app.getCacheDir(), "helper.log");
                try (InputStream in = p.getInputStream(); OutputStream out = new FileOutputStream(log, log.length() < 200_000)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                p.waitFor();
            } catch (Exception e) {
                android.util.Log.w("Buddy", "helper failed", e);
            }
        });
    }

    /** Copies the helper files from the APK into Linux once per app version. */
    static synchronized void sync(Context c) throws IOException {
        if (syncedFor == BuildInfo.VERSION_CODE && new File(Linux.root(c), "root/bin/buddy-run").exists()) return;
        File root = Linux.root(c);
        for (String[] f : FILES) {
            File out = new File(root, f[1].substring(1));
            out.getParentFile().mkdirs();
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
}
