package app.buddy.assistant;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The folders of Buddy's Linux the Files screen shows and changes, and the check everything it does goes through: the
 * agents' workspace (/root/.buddy/work) and, for a chat, the two folders of its own session (incoming and files).
 * <p>
 * Agents write in these folders. A link one of them makes there means something else on Android's side than inside
 * Linux: /data/… is then the phone's own, Buddy's settings with their keys included. So a link is shown as a link and
 * never followed, and a path counts only by where it really is (its canonical path), inside one of these folders.
 */
final class FilePlaces {
    /** The workspace, as the agent sees it. */
    static final String WORK = "/root/.buddy/work";

    private FilePlaces() {
    }

    /** A chat's id as Buddy makes them: nothing that could step out of the sessions folder. */
    static boolean validChat(String chat) {
        return chat != null && chat.matches("[A-Za-z0-9_-]{1,64}");
    }

    static File linuxRoot(Context c) throws IOException {
        return Linux.root(c).getCanonicalFile();
    }

    /** The agents' workspace, one folder per project. */
    static File work(Context c) throws IOException {
        return new File(linuxRoot(c), WORK.substring(1));
    }

    /**
     * A chat's project folder, as the chat keeps it: a path inside the workspace ("flappy", "games/maze"), each part a
     * plain name. Where it really is gets checked when it's used (folder), as for any path.
     */
    static boolean validProject(String rel) {
        if (rel == null || rel.isEmpty() || rel.length() > 1000 || rel.indexOf('\n') >= 0 || rel.indexOf('\r') >= 0) return false;
        for (String part : rel.split("/", -1)) if (!validName(part)) return false;
        return true;
    }

    /** The folder for a chat's project (not checked: pass it to folder). */
    static File project(Context c, String rel) throws IOException {
        return new File(work(c), rel);
    }

