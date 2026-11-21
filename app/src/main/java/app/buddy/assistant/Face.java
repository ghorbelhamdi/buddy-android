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
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Buddy's face: the built-in drawings, or a picture picked in Settings (issue #10). The picture lives in the app's
 * own storage, so it stays through updates; when it's missing or can't be read, Buddy's own face shows. An animated
 * one (GIF, animated WebP) moves on the bubble (issue #68), and shows its first frame everywhere else.
 */
final class Face {
    /** Longest side of the saved picture, in px: sharp at the bubble's 60dp on any screen. */
    private static final int SIDE = 256;
    private static Bitmap picture; // decoded once, dropped when a new picture is saved
    private static Drawable bubble;
    private static boolean loaded;
    /** The animated picture as the bubble plays it (decoded once), and whether it couldn't be read. */
    private static AnimatedImageDrawable anim;
    private static Cropped cropped;
    private static boolean animBroken;
    /** A bigger animation is kept as a still picture (its first frame): the bubble would decode it on every frame. */
    private static final long ANIM_MAX = 16L << 20;

    private Face() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "face.png");
    }

    /** The animated picture as it was picked (its first frame is face.png). */
    private static File animFile(Context c) {
        return new File(c.getFilesDir(), "face.anim");
    }

    /** Where the bubble crops the animation: "left top right bottom", fractions of a frame. */
    private static File cropFile(Context c) {
        return new File(c.getFilesDir(), "face.anim.crop");
    }

    /** Room left around an animation's drawing when it's cropped (for frames between the ones looked at). */
    private static final float CROP_MARGIN = 0.02f;

    /**
     * Where a GIF's frames draw, all of them together: {left, top, right, bottom} as fractions of a frame, from
     * every frame (Movie, the platform's GIF reader, looked at every 10 ms); null for anything else (an animated WebP).
     */
    @SuppressWarnings("deprecation")
    private static float[] framesBox(Context c, Uri uri) {
        android.graphics.Movie m;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            m = in == null ? null : android.graphics.Movie.decodeStream(new java.io.BufferedInputStream(in));
        } catch (Exception e) {
            return null;
        }
        if (m == null || m.width() <= 0 || m.height() <= 0) return null;
        int w = m.width(), h = m.height();
        float s = Math.min(1f, 256f / Math.max(w, h)); // looked at small: where, not how
        int sw = Math.max(1, Math.round(w * s)), sh = Math.max(1, Math.round(h * s));
        Bitmap frame = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(frame);
        cv.scale(s, s);
        int left = sw, top = sh, right = -1, bottom = -1, dur = Math.max(m.duration(), 1);
        int[] row = new int[sw];
        // every 10 ms catches every frame (a GIF's shortest delay); a long one is looked at 1500 times, evenly
        int step = Math.max(10, dur / 1500);
        for (int t = 0; t < dur; t += step) {
            frame.eraseColor(0);
            m.setTime(t);
            m.draw(cv, 0, 0);
            for (int y = 0; y < sh; y++) {
                frame.getPixels(row, 0, sw, 0, y, sw, 1);
                for (int x = 0; x < sw; x++) {
                    if ((row[x] >>> 24) <= 16) continue;
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
            if (left == 0 && top == 0 && right == sw - 1 && bottom == sh - 1) break; // (all of it already)
        }
        frame.recycle();
        if (right < 0) return null;
        return new float[]{(float) left / sw, (float) top / sh, (float) (right + 1) / sw, (float) (bottom + 1) / sh};
    }

    /** {left, top, right, bottom} as fractions of a frame, or the whole frame. */
    private static float[] crop(Context c) {
        try {
            String[] p = new String(java.nio.file.Files.readAllBytes(cropFile(c).toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim().split("\\s+");
            float[] f = {Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])};
            if (f[2] > f[0] && f[3] > f[1]) return f;
        } catch (Exception ignored) { // none (saved before 2.3.18), or broken: the whole frame
        }
        return new float[]{0, 0, 1, 1};
    }

    /** Is the saved picture animated? */
    static boolean animated(Context c) {
        return animFile(c).length() > 0;
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
        String play = Prefs.faceAnim(c);
        if (!"off".equals(play) && animated(c) && !animBroken) {
            if (anim == null) {
                try {
                    Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(animFile(c)), (dec, info, src) -> {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                        float s = Math.min(1f, (float) SIDE / Math.max(w, h)); // decoded at the bubble's size
                        if (s < 1f) dec.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
                    });
                    if (d instanceof AnimatedImageDrawable) anim = (AnimatedImageDrawable) d;
                    else animBroken = true;
                } catch (IOException e) {
                    animBroken = true;
                }
            }
            if (anim != null) {
                anim.setRepeatCount("loop".equals(play) ? AnimatedImageDrawable.REPEAT_INFINITE : 0);
                if (cropped == null || cropped.anim != anim) cropped = new Cropped(anim, crop(c));
                return cropped; // (no shadow: it would have to follow every frame)
            }
        }
        if (bubble == null) bubble = new Shadowed(b);
        return bubble;
    }

    /**
     * The animated picture cropped to where its frames draw: its drawing fills the bubble, as the still picture's does
     * (issue #68). Plays and stops with it (Animatable), and redraws on every frame it gets.
     */
    static final class Cropped extends Drawable implements Drawable.Callback, android.graphics.drawable.Animatable {
        final AnimatedImageDrawable anim;
        private final float[] f;

        Cropped(AnimatedImageDrawable anim, float[] f) {
            this.anim = anim;
            this.f = f;
            anim.setCallback(this);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            // the frame grows so that its crop fills these bounds
            float fw = f[2] - f[0], fh = f[3] - f[1];
            float w = b.width() / fw, h = b.height() / fh;
            int l = Math.round(b.left - f[0] * w), t = Math.round(b.top - f[1] * h);
            anim.setBounds(l, t, Math.round(l + w), Math.round(t + h));
        }

        @Override
        public void draw(Canvas c) {
            c.save();
            c.clipRect(getBounds()); // (only the crop: the rest of the frame stays out of the bubble)
            anim.draw(c);
            c.restore();
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.max(1, Math.round(anim.getIntrinsicWidth() * (f[2] - f[0])));
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.max(1, Math.round(anim.getIntrinsicHeight() * (f[3] - f[1])));
        }

        @Override
        public void setAlpha(int alpha) {
            anim.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            anim.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void start() {
            anim.start();
        }

        @Override
        public void stop() {
            anim.stop();
        }

        @Override
        public boolean isRunning() {
            return anim.isRunning();
        }

        @Override
        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }
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
     * Reads a picked image (PNG, WebP, JPEG…), trims its transparent edges, scales it down and saves it as Buddy's
     * face. An animated one is kept as it is too, for the bubble, cropped there to where any of its frames draws (a
     * GIF; an animated WebP shows whole). Throws if it isn't a picture. True when it was an animation too big to keep
     * moving.
     */
    static boolean save(Context c, Uri uri) throws IOException {
        int box = SIDE * 2;
        boolean moving;
        try {
            moving = ImageDecoder.decodeDrawable(ImageDecoder.createSource(c.getContentResolver(), uri), (d, info, src) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float s = Math.min(1f, (float) box / Math.max(w, h));
                if (s < 1f) d.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
            }) instanceof AnimatedImageDrawable;
        } catch (IOException e) {
            moving = false; // the still decode below says whether it's a picture at all
        }
        boolean tooBig = false;
        if (moving) { // known before the first frame is saved: a still one is trimmed as any picture
            try (android.content.res.AssetFileDescriptor fd = c.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                long len = fd == null ? -1 : fd.getLength();
                if (len > ANIM_MAX) {
                    moving = false;
                    tooBig = true;
                }
            } catch (IOException | RuntimeException e) { // (the copy below counts as it goes, too)
            }
        }
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
            if (right < 0 && !moving) throw new IOException("empty picture");
            if (right < 0) { // an animation that starts blank: the frames below say where its drawing is
                left = top = 0;
                right = w - 1;
                bottom = h - 1;
            }
        } else {
            left = top = 0;
            right = w - 1;
            bottom = h - 1;
        }
        if (moving) { // the animation is cropped to where any of its frames draws (issue #68: it looked zoomed out)
            float[] all = framesBox(c, uri);
            if (all == null) { // not a GIF (an animated WebP): its frames can't be looked at, so the whole frame
                left = top = 0;
                right = w - 1;
                bottom = h - 1;
            } else {
                left = Math.round(all[0] * w);
                top = Math.round(all[1] * h);
                right = Math.min(w - 1, Math.round(all[2] * w) - 1);
                bottom = Math.min(h - 1, Math.round(all[3] * h) - 1);
                int mx = Math.round(w * CROP_MARGIN), my = Math.round(h * CROP_MARGIN);
                left = Math.max(0, left - mx);
                top = Math.max(0, top - my);
                right = Math.min(w - 1, right + mx);
                bottom = Math.min(h - 1, bottom + my);
                if (right <= left || bottom <= top) { // nothing drawn in any frame: the whole frame
                    left = top = 0;
                    right = w - 1;
                    bottom = h - 1;
                }
            }
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
        File a = animFile(c), cropAt = cropFile(c);
        if (moving) { // where it's cropped, as fractions of a frame
            try (OutputStream os = new FileOutputStream(cropAt)) {
                os.write(String.format(java.util.Locale.ROOT, "%f %f %f %f", (float) left / w, (float) top / h,
                        (float) (right + 1) / w, (float) (bottom + 1) / h).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                cropAt.delete(); // (the bubble shows the whole frame then)
            }
        } else {
            cropAt.delete();
        }
        if (moving) { // the file as picked: the bubble plays it
            File at = new File(a.getPath() + ".tmp");
            long n = 0;
            try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(at)) {
                if (in == null) throw new IOException("can't read the picture");
                byte[] buf = new byte[64 * 1024];
                for (int r; (r = in.read(buf)) > 0; ) {
                    n += r;
                    if (n > ANIM_MAX) throw new IOException("too big");
                    os.write(buf, 0, r);
                }
            } catch (IOException e) {
                at.delete();
                a.delete(); // the first frame stays, still
                n = -1;
            }
            if (n > 0 && !at.renameTo(a)) at.delete();
        } else {
            a.delete();
        }
        synchronized (Face.class) {
            loaded = false; // read again on next use
            anim = null;
            cropped = null;
            animBroken = false;
        }
        return tooBig;
    }
}
