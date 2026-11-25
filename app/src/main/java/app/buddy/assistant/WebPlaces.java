package app.buddy.assistant;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The rest of the page for other devices (WebDevices checks who asks first): Buddy's Linux files (the same places and
 * the same checks as the Files screen, FilePlaces), files sent with a message, the chats' schedules and the routines,
 * and pictures of the phone's screen and of the chats' hidden screens.
 * <p>
 * The pictures are for looking only: the page can't touch either screen. While a money app is in front of one (Guard),
 * that screen isn't shown, as cards about money stay on the phone.
 */
final class WebPlaces {
    private WebPlaces() {
    }

    // ------------------------------------------------------------------ Linux files

    /** The folders the page may list for a chat (null: none): the workspace, and the chat's two. */
    static JSONObject places(Context c, Sessions.Session s) throws Exception {
        JSONArray a = new JSONArray().put(new JSONObject().put("name", "Workspace").put("path", FilePlaces.WORK)
                .put("about", "Where the agents work, one folder per project"));
        if (s != null) {
            if (!s.folder.isEmpty()) a.put(0, new JSONObject().put("name", s.folder).put("path", FilePlaces.WORK + "/" + s.folder)
                    .put("about", "This chat’s project folder"));
            a.put(new JSONObject().put("name", "Sent to Linux tools").put("path", FilePlaces.linuxPath(c, FilePlaces.incoming(c, s.id)))
                    .put("about", "Files sent to this chat’s Linux tools, of any size"));
            a.put(new JSONObject().put("name", "Attachments").put("path", FilePlaces.linuxPath(c, FilePlaces.attachments(c, s.id)))
                    .put("about", "Copies of what was attached in this chat"));
        }
        return new JSONObject().put("places", a);
    }

