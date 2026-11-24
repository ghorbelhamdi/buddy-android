package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.OsConstants;
import android.text.format.Formatter;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Files for Buddy's Linux (issue #76): a file picked or shared for the shell rather than the model, of any size. It is
 * streamed straight from the other app into a folder of Buddy's Linux, in one pass: no copy in Buddy's attachments
 * first, no download by the helper. Two ways in: the Files screen uploads into the folder it shows (FilesActivity), and
 * a file too big to attach in a chat goes to that chat's own folder (sessions/&lt;chat&gt;/incoming), where the agent
 * gets its path with the message, never the file in its request. One file at a time, with a notification that shows
 * how far it is and can cancel.
 */
final class LinuxFiles {
    static final String ACTION_CANCEL = "app.buddy.assistant.CANCEL_LINUX_FILES";
    private static final String TAG = "linux_files";
    /** Room left over on the phone after a file whose size is known: Android and Buddy need some too. */
    private static final long SPARE = 64L * 1024 * 1024;
    /** How a copy still being written ends (".name.buddy-part"): a name of Buddy's own, never one of a project's files. */
    private static final String PART = ".buddy-part";

    static final class Job {
        /** The chat it was sent from: its two folders are allowed besides the workspace. Null: Files opened from Settings. */
        final String chat;
        /**
         * Picked in the chat itself (too big to attach): the chat shows it while it's copied, and it ends as a chip
         * above the message box and a line in the chat. Else it was uploaded on the Files screen, which shows it.
         */
        final boolean forChat;
        /** The folder it goes into, on Android's side. */
        final File dir;
        final String name;
        /** -1: the other app didn't say. */
        final long size;
        final InputStream in;
        volatile long done;
        volatile boolean cancelled;

        Job(String chat, boolean forChat, File dir, String name, long size, InputStream in) {
            this.chat = chat;
            this.forChat = forChat;
            this.dir = dir;
            this.name = name;
            this.size = size;
            this.in = in;
        }
    }

    /** Waiting and the one being copied (first), in order. */
    private static final List<Job> jobs = new ArrayList<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Paths of finished files, per chat, for its message box (taken when the chat is on screen). */
    private static final Map<String, List<String>> ready = new ConcurrentHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** The Files screens in front: run (main thread) whenever a copy moves on or ends. */
    private static final List<Runnable> watchers = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** What went wrong with files uploaded on the Files screen, for it to say. */
    private static final List<String> said = java.util.Collections.synchronizedList(new ArrayList<>());
    private static int results = 100;

    private LinuxFiles() {
    }

    /** The chat's folder for them, as Buddy sees it. */
    static File dir(Context c, String chat) {
        return new File(Linux.root(c), "root/.buddy/sessions/" + chat + "/incoming");
    }

