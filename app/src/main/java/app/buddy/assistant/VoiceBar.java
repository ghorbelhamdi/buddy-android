package app.buddy.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import static app.buddy.assistant.Ui.*;

/**
 * What the composer turns into while you talk (like a voice message in a messenger): cancel, a live
 * waveform of the microphone, a timer or status, and send. Words heard so far show above it. In a live voice call
 * (see CodexLive) it says "Live" and has End instead (cancel), the waveform shows both sides, the line what's said.
 */
final class VoiceBar extends LinearLayout {
    interface Actions {
        void cancel();

        void send();
    }

    private final TextView heard, status;
    private final Wave wave;
    private long started;
    private String state; // null: recording (timer shows)

    VoiceBar(Context c, Actions a) {
        this(c, a, false);
    }

    VoiceBar(Context c, Actions a, boolean live) {
        super(c);
        setOrientation(VERTICAL);
        heard = text(c, "", 14, MUTED);
        heard.setMaxLines(3);
        heard.setEllipsize(android.text.TextUtils.TruncateAt.START);
        heard.setPaddingRelative(dp(c, 14), dp(c, 8), dp(c, 14), 0);
        heard.setVisibility(GONE);
        addView(heard, full());

        LinearLayout row = row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        if (live) {
            View dot = new View(c);
            dot.setBackground(oval(ERR));
            LayoutParams dp = new LayoutParams(dp(c, 10), dp(c, 10));
            dp.setMarginStart(dp(c, 12));
            dp.setMarginEnd(dp(c, 6));
            row.addView(dot, dp);
            row.addView(monoText(c, c.getString(R.string.live_label), 12, ERR), wrap());
        } else {
            ImageButton cancel = iconButton(c, R.drawable.ms_close, c.getString(android.R.string.cancel), MUTED);
            cancel.setLayoutParams(new LayoutParams(dp(c, 40), dp(c, 40)));
            cancel.setOnClickListener(v -> a.cancel());
            row.addView(cancel);
        }
        wave = new Wave(c);
        LayoutParams wp = new LayoutParams(0, dp(c, 32), 1);
        wp.leftMargin = wp.rightMargin = dp(c, 6);
        row.addView(wave, wp);
        status = monoText(c, "", 12, MUTED);
        status.setSingleLine(true);
        row.addView(status, wrap());
        if (live) {
            android.widget.Button end = button(c, c.getString(R.string.live_end), false);
            end.setTextColor(ERR);
            end.setBackground(rounded(c, ERR_SOFT, 10));
            end.setMinHeight(dp(c, 34));
            end.setMinimumHeight(dp(c, 34));
            end.setPaddingRelative(dp(c, 14), 0, dp(c, 14), 0);
            end.setOnClickListener(v -> a.cancel());
            LayoutParams ep = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(c, 34));
            ep.setMarginStart(dp(c, 8));
            ep.setMarginEnd(dp(c, 4));
            row.addView(end, ep);
            addView(row, full());
            setBackground(outlined(c, CARD, 26, ERR, 1));
            return;
        }
        ImageButton send = new ImageButton(c);
        send.setImageResource(R.drawable.ms_arrow_upward);
        send.setContentDescription(c.getString(R.string.voice_stop_and_send));
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setImageTintList(ColorStateList.valueOf(ON_ACCENT));
        send.setBackground(oval(ACCENT));
        send.setOnClickListener(v -> a.send());
        LayoutParams sp = new LayoutParams(dp(c, 40), dp(c, 40));
        sp.setMarginStart(dp(c, 8));
        row.addView(send, sp);
        addView(row, full());
        setBackground(outlined(c, CARD, 26, ACCENT, 1));
    }

    /** Recording starts now (or once a status like "Starting Claude…" is cleared). */
    void begin(String initialState) {
        started = System.currentTimeMillis();
        state = initialState;
        wave.live = initialState == null;
        heard.setText("");
        heard.setVisibility(GONE);
        wave.reset();
        tick();
    }

    /** The timer counts from this moment (System.currentTimeMillis), e.g. when a live call started. */
    void since(long t) {
        started = t;
        tick();
    }

    void setState(String s) {
        if (s == null && state != null) started = System.currentTimeMillis();
        state = s;
        wave.live = s == null;
        tick();
    }

    /** A live call's status (Listening…, Speaking…): shown beside the timer, the waveform stays live. */
    void setLiveStatus(String s) {
        liveStatus = s;
        tick();
    }

    private String liveStatus;

    void setHeard(String t) {
        heard.setText(t);
        heard.setVisibility(t == null || t.isEmpty() ? GONE : VISIBLE);
    }

    void level(float v) {
        wave.level = Math.max(wave.level, v);
    }

    private void tick() {
        if (state != null) {
            status.setText(state);
        } else {
            long s = (System.currentTimeMillis() - started) / 1000;
            String time = String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
            status.setText(liveStatus == null ? time : liveStatus + "  " + time);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (getVisibility() == VISIBLE) {
                wave.push();
                tick();
            }
            postDelayed(this, 70);
        }
    };

    /** Bars scrolling right to left, one per tick, height = the loudest level heard in that tick. */
    private static final class Wave extends View {
        private final float[] bars = new float[64];
        private int head;
        float level;
        boolean live = true;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Wave(Context c) {
            super(c);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void reset() {
            java.util.Arrays.fill(bars, 0);
            level = 0;
        }

        void push() {
            bars[head] = live ? level : 0;
            head = (head + 1) % bars.length;
            level = 0;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), mid = h / 2f;
            float step = getResources().getDisplayMetrics().density * 5;
            int n = Math.min(bars.length, (int) (w / step));
            p.setStrokeWidth(step * 0.55f);
            p.setColor(live ? ACCENT : MUTED);
            for (int i = 0; i < n; i++) {
                float v = bars[(head - n + i + bars.length * 2) % bars.length];
                float half = Math.max(step * 0.3f, v * (h / 2f - step * 0.3f));
                float x = w - (n - i) * step + step / 2f;
                c.drawLine(x, mid - half, x, mid + half, p);
            }
        }
    }
}
