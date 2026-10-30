package app.buddy.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A small speedometer for a chat's top bar: how much of the plan's tighter window (5-hour or weekly) is used. */
final class UsageGauge extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private int percent;

    UsageGauge(Context c) {
        super(c);
        float w = 2.5f * c.getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{track, fill}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        needle.setStrokeWidth(Ui.dp(c, 2));
        needle.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(int percent) {
        this.percent = Math.max(0, Math.min(100, percent));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Ui.dp(getContext(), 22), pad = track.getStrokeWidth() / 2;
        float left = (getWidth() - size) / 2f, top = (getHeight() - size) / 2f + Ui.dp(getContext(), 2);
        box.set(left + pad, top + pad, left + size - pad, top + size - pad);
        int color = percent >= 80 ? Ui.ERR : Ui.ACCENT;
        track.setColor(Theme.withAlpha(Ui.MUTED, 0x55));
        fill.setColor(color);
        needle.setColor(color);
        // a 240° dial from lower left to lower right, filled up to the percentage
        c.drawArc(box, 150, 240, false, track);
        if (percent > 0) c.drawArc(box, 150, 240 * percent / 100f, false, fill);
        double a = Math.toRadians(150 + 240 * percent / 100.0);
        float cx = box.centerX(), cy = box.centerY(), r = box.width() / 2 - Ui.dp(getContext(), 3);
        c.drawLine(cx, cy, cx + (float) (r * Math.cos(a)), cy + (float) (r * Math.sin(a)), needle);
    }
}
