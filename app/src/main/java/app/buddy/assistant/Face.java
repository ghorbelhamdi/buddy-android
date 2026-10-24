package app.buddy.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.net.Uri;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Buddy's face: the built-in drawings, or a picture picked in Settings (issue #10). The picture lives in the app's
 * own storage, so it stays through updates; when it's missing or can't be read, Buddy's own face shows.
 */
final class Face {
    /** Longest side of the saved picture, in px: sharp at the bubble's 60dp on any screen. */
    private static final int SIDE = 256;
    private static Bitmap picture, bubble; // decoded once, dropped when a new picture is saved
    private static boolean loaded;

    private Face() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "face.png");
    }

    /** The picked picture while "Your picture" is on, or null for Buddy's own face. */
    static synchronized Bitmap picture(Context c) {
        if (!"picture".equals(Prefs.face(c))) return null;
        if (!loaded) {
            loaded = true;
            picture = BitmapFactory.decodeFile(file(c).getPath()); // null when missing or broken
            bubble = null;
        }
        return picture;
    }

    /** Is a picture saved (whether or not it's in use)? */
    static boolean saved(Context c) {
        return file(c).length() > 0;
    }

    /** Shows Buddy's face in an avatar or tile: the picked picture, else the drawing {@code res}. */
    static void set(ImageView v, int res) {
        Bitmap b = picture(v.getContext());
        if (b != null) v.setImageBitmap(b);
        else v.setImageResource(res);
    }

    /** The floating bubble's picture: a soft shadow is drawn under it, as the drawn face gets one from its outline. */
    static synchronized Bitmap bubble(Context c) {
        Bitmap b = picture(c);
        if (b == null) return null;
        if (bubble == null) {
            int pad = SIDE / 16;
            bubble = Bitmap.createBitmap(b.getWidth() + 2 * pad, b.getHeight() + 2 * pad, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bubble);
            Paint blur = new Paint();
            blur.setMaskFilter(new BlurMaskFilter(pad / 2f, BlurMaskFilter.Blur.NORMAL));
            int[] off = new int[2];
            Bitmap shade = b.extractAlpha(blur, off);
            Paint p = new Paint();
            p.setColor(0x47000000);
            cv.drawBitmap(shade, pad + off[0], pad + off[1] + pad / 3f, p);
            cv.drawBitmap(b, pad, pad, null);
            shade.recycle();
        }
        return bubble;
    }

    /**
     * Reads a picked image (PNG, WebP, JPEG…; an animation's first frame), trims its transparent edges, scales it
     * down and saves it as Buddy's face. Throws if it isn't a picture.
     */
    static void save(Context c, Uri uri) throws IOException {
        int box = SIDE * 2;
        Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri), (d, info, src) -> {
            d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE); // readable pixels, for the trim
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float s = Math.min(1f, (float) box / Math.max(w, h));
            if (s < 1f) d.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
        });
        // a sticker often sits in a big transparent square: keep only the drawing, so it fills the bubble
        int w = b.getWidth(), h = b.getHeight(), left = w, top = h, right = -1, bottom = -1;
        if (b.hasAlpha()) {
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                b.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) {
                    if ((row[x] >>> 24) <= 16) continue;
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    bottom = y;
                }
            }
            if (right < 0) throw new IOException("empty picture");
        } else {
            left = top = 0;
            right = w - 1;
            bottom = h - 1;
        }
        int cw = right - left + 1, ch = bottom - top + 1;
        float s = Math.min(1f, (float) SIDE / Math.max(cw, ch));
        Bitmap crop = Bitmap.createBitmap(b, left, top, cw, ch);
        Bitmap out = Bitmap.createScaledBitmap(crop, Math.max(1, Math.round(cw * s)), Math.max(1, Math.round(ch * s)), true);
        File f = file(c), tmp = new File(f.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            out.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("can't save the picture");
        }
        synchronized (Face.class) {
            loaded = false; // read again on next use
        }
    }
}
