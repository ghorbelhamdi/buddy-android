package app.buddy.assistant;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import static app.buddy.assistant.Ui.*;

/**
 * In a chat that works in the background: a small live picture of the chat's own screen (tap it for a bigger one)
 * and "Show on my screen", which moves the app to the real screen and lets the chat go on there. It only looks:
 * touches never reach the background screen.
 */
final class BackgroundPanel extends LinearLayout {
    private static final long EVERY_MS = 1000;
    private final Activity a;
    private final Sessions.Session ses;
    private final ImageView picture;
    private final TextView app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean shown, large, loading;
    private final Runnable tick = this::load;

    BackgroundPanel(Activity a, Sessions.Session ses) {
        super(a);
        this.a = a;
        this.ses = ses;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(rounded(a, CARD, 16));
        setPaddingRelative(dp(a, 10), dp(a, 10), dp(a, 12), dp(a, 10));
        picture = new ImageView(a);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.setBackground(rounded(a, CARD2, 10));
        picture.setClipToOutline(true);
        picture.setContentDescription(a.getString(R.string.background_preview));
        picture.setOnClickListener(v -> {
            large = !large;
            size();
            load();
        });
        addView(picture);
        LinearLayout side = column(a);
        side.setPaddingRelative(dp(a, 12), 0, 0, 0);
        TextView label = text(a, a.getString(R.string.chat_background), 11, ACCENT_TEXT);
        label.setTypeface(sans(a, 600));
        label.setLetterSpacing(0.06f);
        side.addView(label);
        app = single(text(a, "", 13, MUTED));
        side.addView(app);
        Button show = button(a, a.getString(R.string.background_show), false);
        show.setMinHeight(dp(a, 36));
        show.setMinimumHeight(dp(a, 36));
        show.setPaddingRelative(dp(a, 14), 0, dp(a, 14), 0);
        show.setOnClickListener(v -> {
            BuddyService svc = BuddyService.get();
            if (svc == null) return;
            show.setEnabled(false);
            new Thread(() -> {
                Background.bringToScreen(svc, ses);
                main.post(() -> {
                    show.setEnabled(true);
                    refresh();
                });
            }).start();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(a, 36));
        sp.topMargin = dp(a, 8);
        side.addView(show, sp);
        addView(side, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        size();
        setVisibility(GONE);
    }

    private void size() {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        int w = dp(a, large ? 150 : 56);
        picture.setLayoutParams(new LinearLayout.LayoutParams(w, Math.round(w * (m.heightPixels / (float) m.widthPixels))));
    }

    /** The chat's screen is on show (the activity is in front) or not: the picture only updates while it is. */
    void setShown(boolean on) {
        shown = on;
        main.removeCallbacks(tick);
        if (on) refresh();
    }

    /** Is there a background screen to show for this chat right now? Then the panel shows and keeps its picture fresh. */
    void refresh() {
        boolean on = ses.background && Background.displayOf(ses.id) != android.view.Display.DEFAULT_DISPLAY;
        setVisibility(on ? VISIBLE : GONE);
        main.removeCallbacks(tick);
        if (on && shown) load();
    }

    private void load() {
        main.removeCallbacks(tick);
        if (!shown || loading || getVisibility() != VISIBLE) return;
        loading = true;
        final int width = dp(a, large ? 150 : 56) * 2;
        new Thread(() -> {
            BuddyService svc = BuddyService.get();
            byte[] jpg = Background.frame(a.getApplicationContext(), ses.id, width, 70);
            final Bitmap b = jpg == null ? null : BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            final String pkg = svc == null ? null : Background.appOn(svc, ses.id);
            String name = pkg;
            try {
                if (pkg != null) name = String.valueOf(a.getPackageManager().getApplicationLabel(
                        a.getPackageManager().getApplicationInfo(pkg, 0)));
            } catch (Exception ignored) {
            }
            final String label = name == null ? "" : name;
            main.post(() -> {
                loading = false;
                if (b != null) picture.setImageBitmap(b);
                app.setText(label);
                if (Background.displayOf(ses.id) == android.view.Display.DEFAULT_DISPLAY) {
                    refresh(); // its screen went (idle for long, or brought to the real one)
                    return;
                }
                if (shown) main.postDelayed(tick, EVERY_MS);
            });
        }).start();
    }
}
