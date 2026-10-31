package app.buddy.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup and move (Settings): chats, settings, rules, pictures, skills, projects and what the agents keep of each
 * conversation, in one file in Downloads/Buddy, to restore on this phone or another. Sign-ins and API keys stay out:
 * they're secrets, and the keys can't leave this phone's keystore anyway.
 */
final class Backup {
    /** Buddy's settings and chats (the API key services' list and choices stay out, as their keys do). */
    private static final String[] PREFS = {"buddy", "buddy_sessions", "browser"};
    /** In Buddy's Linux, what to keep (no sign-ins): projects without their builds, skills, the agents' conversations. */
    private static final String LINUX_PARTS = "root/.buddy/sessions root/.buddy/work root/.agents/skills root/.claude/projects "
            + "root/.codex/sessions root/.local/share/opencode/opencode.db";

    private Backup() {
    }

    /** Writes the backup to Downloads/Buddy and returns its name. */
    static String create(Context c) throws Exception {
        String name = "buddy-backup-" + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(new Date()) + ".zip";
        File tmp = new File(c.getCacheDir(), "backup.zip");
        File linux = new File(c.getCacheDir(), "linux.tar.gz");
        linux.delete();
        if (Linux.installed(c)) {
            String out = Linux.exec(c, "cd / && rm -f /tmp/buddy-backup.tar.gz; p=$(ls -d " + LINUX_PARTS + " 2>/dev/null); "
                    + "[ -z \"$p\" ] || tar czf /tmp/buddy-backup.tar.gz --exclude=node_modules --exclude=build "
                    + "--exclude=.gradle --exclude=.cxx $p 2>&1 && echo DONE", 1800);
            File made = new File(Linux.root(c), "tmp/buddy-backup.tar.gz");
            if (!out.contains("DONE")) throw new Exception(out);
            if (made.isFile()) {
                if (!made.renameTo(linux)) copyFile(made, linux);
                made.delete();
            }
        }
        File prefsDir = new File(c.getApplicationInfo().dataDir, "shared_prefs");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(tmp))) {
            z.putNextEntry(new ZipEntry("buddy-backup.txt"));
            z.write(("Buddy " + BuildInfo.VERSION + " backup\n").getBytes());
            for (String p : PREFS) {
                File f = new File(prefsDir, p + ".xml");
                if (!f.isFile()) continue;
                String xml = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                // services and their choices only make sense with their keys, which stay on this phone
                if (p.equals("buddy")) xml = xml.replaceAll("\\s*<string name=\"(providers|agent_default_[a-z]+)\">[^<]*</string>", "");
                z.putNextEntry(new ZipEntry("prefs/" + p + ".xml"));
                z.write(xml.getBytes("UTF-8"));
            }
            addTree(z, new File(c.getFilesDir(), "attachments"), "files/attachments/");
            File face = new File(c.getFilesDir(), "face.png");
            if (face.isFile()) addFile(z, face, "files/face.png");
            if (linux.isFile()) addFile(z, linux, "linux.tar.gz");
        } finally {
            linux.delete();
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Buddy");
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new Exception("Couldn't write to Downloads.");
        try (OutputStream o = c.getContentResolver().openOutputStream(u); InputStream in = new FileInputStream(tmp)) {
            pump(in, o);
        } finally {
            tmp.delete();
        }
        return name;
    }

    /**
     * Puts a backup's chats, settings and files in place of this phone's, then restarts Buddy so they're read fresh.
     * Its Linux part needs Buddy's Linux set up first: false when it was skipped for that.
     */
    static boolean restore(Context c, Uri uri) throws Exception {
        File prefsDir = new File(c.getApplicationInfo().dataDir, "shared_prefs");
        File staging = new File(c.getCacheDir(), "restore");
        Linux.deleteTree(staging);
        staging.mkdirs();
        boolean ok = false, linuxDone = false;
        String root = staging.getCanonicalPath() + File.separator;
        try (InputStream in = c.getContentResolver().openInputStream(uri); ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                File out = new File(staging, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) continue;
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    pump(z, o);
                }
                if (e.getName().equals("buddy-backup.txt")) ok = true;
            }
        }
        if (!ok) throw new IllegalArgumentException(c.getString(R.string.backup_not_one));
        File linux = new File(staging, "linux.tar.gz");
        if (linux.isFile() && Linux.installed(c)) {
            File in = new File(Linux.root(c), "tmp/buddy-restore.tar.gz");
            copyFile(linux, in);
            Linux.exec(c, "cd / && tar xzf /tmp/buddy-restore.tar.gz; rm -f /tmp/buddy-restore.tar.gz", 1800);
            linuxDone = true;
        }
        File att = new File(staging, "files/attachments");
        if (att.isDirectory()) {
            File dest = new File(c.getFilesDir(), "attachments");
            dest.mkdirs();
            File[] fs = att.listFiles();
            if (fs != null) for (File f : fs) if (f.isFile()) copyFile(f, new File(dest, f.getName()));
        }
        File face = new File(staging, "files/face.png");
        if (face.isFile()) copyFile(face, new File(c.getFilesDir(), "face.png"));
        // this phone's own token, keys' services and choices stay; the rest is the backup's
        String token = Prefs.token(c), providers = Prefs.providers(c);
        java.util.Map<String, String> defaults = new java.util.HashMap<>();
        for (Account a : Account.ALL) defaults.put(a.agent, Prefs.agentDefault(c, a.agent));
        for (String p : PREFS) {
            File f = new File(staging, "prefs/" + p + ".xml");
            if (!f.isFile()) continue;
            String xml = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            if (p.equals("buddy")) {
                StringBuilder keep = new StringBuilder("<string name=\"token\">" + esc(token) + "</string>\n")
                        .append("<string name=\"providers\">").append(esc(providers)).append("</string>\n");
                for (java.util.Map.Entry<String, String> d : defaults.entrySet()) {
                    keep.append("<string name=\"agent_default_").append(d.getKey()).append("\">").append(esc(d.getValue())).append("</string>\n");
                }
                xml = xml.replaceAll("\\s*<string name=\"(token|providers|agent_default_[a-z]+)\">[^<]*</string>", "")
                        .replace("</map>", keep + "</map>");
            }
            c.getSharedPreferences(p, Context.MODE_PRIVATE); // loaded, so nothing pending writes over the new file later
            try (OutputStream o = new FileOutputStream(new File(prefsDir, p + ".xml"))) {
                o.write(xml.getBytes("UTF-8"));
            }
        }
        boolean skipped = linux.isFile() && !linuxDone;
        Linux.deleteTree(staging);
        return !skipped;
    }

    /** Starts Buddy again in a new process, so the restored settings and chats are what it reads. */
    static void restart(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(c, 0, i,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_CANCEL_CURRENT);
        android.app.AlarmManager am = (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 800, pi);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void addTree(ZipOutputStream z, File dir, String prefix) throws Exception {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) addTree(z, f, prefix + f.getName() + "/");
            else addFile(z, f, prefix + f.getName());
        }
    }

    private static void addFile(ZipOutputStream z, File f, String name) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            pump(in, z);
        }
        z.closeEntry();
    }

    /** in to out; neither is closed. */
    private static void pump(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            pump(in, out);
        }
    }
}
