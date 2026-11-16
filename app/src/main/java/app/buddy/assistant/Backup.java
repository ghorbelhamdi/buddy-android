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
    /**
     * Settings that never leave this phone in a backup and are never taken from one: the tokens that open Buddy's
     * phone tools and its background helper (anyone holding the file would hold them), and what belongs to this
     * phone's own set-up (its pairing, its API key services).
     */
    private static final java.util.Set<String> OWN = new java.util.HashSet<>(java.util.Arrays.asList(
            "token", "bg_token", "bg_paired", "bg_adb_port", "providers"));
    /**
     * Settings a backup can't loosen: on restore this phone's own values stay. A backup is a file from outside, and
     * one made to harm (or just made on a phone set up differently) shouldn't switch on phone commands, Linux tools
     * or Trust for everything, add an MCP server or a "never ask" button.
     */
    private static final java.util.Set<String> SAFETY = new java.util.HashSet<>(java.util.Arrays.asList(
            "bg_shell", "bg_shell_ask", "agents_tap_buddy", "dev", "autonomous_default", "guard_builtin", "guard_allow",
            "ssh_server", "remote_session", "codex_remote", "mcp_servers", "ask_new_apps"));

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
                // nor the tokens and this phone's pairing (see OWN)
                if (p.equals("buddy")) xml = xml.replaceAll("\\s*<string name=\"(token|bg_token|providers|agent_default_[a-z]+)\">[^<]*</string>", "")
                        .replaceAll("\\s*<(boolean|int) name=\"(bg_paired|bg_adb_port)\"[^>]*/>", "");
                z.putNextEntry(new ZipEntry("prefs/" + p + ".xml"));
                z.write(xml.getBytes("UTF-8"));
            }
            addTree(z, new File(c.getFilesDir(), "chats"), "files/chats/");
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
            // only the parts a backup holds, and nothing that reaches outside them: the archive is a file from
            // outside, and unpacked as it is at / it could write over Buddy's own scripts. So: every name must sit
            // under one of the parts, it is unpacked into a folder of its own first, links that end outside the
            // parts are dropped there, and only then it goes into place
            String allowed = "^(\\./)?(" + LINUX_PARTS.replace(".", "\\.").replace(" ", "|") + ")(/|$)";
            String out = Linux.exec(c, "cd / && d=/tmp/buddy-restore.d && rm -rf $d && mkdir -p $d && f=/tmp/buddy-restore.tar.gz && "
                    + "if tar tzf $f | grep -Ev '" + allowed + "' | grep -q . || tar tzf $f | grep -Eq '(^|/)\\.\\.(/|$)'; "
                    + "then echo UNSAFE; else tar xzf $f -C $d && "
                    // a link stays when it ends inside one of the parts (a project's "current -> ../releases/v2")
                    + "D=$(readlink -f $d); find $d -type l | while IFS= read -r l; do t=$(readlink \"$l\"); "
                    + "case \"$t\" in /*) rm -f \"$l\"; continue;; esac; "
                    + "r=$(cd \"$(dirname \"$l\")\" && readlink -f \"$t\" 2>/dev/null); "
                    + "k=; for q in " + LINUX_PARTS + "; do case \"$r\" in \"$D/$q\"|\"$D/$q\"/*) k=1;; esac; done; "
                    + "[ -n \"$k\" ] || rm -f \"$l\"; done; "
                    // the parts only, so the folders above them (/, /root) keep their own modes
                    + "(cd $d && p=$(ls -d " + LINUX_PARTS + " 2>/dev/null); [ -z \"$p\" ] || tar cf - $p) | tar xf - -C / && echo DONE; fi; "
                    + "rm -rf $d $f", 1800);
            if (out.contains("UNSAFE")) throw new IllegalArgumentException(c.getString(R.string.backup_not_one));
            linuxDone = out.contains("DONE");
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
        // the chats are the backup's: its chat files (2.2.26 on), or none, and then the chats in its settings (before);
        // the chats in memory save nothing more until the restart, so they can't write over them
        ChatHub.get(c).sessions.freeze();
        File chats = new File(c.getFilesDir(), "chats");
        Linux.deleteTree(chats);
        chats.mkdirs();
        File savedChats = new File(staging, "files/chats");
        File[] cs = savedChats.listFiles();
        if (cs != null) for (File f : cs) if (f.isFile() && f.getName().endsWith(".json")) {
            File to = new File(chats, f.getName());
            try { // every restored chat asks as App permissions say: "Routine allowed" is chosen on this phone, by hand
                org.json.JSONObject o = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                o.remove("approvals");
                // nor does a chat bring Linux tools along onto a phone that starts chats without them: a chat with a
                // scheduled task would run with a shell there unseen. The user switches them on again in the chat
                if (!Prefs.devMode(c)) o.put("dev", false);
                try (OutputStream w = new FileOutputStream(to)) {
                    w.write(o.toString().getBytes("UTF-8"));
                }
            } catch (org.json.JSONException notAChat) {
                copyFile(f, to);
            }
        }
        for (String p : PREFS) {
            File f = new File(staging, "prefs/" + p + ".xml");
            if (!f.isFile()) continue;
            String xml = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            if (p.equals("buddy")) { // Buddy's settings: the backup's, except what stays this phone's (see merge)
                android.content.SharedPreferences sp = c.getSharedPreferences(p, Context.MODE_PRIVATE);
                java.util.Map<String, Object> merged = merge(c, readPrefs(xml), sp.getAll());
                android.content.SharedPreferences.Editor e = sp.edit().clear();
                for (java.util.Map.Entry<String, Object> v : merged.entrySet()) put(e, v.getKey(), v.getValue());
                e.commit();
                continue;
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

    /**
     * A backup's settings as they go onto this phone. This phone's own stay for its tokens, pairing and API key
     * services (OWN), for every switch that decides what an agent may do (SAFETY) and for where downloads and requests go
     * (see stays). An app gets the stricter of the two modes, so Trust only if this phone trusts it already, and
     * notification rules that differ from this phone's arrive paused: both act without asking, so they start with
     * the user's own OK here.
     */
    private static java.util.Map<String, Object> merge(Context c, java.util.Map<String, Object> backup, java.util.Map<String, ?> mine) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Object> e : backup.entrySet()) {
            String k = e.getKey();
            if (stays(k)) continue;
            if (k.startsWith("app_safety_")) { // the stricter of the two; Trust only where this phone trusts already
                // (an app with no setting here has the mode Buddy gives it by default: Hard for a bank or a
                // messenger, which a backup's Smart must not undo)
                Object here = mine.get(k);
                if (here == null) here = Guard.mode(c, k.substring("app_safety_".length()), null);
                if (strictness(here) >= strictness(e.getValue())) continue;
            }
            out.put(k, e.getValue());
        }
        for (java.util.Map.Entry<String, ?> e : mine.entrySet()) {
            String k = e.getKey();
            // this phone's own choice for an app stays where the backup has none, or a looser one
            if (stays(k) || k.startsWith("app_safety_") && !out.containsKey(k)) out.put(k, e.getValue());
        }
        // "always ask" buttons: this phone's and the backup's, both
        Object ask = backup.get("guard_ask"), myAsk = mine.get("guard_ask");
        if (ask instanceof String && myAsk instanceof String) {
            try {
                org.json.JSONArray both = new org.json.JSONArray((String) ask), more = new org.json.JSONArray((String) myAsk);
                java.util.Set<String> have = new java.util.HashSet<>();
                for (int i = 0; i < both.length(); i++) have.add(String.valueOf(both.get(i)));
                for (int i = 0; i < more.length(); i++) if (have.add(String.valueOf(more.get(i)))) both.put(more.get(i));
                out.put("guard_ask", both.toString());
            } catch (org.json.JSONException odd) {
                out.put("guard_ask", myAsk);
            }
        } else if (myAsk != null && ask == null) out.put("guard_ask", myAsk);
        Object rules = backup.get("rules");
        if (rules != null && !rules.equals(mine.get("rules")) && !"[]".equals(rules)) out.put("rules_on", false);
        return out;
    }

    /** A setting that is never taken from a backup: this phone's own value stays (or none). */
    private static boolean stays(String k) {
        return OWN.contains(k) || SAFETY.contains(k) || k.startsWith("agent_default_")
                // where Buddy's Linux downloads from, where an agent's requests go, and who reviews its steps
                || k.startsWith("mirror") || k.startsWith("api_base_") || k.startsWith("reviewer");
    }

    /** An app's mode by how much it asks: none or Trust 0, Smart 1, Hard 2. */
    private static int strictness(Object mode) {
        return "hard".equals(mode) ? 2 : "smart".equals(mode) ? 1 : 0;
    }

    /** A shared-preferences file's values by name (strings, booleans, numbers, sets of strings). */
    private static java.util.Map<String, Object> readPrefs(String xml) throws Exception {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
        p.setInput(new java.io.StringReader(xml));
        String name = null;
        java.util.Set<String> set = null;
        for (int t = p.getEventType(); t != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; t = p.next()) {
            if (t == org.xmlpull.v1.XmlPullParser.END_TAG && "set".equals(p.getName())) {
                if (name != null && set != null) out.put(name, set);
                set = null;
                continue;
            }
            if (t != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
            String tag = p.getName(), n = p.getAttributeValue(null, "name"), v = p.getAttributeValue(null, "value");
            if ("set".equals(tag)) {
                name = n;
                set = new java.util.HashSet<>();
            } else if ("string".equals(tag)) {
                String text = p.nextText();
                if (set != null) set.add(text);
                else if (n != null) out.put(n, text);
            } else if (n == null || v == null) {
                continue;
            } else if ("boolean".equals(tag)) out.put(n, Boolean.parseBoolean(v));
            else if ("int".equals(tag)) out.put(n, Integer.parseInt(v));
            else if ("long".equals(tag)) out.put(n, Long.parseLong(v));
            else if ("float".equals(tag)) out.put(n, Float.parseFloat(v));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void put(android.content.SharedPreferences.Editor e, String k, Object v) {
        if (v instanceof String) e.putString(k, (String) v);
        else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Long) e.putLong(k, (Long) v);
        else if (v instanceof Float) e.putFloat(k, (Float) v);
        else if (v instanceof java.util.Set) e.putStringSet(k, (java.util.Set<String>) v);
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
