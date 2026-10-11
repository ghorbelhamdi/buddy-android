package app.buddy.assistant;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Photos and files attached to chat messages. They live in the app's own storage; the helper in Buddy's Linux
 * fetches them over the local server (GET /file/&lt;id&gt;) right before the agent runs.
 */
final class Attachments {
    static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final int MAX_SIDE = 2048;

    private Attachments() {
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "attachments");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]{1,120}") && !id.contains("..");
    }

    /** The stored file for an id, or null if the id isn't valid. */
    static File file(Context c, String id) {
        return validId(id) ? new File(dir(c), id) : null;
    }

    /** "a1b2c3d4_receipt.pdf" → "receipt.pdf". */
    static String name(String id) {
        int u = id.indexOf('_');
        return u >= 0 ? id.substring(u + 1) : id;
    }

    static boolean isImage(String id) {
        String n = id.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif");
    }

    /** Copies what the user picked into Buddy's storage; photos are scaled to 2048px and turned upright. Returns the id. */
    static String importUri(Context c, Uri uri) throws IOException {
        String type = c.getContentResolver().getType(uri);
        String name = displayName(c, uri);
        if (name == null || name.isEmpty()) name = type != null && type.startsWith("image/") ? "photo.jpg" : "file";
        boolean photo = type != null && type.startsWith("image/") && !type.contains("gif") && !type.contains("svg");
        String base = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (photo) base = base.replaceAll("\\.[A-Za-z0-9]{1,5}$", "") + ".jpg";
        if (base.length() > 60) base = base.substring(base.length() - 60);
        byte[] r = new byte[4];
        new SecureRandom().nextBytes(r);
        String id = String.format("%02x%02x%02x%02x_", r[0], r[1], r[2], r[3]) + base;
        File out = new File(dir(c), id);
        if (photo && savePhoto(c, uri, out)) return id;
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream o = new FileOutputStream(out)) {
            if (in == null) throw new IOException("Can't open " + name);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException(name + " is larger than 25 MB.");
                o.write(buf, 0, n);
            }
        } catch (IOException e) {
            out.delete();
            throw e;
        }
        return id;
    }

    private static boolean savePhoto(Context c, Uri uri, File out) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0) return false;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            if (b == null) return false;
            int rotate = 0;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                rotate = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) {
            }
            float scale = Math.min(1f, (float) MAX_SIDE / Math.max(b.getWidth(), b.getHeight()));
            if (rotate != 0 || scale < 1f) {
                Matrix m = new Matrix();
                m.postScale(scale, scale);
                m.postRotate(rotate);
                Bitmap t = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (t != b) b.recycle();
                b = t;
            }
            try (OutputStream os = new FileOutputStream(out)) {
                b.compress(Bitmap.CompressFormat.JPEG, 88, os);
            }
            b.recycle();
            return true;
        } catch (Exception e) {
            out.delete();
            return false;
        }
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? null : p.substring(p.lastIndexOf('/') + 1);
    }

    /** A small bitmap for previews. */
    static Bitmap thumb(Context c, String id, int px) {
        File f = file(c, id);
        if (f == null || !f.exists()) return null;
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), b);
        int s = 1;
        while (Math.min(b.outWidth, b.outHeight) / (s * 2) >= px) s *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Forget attachments older than 30 days. */
    static void cleanup(Context c) {
        long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) if (f.lastModified() < cutoff) f.delete();
    }
}