    /** A folder inside the workspace as a chat keeps it ("flappy"), or null: the workspace itself, or not in it. */
    static String projectOf(Context c, File dir) {
        try {
            File w = work(c);
            if (!inside(dir, w)) return null;
            String rel = dir.getPath().substring(w.getPath().length() + 1);
            return validProject(rel) ? rel : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The chat's own folder. Only two folders in it are ever shown: the rest is the agents' own records. */
    static File chat(Context c, String chat) throws IOException {
        if (!validChat(chat)) throw new IOException(c.getString(R.string.files_outside));
        return new File(linuxRoot(c), "root/.buddy/sessions/" + chat);
    }

    /** What the user sent to the chat's Linux tools. */
    static File incoming(Context c, String chat) throws IOException {
        return new File(chat(c, chat), "incoming");
    }

    /** The chat's attachments, as the helper copied them for the agent. */
    static File attachments(Context c, String chat) throws IOException {
        return new File(chat(c, chat), "files");
    }

    /** The folders everything must be inside: the workspace and, with a chat, its two. */
    static List<File> roots(Context c, String chat) throws IOException {
        List<File> l = new ArrayList<>();
        l.add(work(c));
        if (chat != null) {
            l.add(incoming(c, chat));
            l.add(attachments(c, chat));
        }
        return l;
    }

    /**
     * The real place of a folder to list or to write into: one of the roots or a folder inside one, wherever links on
     * the way lead. Anything else is refused. A root that was itself swapped for a link counts for nothing.
     */
    static File folder(Context c, String chat, File dir) throws IOException {
        File real = dir.getCanonicalFile();
        for (File root : roots(c, chat)) {
            if (!root.getCanonicalPath().equals(root.getPath())) continue;
            if (real.equals(root) || inside(real, root)) return real;
        }
        throw new IOException(c.getString(R.string.files_outside));
    }

    /**
     * The real place of a file or folder to act on: its folder is checked as above, the entry itself is taken by name.
     * It may be a link, and is then renamed or deleted as the link it is, never through it. A root itself isn't an
     * entry: the places can't be renamed or deleted.
     */
    static File entry(Context c, String chat, File f) throws IOException {
        File parent = f.getParentFile();
        if (parent == null || !validName(f.getName())) throw new IOException(c.getString(R.string.files_outside));
        return new File(folder(c, chat, parent), f.getName());
    }

    static boolean inside(File f, File root) {
        return f.getPath().startsWith(root.getPath() + "/");
    }

    /** A name for one file or folder: not empty, no slash, not "." or "..", and short enough for the file system. */
    static boolean validName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0
                && name.indexOf('\0') < 0 && name.getBytes(StandardCharsets.UTF_8).length <= 255;
    }

    /** The path as the agent sees it inside Linux. */
    static String linuxPath(Context c, File f) {
        String root, p = f.getPath();
        try {
            root = linuxRoot(c).getPath();
        } catch (IOException e) {
            root = Linux.root(c).getPath();
        }
        return p.startsWith(root + "/") ? p.substring(root.length()) : p;
    }

    /** The file on Android's side for a path inside Linux (not checked: pass it to folder or entry). */
    static File fromLinux(Context c, String path) throws IOException {
        return new File(linuxRoot(c), path.startsWith("/") ? path.substring(1) : path);
    }

    // ------------------------------------------------------------------ what is there

    /** One file, folder or link, as lstat sees it: a link is never looked through. */
    static final class Entry {
        final File file;
        final String name;
        final boolean dir, link, regular;
        final long size, time;
        /** Where a link points, as written. */
        final String linkTo;

        Entry(File file, StructStat st) {
            this.file = file;
            name = file.getName();
            dir = OsConstants.S_ISDIR(st.st_mode);
            link = OsConstants.S_ISLNK(st.st_mode);
            regular = OsConstants.S_ISREG(st.st_mode);
            size = regular ? st.st_size : 0;
            time = st.st_mtime * 1000;
            String to = null;
            if (link) {
                try {
                    to = Os.readlink(file.getPath());
                } catch (Exception ignored) {
                }
            }
            linkTo = to;
        }

        boolean hidden() {
            return name.startsWith(".");
        }
    }

    /** The entry at f, or null when nothing is there. */
    static Entry stat(File f) {
        try {
            return new Entry(f, Os.lstat(f.getPath()));
        } catch (Exception e) {
            return null;
        }
    }

    /** How much a folder (or a file) holds: files and folders in it, and their bytes. Links count as one item, not followed. */
    static final class Tally {
        volatile long items, bytes; // (one thread counts; the screen reads them while it does)
        /** Set from another thread to stop the walk. */
        volatile boolean stop;
    }

    /** Adds f and everything in it to t (off the main thread: a project can hold many thousands of files). */
    static void tally(File f, Tally t) {
        if (t.stop) return;
        Entry e = stat(f);
        if (e == null) return;
        t.items++;
        t.bytes += e.size;
        if (!e.dir) return;
        String[] kids = f.list();
        if (kids != null) for (String k : kids) tally(new File(f, k), t);
    }

    // ------------------------------------------------------------------ changes

    /** The system's own words for what went wrong ("Permission denied"). */
    static String why(Exception e) {
        if (e instanceof ErrnoException) return Os.strerror(((ErrnoException) e).errno);
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /**
     * Deletes f and what it holds, one entry at a time; count.items goes up as they go, and the first thing that
     * couldn't be deleted is thrown at the end (the rest still goes). A link is removed, never followed: a folder is
     * only opened while it is really where its path says, so one swapped for a link meanwhile isn't emptied.
     */
    static void delete(File f, Tally count) throws IOException {
        IOException[] first = new IOException[1];
        delete(f, count, first);
        if (first[0] != null) throw first[0];
    }

    private static void delete(File f, Tally count, IOException[] first) {
        if (count.stop) return;
        Entry e = stat(f);
        if (e == null) return;
        try {
            if (e.dir) {
                if (!f.getCanonicalPath().equals(f.getPath())) throw new IOException(f.getName());
                String[] kids = f.list();
                if (kids != null) for (String k : kids) delete(new File(f, k), count, first);
                if (count.stop) return;
            }
            Os.remove(f.getPath());
            count.items++;
        } catch (Exception x) {
            if (first[0] == null) first[0] = new IOException(f.getName() + ": " + why(x));
        }
    }

    /**
     * Opens a file for another app to read (or for Buddy to copy out): a regular file, not through a link, and looked
     * at again once it is open, by where the open file really is. A folder above it could be swapped for a link
     * between the check and the open; the open file can't lie about its place. Opened without waiting: a named pipe
     * put in the file's place would otherwise hold the open (and the thread) until something wrote to it.
     */
    static ParcelFileDescriptor open(Context c, String chat, File f) throws IOException {
        File real = entry(c, chat, f);
        ParcelFileDescriptor pfd;
        try {
            FileDescriptor fd = Os.open(real.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK
                    | OsConstants.O_CLOEXEC, 0);
            try {
                pfd = ParcelFileDescriptor.dup(fd);
            } finally {
                Os.close(fd);
            }
        } catch (ErrnoException e) {
            throw new IOException(f.getName() + ": " + why(e));
        }
        try {
            if (!OsConstants.S_ISREG(Os.fstat(pfd.getFileDescriptor()).st_mode)) throw new IOException(c.getString(R.string.files_outside));
            File opened = new File(Os.readlink("/proc/self/fd/" + pfd.getFd()));
            boolean ok = false;
            for (File root : roots(c, chat)) ok |= inside(opened, root);
            if (!ok) throw new IOException(c.getString(R.string.files_outside));
            Os.fcntlInt(pfd.getFileDescriptor(), OsConstants.F_SETFL, 0); // (a plain reading file again for whoever gets it)
            return pfd;
        } catch (Exception e) {
            pfd.close();
            throw e instanceof IOException ? (IOException) e : new IOException(why(e));
        }
    }
}
