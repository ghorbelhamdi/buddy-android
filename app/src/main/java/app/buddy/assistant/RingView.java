package app.buddy.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Accent-coloured arcs swirling around Buddy while it's working; calm (fainter, slower) while the agent works only in
 * the background and the phone is free (see Bubble.refreshStatus).
 */
final class RingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float dp;
    private boolean spinning, calm;
    private long start;

    RingView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setSpinning(boolean on) {
        if (spinning == on) return;
        spinning = on;
        start = System.currentTimeMillis();
        setVisibility(on ? VISIBLE : GONE);
        if (on) postInvalidateOnAnimation();
    }

    void setCalm(boolean on) {
        if (calm == on) return;
        calm = on;
        start = System.currentTimeMillis(); // the new speed starts where the arcs are drawn from, not mid-turn
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!spinning) return;
        float t = (System.currentTimeMillis() - start) / 1000f * (calm ? 0.45f : 1f);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        // three arcs at different radii and speeds, like a little orbit
        float[] speed = {260f, -190f, 330f};
        float[] len = {95f, 70f, 55f};
        int[] alpha = {235, 170, 120};
        for (int i = 0; i < 3; i++) {
            float r = Math.min(cx, cy) - (2.5f + i * 3f) * dp; // all three stay outside Buddy's body
            box.set(cx - r, cy - r, cx + r, cy + r);
            paint.setStrokeWidth((3.4f - i * 0.7f) * dp);
            paint.setColor(Theme.withAlpha(Ui.ACCENT, calm ? alpha[i] * 2 / 5 : alpha[i]));
            float a = (t * speed[i] + i * 120f) % 360f;
            float l = len[i] + 25f * (float) Math.sin(t * 2.6f + i);
            c.drawArc(box, a, l, false, paint);
        }
        postInvalidateOnAnimation();
    }
}