    /** What a folder holds: folders first, then by name; links as links, never followed. */
    static JSONObject list(Context c, String chat, String path, boolean hidden) throws Exception {
        File at = FilePlaces.fromLinux(c, path);
        if (at.isFile() && at.getParentFile() != null) at = at.getParentFile(); // (a link to a file: its folder)
        File dir = FilePlaces.folder(c, chat, at);
        String[] names = dir.list();
        List<FilePlaces.Entry> es = new ArrayList<>();
        int hiddenCount = 0;
        if (names != null) for (String n : names) {
            FilePlaces.Entry e = FilePlaces.stat(new File(dir, n));
            if (e == null) continue;
            if (e.hidden() && !hidden) {
                hiddenCount++;
                continue;
            }
            es.add(e);
        }
        es.sort((x, y) -> x.dir != y.dir ? (x.dir ? -1 : 1) : x.name.compareToIgnoreCase(y.name));
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(es.size(), 3000); i++) {
            FilePlaces.Entry e = es.get(i);
            a.put(new JSONObject().put("name", e.name).put("dir", e.dir).put("link", e.link).put("size", e.size)
                    .put("time", e.time).put("to", e.linkTo == null ? "" : e.linkTo));
        }
        return new JSONObject().put("path", FilePlaces.linuxPath(c, dir)).put("entries", a).put("more", Math.max(0, es.size() - 3000))
                .put("hidden", hiddenCount).put("busy", LinuxFiles.under(dir));
    }

    /** New folder, rename or delete, as on the Files screen. Null when done, else why not (the system's own words). */
    static String change(Context c, String chat, JSONObject in) {
        String op = in.optString("op");
        try {
            if ("mkdir".equals(op)) {
                String n = in.optString("name").trim();
                if (!FilePlaces.validName(n)) return "A name can’t be empty, “.” or “..”, or have a slash in it.";
                File dir = FilePlaces.folder(c, chat, FilePlaces.fromLinux(c, in.optString("path")));
                dir.mkdirs();
                Os.mkdir(new File(dir, n).getPath(), 0700);
                return null;
            }
            File real = FilePlaces.entry(c, chat, FilePlaces.fromLinux(c, in.optString("path")));
            if (LinuxFiles.under(real)) return "A file is being copied into it: wait for that first.";
            if ("rename".equals(op)) {
                String n = in.optString("name").trim();
                if (!FilePlaces.validName(n)) return "A name can’t be empty, “.” or “..”, or have a slash in it.";
                File to = new File(real.getParentFile(), n);
                if (FilePlaces.stat(to) != null) return Os.strerror(OsConstants.EEXIST);
                FilePlaces.Entry e = FilePlaces.stat(real);
                Os.rename(real.getPath(), to.getPath());
                String was = e != null && e.dir ? FilePlaces.projectOf(c, real) : null, now = was != null ? FilePlaces.projectOf(c, to) : null;
                if (was != null && now != null) { // chats working in it follow the new name
                    WebDevices.onMain(() -> {
                        ChatHub.get(c).folderRenamed(was, now);
                        return true;
                    });
                }
                return null;
            }
            if ("delete".equals(op)) {
                FilePlaces.delete(real, new FilePlaces.Tally());
                return null;
            }
            return "unknown";
        } catch (Exception e) {
            return FilePlaces.why(e);
        }
    }

    /**
     * A file the browser sends, streamed to its place (nothing held in memory): into a folder of Linux (to "files"), or
     * as a chat's attachment (to "attach": up to 25 MB, as the paperclip; bigger ones go to the chat's Linux tools when
     * it has them). A name that's taken gets " (2)". The bytes go to a hidden part file first, renamed when all came.
     */
    static JSONObject upload(Context c, Map<String, String> q, Sessions.Session s, InputStream in, long length) throws Exception {
        if (length < 0) return err("The browser didn’t say how big the file is.");
        String name = LinuxFiles.clean(q.get("name"));
        boolean attach = "attach".equals(q.get("to"));
        File dir;
        if (attach && length <= Attachments.MAX_BYTES) { // (a folder of its own: two files of one name arrive together)
            byte[] r0 = new byte[8];
            new SecureRandom().nextBytes(r0);
            dir = new File(new File(c.getCacheDir(), "web-in"), hex(r0));
            dir.mkdirs();
        } else if (attach) {
            if (s == null || !s.dev || Lite.on(s) || !Linux.installed(c))
                return err("Over 25 MB, a file goes to a chat’s Linux tools, and this chat doesn’t have them on.");
            dir = FilePlaces.incoming(c, s.id);
            dir.mkdirs();
            dir = FilePlaces.folder(c, s.id, dir);
        } else {
            dir = FilePlaces.folder(c, s == null ? null : s.id, FilePlaces.fromLinux(c, q.get("dir")));
        }
        long free = dir.getUsableSpace();
        if (free - length < 200L * 1024 * 1024) return err("Not enough room on the phone: it has " + android.text.format.Formatter
                .formatShortFileSize(c, free) + " free.");
        byte[] r = new byte[6];
        new SecureRandom().nextBytes(r);
        File part = new File(dir, "." + name + "." + hex(r) + ".buddy-part");
        long got = 0;
        android.os.PowerManager.WakeLock lock = c.getSystemService(android.os.PowerManager.class)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "buddy:web-upload");
        lock.acquire(6 * 60 * 60 * 1000L);
        try (OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[256 * 1024];
            while (got < length) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, length - got));
                if (n < 0) break;
                out.write(buf, 0, n);
                got += n;
            }
        } catch (IOException e) {
            part.delete();
            if (attach && length <= Attachments.MAX_BYTES) dir.delete();
            throw e;
        } finally {
            lock.release();
        }
        if (got < length) { // the browser stopped sending (closed, cancelled)
            part.delete();
            if (attach && length <= Attachments.MAX_BYTES) dir.delete();
            return err("The upload stopped before the end.");
        }
        if (attach && length <= Attachments.MAX_BYTES) {
            File named = new File(dir, name);
            named.delete();
            if (!part.renameTo(named)) {
                part.delete();
                return err("Couldn’t keep the file.");
            }
            try {
                String id = Attachments.importFile(c, named);
                return new JSONObject().put("id", id).put("name", Attachments.name(id)).put("image", Attachments.isImage(id));
            } finally {
                named.delete();
                dir.delete();
            }
        }
        File to = LinuxFiles.unique(dir, name);
        if (!part.renameTo(to)) {
            part.delete();
            return err("Couldn’t keep the file.");
        }
        return new JSONObject().put("path", FilePlaces.linuxPath(c, to)).put("name", to.getName()).put("linux", attach);
    }

    private static JSONObject err(String why) throws Exception {
        return new JSONObject().put("error", why);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    // ------------------------------------------------------------------ downloads: a ticket, then a plain link

    /** A file the page may fetch once by a plain link (a link carries no key): for a minute. */
    private static final class Ticket {
        final File file;
        final String chat;
        final boolean attachment;
        final long until;

        Ticket(File file, String chat, boolean attachment) {
            this.file = file;
            this.chat = chat;
            this.attachment = attachment;
            until = System.currentTimeMillis() + 60_000;
        }
    }

    private static final Map<String, Ticket> tickets = new HashMap<>();

    /** A ticket for a file of Linux (path) or an attachment of the chat (att); null when it's not one the page may have. */
    static String ticket(Context c, Sessions.Session s, JSONObject in) throws Exception {
        Ticket t;
        if (in.has("att")) {
            String id = in.optString("att");
            if (s == null || !Attachments.validId(id) || !hasAttachment(s, id)) return null;
            t = new Ticket(Attachments.file(c, id), s.id, true);
        } else {
            String chat = s == null ? null : s.id;
            File f = FilePlaces.entry(c, chat, FilePlaces.fromLinux(c, in.optString("path")));
            FilePlaces.Entry e = FilePlaces.stat(f);
            if (e == null || !e.regular) return null;
            t = new Ticket(f, chat, false);
        }
        byte[] r = new byte[16];
        new SecureRandom().nextBytes(r);
        String k = hex(r);
        synchronized (tickets) {
            long now = System.currentTimeMillis();
            for (Iterator<Ticket> i = tickets.values().iterator(); i.hasNext(); ) if (i.next().until < now) i.remove();
            tickets.put(k, t);
        }
        return k;
    }

    static boolean hasAttachment(Sessions.Session s, String id) {
        for (Sessions.Msg m : new ArrayList<>(s.messages)) if (m.files.contains(id)) return true;
        return false;
    }

    /** The file of a ticket (used up), opened the Files screen's careful way; null when the ticket isn't good. */
    static Object[] redeem(Context c, String k) throws IOException {
        Ticket t;
        synchronized (tickets) {
            t = k == null ? null : tickets.remove(k);
        }
        if (t == null || t.until < System.currentTimeMillis()) return null;
        ParcelFileDescriptor pfd = t.attachment ? ParcelFileDescriptor.open(t.file, ParcelFileDescriptor.MODE_READ_ONLY)
                : FilePlaces.open(c, t.chat, t.file);
        String name = t.attachment ? Attachments.name(t.file.getName()) : t.file.getName();
        return new Object[]{new ParcelFileDescriptor.AutoCloseInputStream(pfd), pfd.getStatSize(), name};
    }

    /** A picture attachment of a chat, to show in it (no other kind: the page only shows pictures). */
    static byte[] picture(Context c, Sessions.Session s, String id) throws IOException {
        if (s == null || !Attachments.validId(id) || !Attachments.isImage(id) || !hasAttachment(s, id)) return null;
        File f = Attachments.file(c, id);
        if (!f.isFile() || f.length() > Attachments.MAX_BYTES) return null;
        return java.nio.file.Files.readAllBytes(f.toPath());
    }

    // ------------------------------------------------------------------ schedules and routines

    static JSONObject plans(Context c) throws Exception {
        JSONObject out = WebDevices.onMain(() -> {
            JSONArray sch = new JSONArray();
            long now = System.currentTimeMillis();
            for (Sessions.Session s : new ArrayList<>(ChatHub.get(c).sessions.all)) {
                Schedules.Plan p = s.schedule;
                if (p == null || s.incognito || !s.parent.isEmpty()) continue;
                String when = p.timed ? "Next: " + Schedules.when(c, p.next(now)) : "";
                if (!p.events.isEmpty()) when += (when.isEmpty() ? "" : " · ") + "On: " + Events.summary(c, p);
                sch.put(new JSONObject().put("chat", s.id).put("title", s.label()).put("text", p.text).put("when", when)
                        .put("paused", p.paused).put("running", s.running).put("timed", p.timed)
                        .put("minutes", new JSONArray(p.minutes)).put("days", p.days).put("quiet", p.quiet)
                        .put("events", !p.events.isEmpty()).put("wake", p.wake));
            }
            return new JSONObject().put("schedules", sch);
        });
        if (out == null) out = new JSONObject().put("schedules", new JSONArray());
        JSONArray rs = new JSONArray();
        for (JSONObject r : Routines.all(c)) {
            JSONArray steps = r.optJSONArray("steps");
            List<String> blanks = steps == null ? new ArrayList<>() : Routines.blanks(steps);
            rs.put(new JSONObject().put("name", r.optString("name")).put("about", r.optString("about"))
                    .put("steps", steps == null ? 0 : steps.length()).put("blanks", new JSONArray(blanks)));
        }
        return out.put("routines", rs);
    }

    /**
     * A chat's schedule set from the page: its message, times and days, and "only tell me when there's news". What
     * else it has (waking the phone, events) stays as the app set it: waking can take away a lock screen, and is set
     * on the phone. Null when done, else why not; ok[0] says when it runs next.
     */
    static String setPlan(Context c, JSONObject in, String[] ok) throws Exception {
        String text = in.optString("text").trim();
        if (text.isEmpty()) return "Write the message it sends.";
        if (text.length() > 8000) return "The message is too long.";
        JSONArray ms = in.optJSONArray("minutes");
        java.util.TreeSet<Integer> times = new java.util.TreeSet<>();
        if (ms != null) for (int i = 0; i < ms.length() && times.size() < 24; i++) {
            int m = ms.optInt(i, -1);
            if (m >= 0 && m < 24 * 60) times.add(m);
        }
        int days = in.optInt("days", Schedules.Plan.EVERY_DAY) & Schedules.Plan.EVERY_DAY;
        if (times.isEmpty()) return "Add a time.";
        if (days == 0) return "Pick at least one day.";
        String r = WebDevices.onMain(() -> {
            ChatHub hub = ChatHub.get(c);
            Sessions.Session s = hub.sessions.find(in.optString("chat"));
            if (s == null || s.incognito || !s.parent.isEmpty()) return "no such chat";
            Schedules.Plan p = s.schedule != null ? s.schedule.copy() : new Schedules.Plan();
            p.text = text;
            p.minutes = new int[times.size()];
            int k = 0;
            for (int m : times) p.minutes[k++] = m;
            p.days = days;
            p.timed = true;
            p.quiet = in.optBoolean("quiet", p.quiet);
            hub.setSchedule(s, p);
            ok[0] = "Next: " + Schedules.when(c, p.next(System.currentTimeMillis()));
            return "";
        });
        return r == null ? "Buddy didn't answer" : r.isEmpty() ? null : r;
    }

    /** Run now, pause, resume or delete a chat's schedule. Null when done, else why not. */
    static String plan(Context c, JSONObject in) throws Exception {
        String r = WebDevices.onMain(() -> {
            ChatHub hub = ChatHub.get(c);
            Sessions.Session s = hub.sessions.find(in.optString("chat"));
            if (s == null || s.incognito || !s.parent.isEmpty() || s.schedule == null) return "that schedule isn’t there any more";
            switch (in.optString("op")) {
                case "run":
                    if (s.running) return "the chat is working: it runs when it’s done";
                    Schedules.runNow(c, s);
                    return "";
                case "pause": Schedules.setPaused(c, s, true); return "";
                case "resume": Schedules.setPaused(c, s, false); return "";
                case "delete": hub.setSchedule(s, null); return "";
                default: return "unknown";
            }
        });
        return r == null ? "Buddy didn't answer" : r.isEmpty() ? null : r;
    }

    /** A routine runs on the phone as from the Routines screen (its cards and checks as always); how it went is a notification there. */
    static String runRoutine(Context c, JSONObject in) {
        String name = in.optString("name");
        if (Routines.find(c, name) == null) return "that routine isn’t there any more";
        BuddyService svc = BuddyService.get();
        if (svc == null || svc.tools() == null) return "Buddy’s accessibility isn’t on on the phone";
        if (!Routines.RUNNING.compareAndSet(null, name)) return "“" + Routines.RUNNING.get() + "” is running: one routine at a time";
        Context app = c.getApplicationContext();
        JSONObject values = in.optJSONObject("values");
        new Thread(() -> {
            String result;
            try {
                result = svc.tools().runFromPage(name, values, Background.setUp(app) && Prefs.routinesHidden(app));
            } finally {
                Routines.RUNNING.set(null);
            }
            RoutinesActivity.done(app, name, result);
            Routines.Listeners.changed();
        }, "routine-web").start();
        return null;
    }

    // ------------------------------------------------------------------ screens: to look at, never to touch

    /** The screens there are to look at: the phone's own, and each chat's hidden one. */
    static JSONObject screens(Context c) throws Exception {
        JSONObject out = WebDevices.onMain(() -> {
            JSONArray a = new JSONArray();
            BuddyService svc = BuddyService.get();
            for (Sessions.Session s : new ArrayList<>(ChatHub.get(c).sessions.all)) {
                if (s.incognito || Background.displayOf(s.id) == Display.DEFAULT_DISPLAY) continue;
                String pkg = svc == null ? null : Background.appOn(svc, s.id);
                a.put(new JSONObject().put("chat", s.id).put("title", s.parent.isEmpty() ? s.label() : "Sub-agent · " + s.label())
                        .put("app", appName(c, pkg)).put("running", s.running));
            }
            return new JSONObject().put("phone", svc != null).put("screens", a);
        });
        return out == null ? new JSONObject().put("phone", false).put("screens", new JSONArray()) : out;
    }

    private static String appName(Context c, String pkg) {
        if (pkg == null) return "";
        try {
            return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Why a screen isn't shown now (a money app is in front of it), or null. */
    static String hiddenWhy(Context c, String which) {
        BuddyService svc = BuddyService.get();
        if (svc == null) return "Buddy’s accessibility isn’t on on the phone.";
        String pkg = "phone".equals(which) ? svc.foregroundPkg() : Background.appOn(svc, which);
        return pkg != null && Guard.moneyApp(c, pkg) ? "A money app is in front: that screen is only shown on the phone." : null;
    }

    private static final Object shotLock = new Object();
    private static long shotAt;
    private static byte[] lastShot;
    private static int lastWidth;

    /**
     * A picture of the phone's own screen, as JPEG at most width px wide. Android lets an app take about one a second:
     * a newer one than 1.2 s is shared by everyone who asks (several open pages), and an agent's screenshot meanwhile
     * waits and tries again on its own (Tools.screenshot).
     */
    static byte[] phone(int width) throws Exception {
        BuddyService svc = BuddyService.get();
        if (svc == null) return null;
        synchronized (shotLock) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastShot != null && now - shotAt < 1200 && lastWidth == width) return lastShot;
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Bitmap> shot = new AtomicReference<>();
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult r) {
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                        if (hw != null) shot.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                        r.getHardwareBuffer().close();
                    } catch (Exception ignored) {
                    }
                    done.countDown();
                }

                @Override
                public void onFailure(int code) {
                    done.countDown();
                }
            });
            done.await(5, TimeUnit.SECONDS);
            Bitmap full = shot.get();
            // (too soon after another one: the one before, if it's from just now; never an old one as if it were now)
            if (full == null) return lastShot != null && android.os.SystemClock.elapsedRealtime() - shotAt < 4000 ? lastShot : null;
            float scale = Math.min(1f, (float) width / full.getWidth());
            Bitmap small = scale < 1f ? Bitmap.createScaledBitmap(full, Math.round(full.getWidth() * scale),
                    Math.round(full.getHeight() * scale), true) : full;
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 72, jpg);
            lastShot = jpg.toByteArray();
            lastWidth = width;
            shotAt = android.os.SystemClock.elapsedRealtime();
            return lastShot;
        }
    }

    /** Query string parameters, decoded. */
    static Map<String, String> query(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String p : q.split("&")) {
            int e = p.indexOf('=');
            if (e <= 0) continue;
            try {
                m.put(p.substring(0, e), java.net.URLDecoder.decode(p.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    static List<String> list(JSONArray a) {
        List<String> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        return l;
    }

    @SuppressWarnings("unused")
    private static final List<String> NONE = Arrays.asList();
}
