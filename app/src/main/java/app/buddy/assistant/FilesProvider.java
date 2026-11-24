package app.buddy.assistant;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Shares single files with other apps by URI permission: the camera writes the new photo to
 * content://…/capture, attachments open in a viewer via content://…/a/&lt;id&gt;, and a file the user opens or shares
 * from the Files screen goes out as content://…/f/&lt;id&gt;/&lt;name&gt;, read in place whatever its size.
 */
public class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "app.buddy.assistant.files";

    static Uri captureUri() {
        return Uri.parse("content://" + AUTHORITY + "/capture");
    }

    static Uri attachmentUri(String id) {
        return Uri.parse("content://" + AUTHORITY + "/a/" + id);
    }

    static Uri apkUri() {
        return Uri.parse("content://" + AUTHORITY + "/apk/app.apk");
    }

    /** An app built in Buddy's Linux, copied here for Android's installer (install_apk). */
    static File apkFile(android.content.Context c) {
        return new File(c.getCacheDir(), "install.apk");
    }

    /** A file from Buddy's Linux handed to the share sheet (share): a copy, by its name. */
    static File shareFile(android.content.Context c, String name) {
        File dir = new File(c.getCacheDir(), "share");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete(); // one share at a time
        dir.mkdirs();
        return new File(dir, name);
    }

    static Uri shareUri(String name) {
        return Uri.parse("content://" + AUTHORITY + "/share/" + Uri.encode(name));
    }

    /** A file of Buddy's Linux handed out from the Files screen, with the chat whose folders it may be in (or null). */
    private static final class Served {
        final File file;
        final String chat;

        Served(File file, String chat) {
            this.file = file;
            this.chat = chat;
        }
    }

    /**
     * The files handed out, by an id made for each: another app never names a path, only an id Buddy gave it. Kept in
     * memory (the newest few hundred): after Buddy restarts, the old addresses open nothing.
     */
    private static final java.util.Map<String, Served> served = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Served>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Served> eldest) {
                    return size() > 512;
                }
            });

    /**
     * An address for a file the user picked on the Files screen (FilesActivity), to open or share it. The file is
     * checked again each time the address is used (FilePlaces.open): it must still be a regular file inside the
     * workspace or that chat's own folders.
     */
    static Uri linuxUri(File f, String chat) {
        byte[] r = new byte[16];
        new java.security.SecureRandom().nextBytes(r);
        StringBuilder id = new StringBuilder();
        for (byte b : r) id.append(String.format("%02x", b));
        served.put(id.toString(), new Served(f, chat));
        // (the name is for the other app's eyes: Buddy goes by the id alone)
        return Uri.parse("content://" + AUTHORITY + "/f/" + id + "/" + Uri.encode(f.getName()));
    }

    private static boolean linux(Uri uri) {
        java.util.List<String> p = uri.getPathSegments();
        return p.size() == 3 && "f".equals(p.get(0));
    }

    private static Served served(Uri uri) {
        return linux(uri) ? served.get(uri.getPathSegments().get(1)) : null;
    }

    static File captureFile(android.content.Context c) {
        return new File(c.getCacheDir(), "capture.jpg");
    }

    private File fileFor(Uri uri) {
        java.util.List<String> p = uri.getPathSegments();
        if (p.size() == 1 && "capture".equals(p.get(0))) return captureFile(getContext());
        if (p.size() == 2 && "a".equals(p.get(0))) return Attachments.file(getContext(), p.get(1));
        if (p.size() == 2 && "apk".equals(p.get(0))) return apkFile(getContext());
        if (p.size() == 2 && "share".equals(p.get(0)) && !p.get(1).contains("/") && !p.get(1).startsWith(".")) {
            return new File(new File(getContext().getCacheDir(), "share"), p.get(1));
        }
        return null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (linux(uri)) {
            Served s = served(uri);
            if (s == null) throw new FileNotFoundException(uri.toString());
            if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
            try {
                return FilePlaces.open(getContext(), s.chat, s.file);
            } catch (java.io.IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }
        File f = fileFor(uri);
        if (f == null) throw new FileNotFoundException(uri.toString());
        boolean capture = f.equals(captureFile(getContext()));
        if (!capture && !"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(capture ? mode : "r"));
    }

    @Override
    public String getType(Uri uri) {
        if (linux(uri)) {
            Served s = served(uri);
            return s == null ? null : type(s.file.getName());
        }
        File f = fileFor(uri);
        if (f == null) return null;
        return type(f.getName(), false);
    }

    /** The type of a file from the Files screen, by its name: the phone's own list of extensions knows most. */
    static String type(String name) {
        return type(name, true);
    }

    private static String type(String name, boolean any) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".csv")) return "text/plain";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        int dot = n.lastIndexOf('.');
        String known = any && dot >= 0 ? android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(dot + 1)) : null;
        return known != null ? known : "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        if (linux(uri)) { // its name and size, while it is still a file where Buddy may hand it out
            Served s = served(uri);
            if (s == null) return null;
            FilePlaces.Entry e;
            try {
                e = FilePlaces.stat(FilePlaces.entry(getContext(), s.chat, s.file));
            } catch (java.io.IOException x) {
                return null;
            }
            if (e == null || !e.regular) return null;
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{e.name, e.size});
            return c;
        }
        File f = fileFor(uri);
        if (f == null) return null;
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        String name = f.equals(apkFile(getContext())) ? "app.apk"
                : f.getParentFile() != null && "share".equals(f.getParentFile().getName()) ? f.getName()
                : f.getParentFile() != null && "attachments".equals(f.getParentFile().getName())
                ? Attachments.name(f.getName()) : "photo.jpg";
        c.addRow(new Object[]{name, f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
