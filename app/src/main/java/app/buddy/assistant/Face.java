package app.buddy.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
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
    private static Bitmap picture; // decoded once, dropped when a new picture is saved
    private static Drawable bubble;
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

    /**
     * The size, in dp, for Buddy's face in an avatar or tile: {@code buddy} for the drawing (it has margins of its
     * own), {@code picture} for a picture, which is trimmed to its edges and often detailed.
     */
    static int size(Context c, int buddy, int picture) {
        return picture(c) != null ? picture : buddy;
    }

    /** Shows Buddy's face in an avatar or tile: the picked picture, else the drawing {@code res}. */
    static void set(ImageView v, int res) {
        Bitmap b = picture(v.getContext());
        if (b != null) v.setImageBitmap(b);
        else v.setImageResource(res);
    }

    /**
     * The floating bubble's picture: it fills the face, and a soft shadow is drawn around and under it, outside the
     * face (as the drawn face gets one from its outline), so the shadow doesn't make the picture smaller.
     */
    static synchronized Drawable bubble(Context c) {
        Bitmap b = picture(c);
        if (b == null) return null;
        if (bubble == null) bubble = new Shadowed(b);
        return bubble;
    }

    /** A picture filling its bounds, with its shadow drawn past them (the face's parent doesn't clip it). */
    private static final class Shadowed extends Drawable {
        private final Bitmap pic, shade;
        private final float dx, dy; // the shade's corner, from the picture's, in picture pixels
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), shadePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        Shadowed(Bitmap pic) {
            this.pic = pic;
            float r = Math.max(pic.getWidth(), pic.getHeight()) / 32f;
            Paint blur = new Paint();
            blur.setMaskFilter(new BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL));
            int[] off = new int[2];
            shade = pic.extractAlpha(blur, off);
            dx = off[0];
            dy = off[1] + r * 2 / 3; // a little lower, as if lit from above
            shadePaint.setColor(0x47000000);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            c.save();
            c.translate(b.left, b.top);
            c.scale((float) b.width() / pic.getWidth(), (float) b.height() / pic.getHeight());
            c.drawBitmap(shade, dx, dy, shadePaint);
            c.drawBitmap(pic, 0, 0, paint);
            c.restore();
        }

        @Override
        public int getIntrinsicWidth() {
            return pic.getWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return pic.getHeight();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            shadePaint.setAlpha(alpha * 0x47 / 255);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter f) {
            paint.setColorFilter(f);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
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
