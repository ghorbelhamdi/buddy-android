package app.buddy.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Hold the bubble: its actions fan out around it in an arc (towards the middle of the screen), joined to the
 * bubble by glowing lines, with a pulse ring. Slide onto one and let go, or let go and tap. Only the actions
 * that make sense right now are shown (Stop task, Add instruction, Stop voice, Talk, Open chat…).
 * A small window around the bubble, not a full-screen overlay (see ControlIndicator).
 */
final class RadialMenu {
    static final class Action {
        final int icon;
        final String label;
        final boolean danger;
        final Runnable run;

        Action(int icon, String label, boolean danger, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.danger = danger;
            this.run = run;
        }
    }

    private final Context c;
    private final WindowManager wm;
    private FrameLayout win;
    private Glow glow;
    private final List<View> items = new ArrayList<>();
    private final List<float[]> targets = new ArrayList<>(); // item centres in the window
    private List<Action> actions;
    private int originX, originY, size, hovered = -1;
    private Runnable onClosed;

    RadialMenu(Context c, WindowManager wm) {
        this.c = c;
        this.wm = wm;
    }

    boolean isOpen() {
        return win != null;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** Open around a screen point (the bubble's centre); onRight: the bubble sits on the right edge. */
    void open(int cx, int cy, boolean onRight, List<Action> list, Runnable closed) {
        close(false);
        actions = list;
        onClosed = closed;
        size = dp(344); // the lowest action's two-line label must stay inside the window
        originX = cx - size / 2;
        originY = cy - size / 2;
        win = new FrameLayout(c);
        win.setClipChildren(false);
        glow = new Glow(c);
        win.addView(glow, new FrameLayout.LayoutParams(size, size));
        // an arc facing the middle of the screen
        int n = list.size();
        float radius = dp(n > 4 ? 112 : 100);
        float span = Math.min(170f, 44f * (n - 1));
        float mid = onRight ? 180f : 0f;
        items.clear();
        targets.clear();
        for (int i = 0; i < n; i++) {
            float a = n == 1 ? mid : mid - span / 2 + span * i / (n - 1);
            double rad = Math.toRadians(a);
            float tx = size / 2f + (float) (Math.cos(rad) * radius), ty = size / 2f + (float) (Math.sin(rad) * radius);
            targets.add(new float[]{tx, ty});
            View item = item(list.get(i));
            int w = dp(72), h = dp(92); // room for a label on two lines
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
            win.addView(item, lp);
            item.setX(size / 2f - w / 2f);
            item.setY(size / 2f - dp(26));
            item.setScaleX(0.2f);
            item.setScaleY(0.2f);
            item.setAlpha(0f);
            final int idx = i;
            item.setOnClickListener(v -> pick(idx));
            items.add(item);
            item.animate().x(tx - w / 2f).y(ty - dp(26)).scaleX(1f).scaleY(1f).alpha(1f)
                    .setStartDelay(25L * i).setDuration(320).setInterpolator(new OvershootInterpolator(1.6f))
                    .setUpdateListener(an -> glow.invalidate()).start();
        }
        win.setOnTouchListener((v, e) -> { // a tap outside the actions closes it
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE || e.getActionMasked() == MotionEvent.ACTION_DOWN) close(true);
            return false;
        });
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = originX;
        p.y = originY;
        try {
            wm.addView(win, p);
        } catch (RuntimeException e) { // the accessibility service restarted meanwhile: its window token is gone
            win = null;
            Runnable r = onClosed;
            onClosed = null;
            if (r != null) r.run(); // the bubble scales back as after any menu
            return;
        }
        glow.start();
    }

    private View item(Action a) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView ic = new ImageView(c);
        ic.setImageResource(a.icon);
        ic.setScaleType(ImageView.ScaleType.CENTER);
        int tint = a.danger ? Ui.ERR : Ui.ACCENT;
        ic.setImageTintList(ColorStateList.valueOf(tint));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xF0181614);
        bg.setStroke(dp(1.5f), Theme.withAlpha(tint, 0xCC));
        ic.setBackground(bg);
        ic.setElevation(dp(10));
        box.addView(ic, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView t = new TextView(c);
        t.setText(a.label);
        t.setTextColor(0xFFF4EFE6);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(Ui.sans(c, 600));
        // two short lines at most: longer labels (and most translations) don't fit one line in 72dp
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setLineSpacing(0, 0.9f);
        t.setShadowLayer(dp(4), 0, 0, 0xFF000000);
        t.setPaddingRelative(0, dp(4), 0, 0);
        box.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /**
     * A hint, not a menu: the actions pop halfway out with "Hold me", glow for a moment and slide back in,
     * so people see the bubble has more to it. Can't be touched.
     */
    void peek(int cx, int cy, boolean onRight, List<Action> list) {
        if (win != null) return;
        open(cx, cy, onRight, list, null);
        if (win == null) return; // couldn't be shown: no hint this time
        final FrameLayout w = win;
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) w.getLayoutParams();
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            wm.updateViewLayout(w, p);
        } catch (RuntimeException e) { // gone meanwhile, as in open()
            close(false);
            return;
        }
        // only half way out, smaller
        for (int i = 0; i < items.size(); i++) {
            View it = items.get(i);
            float[] t = targets.get(i);
            float hx = size / 2f + (t[0] - size / 2f) * 0.62f, hy = size / 2f + (t[1] - size / 2f) * 0.62f;
            it.animate().cancel();
            it.animate().x(hx - dp(36)).y(hy - dp(26)).scaleX(0.72f).scaleY(0.72f).alpha(0.9f)
                    .setStartDelay(40L * i).setDuration(360).setInterpolator(new OvershootInterpolator(2f))
                    .setUpdateListener(an -> glow.invalidate()).start();
        }
        TextView hint = new TextView(c);
        hint.setText(R.string.menu_peek_hint);
        hint.setTextColor(0xFFF4EFE6);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTypeface(Ui.sans(c, 700));
        hint.setPaddingRelative(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(dp(12));
        hb.setColor(Ui.ACCENT);
        hint.setBackground(hb);
        hint.setTextColor(Ui.ON_ACCENT);
        hint.measure(0, 0);
        w.addView(hint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        hint.setX(size / 2f - hint.getMeasuredWidth() / 2f);
        hint.setY(size / 2f + dp(40));
        hint.setAlpha(0f);
        hint.animate().alpha(1f).translationYBy(-dp(6)).setDuration(300).start();
        w.postDelayed(() -> {
            if (win == w) close(true);
        }, 1900);
    }

    /** The finger moved (screen coordinates, while still held after the long-press): highlight what's under it. */
    void hover(float rawX, float rawY) {
        if (win == null) return;
        float x = rawX - originX, y = rawY - originY;
        int best = -1;
        float bestD = dp(40);
        for (int i = 0; i < targets.size(); i++) {
            float d = (float) Math.hypot(x - targets.get(i)[0], y - targets.get(i)[1]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best == hovered) return;
        if (hovered >= 0) items.get(hovered).animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        hovered = best;
        if (best >= 0) {
            items.get(best).animate().scaleX(1.22f).scaleY(1.22f).setDuration(140)
                    .setInterpolator(new OvershootInterpolator(3f)).start();
            items.get(best).performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }
        glow.invalidate();
    }

    /** The finger was lifted: run what it's on, or stay open for a tap. */
    void release() {
        if (win == null) return;
        if (hovered >= 0) pick(hovered);
    }

    private void pick(int i) {
        Action a = actions.get(i);
        View v = items.get(i);
        v.animate().scaleX(1.5f).scaleY(1.5f).alpha(0f).setDuration(180).start();
        close(true);
        a.run.run();
    }

    void close(boolean animate) {
        if (win == null) return;
        final FrameLayout w = win;
        win = null;
        hovered = -1;
        glow.stop();
        Runnable remove = () -> {
            try {
                wm.removeView(w);
            } catch (Exception ignored) {
            }
        };
        if (animate) {
            for (View it : items) {
                it.animate().x(size / 2f - it.getWidth() / 2f).y(size / 2f - dp(26)).scaleX(0.2f).scaleY(0.2f)
                        .alpha(0f).setStartDelay(0).setDuration(180).start();
            }
            w.animate().alpha(0f).setDuration(200).withEndAction(remove).start();
        } else {
            remove.run();
        }
        if (onClosed != null) onClosed.run();
    }

    /** The pulse ring at the bubble and the lines to each action. */
    private final class Glow extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
                halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator pulse;
        private float t;

        Glow(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            line.setStrokeWidth(dp(1.5f));
            line.setStrokeCap(Paint.Cap.ROUND);
        }

        void start() {
            pulse = ValueAnimator.ofFloat(0f, 1f);
            pulse.setDuration(1400);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
            pulse.start();
        }

        void stop() {
            if (pulse != null) pulse.cancel();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            // soft halo behind the bubble
            halo.setShader(new RadialGradient(cx, cy, dp(120), Theme.withAlpha(Ui.ACCENT, 0x55), 0x00000000, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, dp(120), halo);
            // two expanding rings
            for (int k = 0; k < 2; k++) {
                float p = (t + k * 0.5f) % 1f;
                ring.setColor(Theme.withAlpha(Ui.ACCENT, (int) (0xAA * (1 - p))));
                cv.drawCircle(cx, cy, dp(38) + p * dp(70), ring);
            }
            // energy lines to the actions (brighter on the one under the finger)
            for (int i = 0; i < items.size(); i++) {
                View it = items.get(i);
                float ix = it.getX() + it.getWidth() / 2f, iy = it.getY() + dp(26);
                boolean hot = i == hovered;
                line.setColor(Theme.withAlpha(Ui.ACCENT, (int) ((hot ? 0xFF : 0x66) * it.getAlpha())));
                float dx = ix - cx, dy = iy - cy, len = (float) Math.hypot(dx, dy);
                if (len < dp(40)) continue;
                float sx = cx + dx / len * dp(36), sy = cy + dy / len * dp(36);
                float ex = ix - dx / len * dp(28), ey = iy - dy / len * dp(28);
                cv.drawLine(sx, sy, ex, ey, line);
                // a spark travelling along the line
                float s = (t * 1.6f + i * 0.17f) % 1f;
                line.setColor(Theme.withAlpha(0xFFFFFFFF, (int) (0xCC * it.getAlpha())));
                cv.drawCircle(sx + (ex - sx) * s, sy + (ey - sy) * s, dp(hot ? 2.5f : 1.6f), line);
            }
        }
    }
}