    /** What the other app calls the file, and its size (-1 when it doesn't say). */
    static Object[] about(Context c, Uri u) {
        String name = null;
        long size = -1;
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                name = cur.getString(0);
                if (!cur.isNull(1)) size = cur.getLong(1);
            }
        } catch (Exception ignored) {
        }
        if (name == null) {
            String p = u.getLastPathSegment();
            name = p == null ? null : p.substring(p.lastIndexOf('/') + 1);
        }
        return new Object[]{name, size};
    }

    /**
     * Opens each file and queues it for the chat's own folder. Call off the main thread while the other app's grant
     * still holds (a share's ends with ShareActivity): an open stream outlives it. Returns what couldn't be opened, or null.
     */
    static String send(Context c, String chat, List<Uri> uris) {
        return queue(c, chat, true, dir(c, chat), uris);
    }

    /**
     * The same from the Files screen, into the folder it shows (one of FilePlaces' for that chat; it is checked again
     * when its turn comes). chat is null when Files was opened from Settings.
     */
    static String upload(Context c, String chat, File dir, List<Uri> uris) {
        return queue(c, chat, false, dir, uris);
    }

    private static String queue(Context c, String chat, boolean forChat, File dir, List<Uri> uris) {
        final Context app = c.getApplicationContext();
        File to;
        try {
            to = dir.getCanonicalFile(); // (as the Files screen names its folders: see into)
        } catch (IOException e) {
            to = dir;
        }
        String err = null;
        for (Uri u : uris) {
            Object[] a = about(app, u);
            String name = clean((String) a[0]);
            try {
                InputStream in = app.getContentResolver().openInputStream(u);
                if (in == null) throw new IOException();
                final Job j = new Job(chat, forChat, to, name, (Long) a[1], in);
                synchronized (jobs) {
                    jobs.add(j);
                }
                worker.execute(() -> copy(app, j));
            } catch (Exception e) {
                err = app.getString(R.string.attach_cant_open, name);
            }
        }
        main.post(() -> notice(app));
        return err;
    }

    /** The job the chat shows now (one picked in the chat), or null. */
    static Job current(String chat) {
        synchronized (jobs) {
            for (Job j : jobs) if (j.forChat && chat.equals(j.chat)) return j;
        }
        return null;
    }

    /** How many of the files picked in the chat are waiting or being copied. */
    static int count(String chat) {
        int n = 0;
        synchronized (jobs) {
            for (Job j : jobs) if (j.forChat && chat.equals(j.chat)) n++;
        }
        return n;
    }

    /** Stops the transfers picked in the chat (null: all of them, the Files screen's too); what was copied so far is removed. */
    static void cancel(String chat) {
        synchronized (jobs) {
            for (Job j : jobs) if (chat == null || (j.forChat && chat.equals(j.chat))) j.cancelled = true;
        }
    }

    /** The files waiting or being copied into this folder, in order (the Files screen's rows). */
    static List<Job> into(File dir) {
        List<Job> l = new ArrayList<>();
        synchronized (jobs) {
            for (Job j : jobs) if (j.dir.equals(dir)) l.add(j);
        }
        return l;
    }

    /** Whether a file is waiting or being copied into f or a folder inside it. */
    static boolean under(File f) {
        synchronized (jobs) {
            for (Job j : jobs) if (j.dir.equals(f) || FilePlaces.inside(j.dir, f)) return true;
        }
        return false;
    }

    /** Whether the file being copied right now goes into f or a folder inside it (the first job is the one at work). */
    static boolean writingUnder(File f) {
        synchronized (jobs) {
            return !jobs.isEmpty() && (jobs.get(0).dir.equals(f) || FilePlaces.inside(jobs.get(0).dir, f));
        }
    }

    /** Stops what goes into f or a folder inside it (f is being deleted). */
    static void cancelUnder(File f) {
        synchronized (jobs) {
            for (Job j : jobs) if (j.dir.equals(f) || FilePlaces.inside(j.dir, f)) j.cancelled = true;
        }
    }

    /** A Files screen in front: r runs on the main thread whenever a copy moves on or ends. */
    static void watch(Runnable r) {
        watchers.add(r);
    }

    static void unwatch(Runnable r) {
        watchers.remove(r);
    }

    /** What went wrong with uploads since the Files screen last asked. */
    static List<String> takeSaid() {
        synchronized (said) {
            List<String> l = new ArrayList<>(said);
            said.clear();
            return l;
        }
    }

    /** The paths finished since the chat last took them. */
    static List<String> takeReady(String chat) {
        List<String> l = ready.remove(chat);
        return l == null ? new ArrayList<>() : l;
    }

    /** What the chat keeps in its folder, in bytes (walks it: off the main thread). */
    static long kept(Context c, String chat) {
        FilePlaces.Tally t = new FilePlaces.Tally(); // (links an agent made there aren't followed)
        FilePlaces.tally(dir(c, chat), t);
        return t.bytes;
    }

    /**
     * The chat is being deleted and keeps its files (chat options): they move to ~/.buddy/work/incoming, where any chat
     * with Linux tools can still reach them. A rename on the same storage, so even gigabytes go at once.
     */
    static void keepAfterChat(Context c, String chat) {
        File from;
        try {
            from = FilePlaces.folder(c, chat, dir(c, chat)); // (not what a link put in its place points to)
        } catch (IOException e) {
            return;
        }
        File[] kids = from.listFiles();
        if (kids == null) return;
        File to = new File(Linux.root(c), "root/.buddy/work/incoming");
        for (File k : kids) {
            if (isPart(k)) continue;
            to.mkdirs();
            File dest;
            try {
                dest = FilePlaces.folder(c, null, to); // (a link an agent put there would lead out of the workspace)
            } catch (IOException e) {
                android.util.Log.w("Buddy", "not keeping " + k + ": " + e.getMessage());
                return;
            }
            if (!k.renameTo(unique(dest, k.getName()))) android.util.Log.w("Buddy", "couldn't keep " + k);
        }
    }

    // ------------------------------------------------------------------ the copy

    /**
     * A copy cut short when Buddy was closed left its part behind, hidden and perhaps gigabytes: Buddy notes which
     * part it is writing (one at a time), and removes the one noted before when the next copy starts or Files opens.
     */
    static void sweep(Context c) {
        synchronized (jobs) {
            if (jobs.isEmpty()) sweepNoted(c);
        }
    }

    /** Call holding jobs, with no part being written. */
    private static void sweepNoted(Context c) {
        String p = Prefs.linuxPart(c);
        if (p.isEmpty()) return;
        File f = new File(p);
        if (isPart(f)) f.delete(); // (a link put there goes itself, not what it points to)
        Prefs.setLinuxPart(c, "");
    }

    private static void copy(Context c, Job j) {
        File part = null;
        PowerManager.WakeLock wake = null;
        String line = null;
        boolean ok = false;
        try {
            if (j.cancelled) { // before it began: nothing was copied
                line = c.getString(R.string.linux_file_not_sent, j.name);
                return;
            }
            // the place itself (a chat's folder for these is made with its first file), then where it really is:
            // an agent may have changed the folders since the file was picked
            if (FilePlaces.roots(c, j.chat).contains(j.dir)) j.dir.mkdirs();
            File dir = FilePlaces.folder(c, j.chat, j.dir);
            if (!dir.isDirectory()) throw new IOException(Os.strerror(OsConstants.ENOENT));
            long free = new StatFs(dir.getPath()).getAvailableBytes();
            if (j.size >= 0 && j.size + SPARE > free) {
                line = c.getString(R.string.linux_file_no_room, j.name, Formatter.formatShortFileSize(c, j.size),
                        Formatter.formatShortFileSize(c, free));
                return;
            }
            // the screen may go off during a long copy: the CPU stays up until it ends
            wake = c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buddy:linux-file");
            wake.acquire(12L * 3600 * 1000);
            File out = unique(dir, j.name);
            part = new File(dir, "." + out.getName() + PART); // a name the agent won't take for the file
            synchronized (jobs) {
                sweepNoted(c);
                Prefs.setLinuxPart(c, part.getPath());
            }
            part.delete(); // (only a part of Buddy's own from before has this name)
            IOException readErr = null;
            Exception writeErr = null;
            long shown = 0;
            byte[] buf = new byte[1024 * 1024];
            try {
                // made new, and not through a link: nothing already there is ever written into
                FileDescriptor fd = Os.open(part.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL
                        | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
                try {
                    OutputStream o = new FileOutputStream(fd);
                    while (!j.cancelled) {
                        int n;
                        try {
                            n = j.in.read(buf);
                        } catch (IOException e) {
                            readErr = e;
                            break;
                        }
                        if (n < 0) break;
                        o.write(buf, 0, n);
                        j.done += n;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - shown > 1000) {
                            shown = now;
                            main.post(() -> notice(c));
                        }
                    }
                } finally {
                    Os.close(fd); // (the stream doesn't own it)
                }
            } catch (Exception e) { // (making, writing or closing the file in Linux)
                writeErr = e;
            }
            if (j.cancelled) {
                line = c.getString(R.string.linux_file_cancelled, j.name);
            } else if (writeErr != null) {
                // the phone's storage filled up, or another error: in its own words
                boolean full = new StatFs(dir.getPath()).getAvailableBytes() < 1024 * 1024;
                line = full ? c.getString(R.string.linux_file_full, j.name)
                        : c.getString(R.string.linux_file_write_failed, j.name, FilePlaces.why(writeErr));
            } else if (readErr != null) { // (short of the size the app gave is no error: that size is its guess)
                // a file from another app can go away while it's read (the app closed, the file was moved or deleted)
                line = c.getString(R.string.linux_file_gone, j.name);
            } else {
                if (out.exists() || Linux.isLink(out)) out = unique(dir, j.name); // (the agent made one of that name meanwhile)
                Os.rename(part.getPath(), out.getPath());
                part = null;
                final String path = FilePlaces.linuxPath(c, out);
                if (j.forChat) {
                    ready.computeIfAbsent(j.chat, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(path);
                }
                line = c.getString(R.string.linux_file_done, j.name, Formatter.formatShortFileSize(c, j.done), path);
                ok = true;
            }
        } catch (Exception e) {
            line = c.getString(R.string.linux_file_write_failed, j.name, FilePlaces.why(e));
        } finally {
            try {
                j.in.close();
            } catch (Exception ignored) {
            }
            final boolean noted = part != null || ok;
            if (part != null) part.delete();
            if (wake != null && wake.isHeld()) wake.release();
            synchronized (jobs) {
                jobs.remove(j);
                if (noted) Prefs.setLinuxPart(c, ""); // (its part is the file now, or gone)
            }
            final String text = line;
            final boolean done = ok;
            main.post(() -> {
                if (j.forChat) { // the chat says how it went
                    ChatHub hub = ChatHub.get(c);
                    Sessions.Session s = hub.sessions.find(j.chat); // (gone: the chat was deleted meanwhile)
                    if (s != null && text != null) hub.note(s, Bubble.Kind.NOTE, text);
                } else if (text != null) {
                    // the Files screen shows the file once it's there, and says what went wrong; when it isn't in
                    // front, a notification says how it ended either way
                    if (watchers.isEmpty()) result(c, j, text);
                    // (a small file is there before a progress row could show: a word that it worked)
                    else said.add(done ? c.getString(R.string.files_uploaded, j.name) : text);
                }
                notice(c);
            });
        }
    }

    /** A copy still being written. */
    static boolean isPart(File f) {
        return f.getName().startsWith(".") && f.getName().endsWith(PART);
    }

    /** The file's own name, made safe for a folder: no slashes or control characters, no leading dot, not too long. */
    static String clean(String name) {
        String n = name == null ? "" : name.replaceAll("[/\\\\\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        // ext4 takes 255 bytes; room is kept for " (2)" and what a part's name adds (a dot and ".buddy-part")
        while (n.getBytes(StandardCharsets.UTF_8).length > 200) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, n.length() - ext.length() - 1) + ext;
        }
        return n.isEmpty() ? "file" : n;
    }

    /** name in dir, or "name (2).ext", "name (3).ext"… when taken: never over a file already there. */
    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists() && !Linux.isLink(f)) return f;
        int dot = name.lastIndexOf('.');
        // (an archive's double extension stays whole: "data (2).tar.gz")
        int tar = name.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".tar.");
        if (tar > 0 && name.indexOf('.', tar + 5) < 0) dot = tar;
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists() && !Linux.isLink(f)) return f;
        }
    }

    // ------------------------------------------------------------------ the notification

    /** Shows how far the current file is, with Cancel; goes when nothing is left (main thread). */
    private static void notice(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        Job j;
        int left;
        synchronized (jobs) {
            j = jobs.isEmpty() ? null : jobs.get(0);
            left = jobs.size();
        }
        for (Runnable w : watchers) w.run();
        if (j == null) {
            nm.cancel(TAG, 1);
            return;
        }
        ControlIndicator.channel(c);
        PendingIntent cancel = PendingIntent.getBroadcast(c, 41, new Intent(c, HiddenNotice.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(c, j.forChat ? 41 : 42, where(c, j),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = progress(c, j);
        if (left > 1) text += " · " + c.getResources().getQuantityString(R.plurals.linux_file_more, left - 1, left - 1);
        Notification.Builder b = new Notification.Builder(c, ControlIndicator.CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(R.string.linux_file_sending, j.name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, c.getString(android.R.string.cancel), cancel).build());
        if (j.size > 0) b.setProgress(1000, (int) Math.min(1000, j.done * 1000 / j.size), false);
        else b.setProgress(0, 0, true);
        try {
            nm.notify(TAG, 1, b.build());
        } catch (SecurityException ignored) { // notifications off: the chat still shows it
        }
    }

    /** Where a tap on a file's notification leads: the chat it was picked in, or Files at the folder it went into. */
    private static Intent where(Context c, Job j) {
        Intent i = j.forChat ? new Intent(c, ChatActivity.class).putExtra("id", j.chat)
                : new Intent(c, FilesActivity.class).putExtra(FilesActivity.EXTRA_CHAT, j.chat)
                .putExtra(FilesActivity.EXTRA_PATH, FilePlaces.linuxPath(c, j.dir));
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    /** How an upload from the Files screen ended, when that screen isn't in front any more. */
    private static void result(Context c, Job j, String text) {
        ControlIndicator.channel(c);
        int id = results++;
        Notification.Builder b = new Notification.Builder(c, ControlIndicator.CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(R.string.files_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, id, where(c, j),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        try {
            c.getSystemService(NotificationManager.class).notify(TAG, id, b.build());
        } catch (SecurityException ignored) { // notifications off
        }
    }

    /** "1.2 GB of 3.1 GB", or just what's done when the size isn't known. */
    static String progress(Context c, Job j) {
        String done = Formatter.formatShortFileSize(c, j.done);
        return j.size > 0 ? c.getString(R.string.linux_file_progress, done, Formatter.formatShortFileSize(c, j.size)) : done;
    }
}
