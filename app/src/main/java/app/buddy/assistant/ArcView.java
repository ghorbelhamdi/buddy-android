package app.buddy.assistant;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tesla-coil effect between the dragged bubble and the dismiss target: both crackle with sparks,
 * lightning reaches from each toward the other as they get closer, then locks into a continuous arc.
 * Bolts are regenerated constantly so they flicker. Positions are in screen pixels; time-based.
 */
final class ArcView extends View {
    private static final class Bolt {
        final Path path = new Path();
        float life, maxLife, width;
        int color;
    }

    private final List<Bolt> bolts = new ArrayList<>();
    private final Random rnd = new Random();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final int[] origin = new int[2];

    private float ax = Float.NaN, ay, bx = Float.NaN, by;
    private float closeness;
    private boolean fused, running;
    private long last;
    private float clock, flash;
    private float debtA, debtB, debtReach, debtArc;

    private static final int CYAN = 0xFF7DF9FF, BLUE = 0xFF4F8BFF, VIOLET = 0xFFB07CFF;

    ArcView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{glowPaint, corePaint, fill}) p.setBlendMode(BlendMode.PLUS);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        corePaint.setStyle(Paint.Style.STROKE);
        corePaint.setStrokeCap(Paint.Cap.ROUND);
        corePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    void update(float bubbleX, float bubbleY, float targetX, float targetY, boolean snapped) {
        ax = bubbleX;
        ay = bubbleY;
        bx = targetX;
        by = targetY;
        fused = snapped;
        float d = (float) Math.hypot(ax - bx, ay - by);
        closeness = snapped ? 1f : Math.max(0f, Math.min(1f, 1f - d / (460 * dp)));
        if (!running) {
            running = true;
            last = System.nanoTime();
            postInvalidateOnAnimation();
        }
    }

    /** Flash and a burst of bolts from the target, then everything fades. */
    void explode() {
        if (!Float.isNaN(bx)) {
            flash = 1f;
            for (int i = 0; i < 16; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                float len = (90 + rnd.nextFloat() * 130) * dp;
                addBolt(bx, by, bx + (float) Math.cos(a) * len, by + (float) Math.sin(a) * len,
                        0.18f + rnd.nextFloat() * 0.12f, 1.4f, rnd.nextBoolean() ? CYAN : VIOLET, true);
            }
        }
        ax = Float.NaN;
        closeness = 0f;
        fused = false;
        if (!running) {
            running = true;
            last = System.nanoTime();
            postInvalidateOnAnimation();
        }
    }

    void stop() {
        ax = Float.NaN;
        closeness = 0f;
        fused = false;
    }

    boolean idle() {
        return bolts.isEmpty() && Float.isNaN(ax) && flash <= 0f;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = Math.min(0.033f, (now - last) / 1e9f);
        last = now;
        clock += dt;
        getLocationOnScreen(origin);
        c.translate(-origin[0], -origin[1]);

        boolean feeding = !Float.isNaN(ax);
        if (feeding) {
            float d = (float) Math.hypot(ax - bx, ay - by);
            if (fused) {
                // locked on: corona discharge all around the rim, plus arcs crawling along the edge
                debtB += dt * 110;
                while (debtB >= 1) {
                    debtB--;
                    spark(bx, by, 36 * dp, (18 + rnd.nextFloat() * 34) * dp, 1.1f);
                }
                debtArc += dt * 28;
                while (debtArc >= 1) {
                    debtArc--;
                    double a = rnd.nextDouble() * Math.PI * 2, span = 0.7 + rnd.nextFloat() * 0.9;
                    float r = 38 * dp;
                    addBolt(bx + (float) Math.cos(a) * r, by + (float) Math.sin(a) * r,
                            bx + (float) Math.cos(a + span) * r, by + (float) Math.sin(a + span) * r,
                            0.06f + rnd.nextFloat() * 0.05f, 1.3f, rnd.nextFloat() < 0.7f ? CYAN : VIOLET, false);
                }
            } else {
                // surface sparks on both electrodes
                debtA += dt * (6 + 14 * closeness);
                debtB += dt * (8 + 14 * closeness);
                while (debtA >= 1) {
                    debtA--;
                    spark(ax, ay, 30 * dp, (14 + 26 * closeness) * dp, 0.8f);
                }
                while (debtB >= 1) {
                    debtB--;
                    spark(bx, by, 32 * dp, (16 + 28 * closeness) * dp, 0.9f);
                }
                // lightning reaching toward each other
                if (closeness > 0.12f) {
                    debtReach += dt * (5 + 30 * closeness);
                    while (debtReach >= 1) {
                        debtReach--;
                        boolean fromA = rnd.nextBoolean();
                        float sx = fromA ? ax : bx, sy = fromA ? ay : by;
                        float tx = fromA ? bx : ax, ty = fromA ? by : ay;
                        float reach = d * Math.min(1f, 0.2f + 0.75f * closeness * closeness) * (0.6f + rnd.nextFloat() * 0.4f);
                        float ang = (float) Math.atan2(ty - sy, tx - sx) + (rnd.nextFloat() - 0.5f) * (1.1f - closeness);
                        float rim = (fromA ? 28 : 31) * dp;
                        addBolt(sx + (float) Math.cos(ang) * rim, sy + (float) Math.sin(ang) * rim,
                                sx + (float) Math.cos(ang) * (rim + reach), sy + (float) Math.sin(ang) * (rim + reach),
                                0.06f + rnd.nextFloat() * 0.06f, 0.8f + closeness * 0.6f, rnd.nextFloat() < 0.75f ? CYAN : VIOLET, true);
                    }
                }
                // close enough: a continuous arc between the two
                if (closeness > 0.62f) {
                    debtArc += dt * (8 + 26 * (closeness - 0.62f) / 0.38f);
                    while (debtArc >= 1) {
                        debtArc--;
                        addBolt(ax, ay, bx, by, 0.05f + rnd.nextFloat() * 0.05f, 1f + closeness, CYAN, true);
                    }
                }
            }
            // charged glow on each electrode, buzzing
            float buzz = 0.82f + 0.18f * (float) Math.abs(Math.sin(clock * 47) * Math.sin(clock * 13));
            if (fused) {
                glow(c, bx, by, 96 * dp * buzz, 1f);
            } else {
                glow(c, ax, ay, (46 + 22 * closeness) * dp * buzz, 0.45f + 0.45f * closeness);
                glow(c, bx, by, (50 + 22 * closeness) * dp * buzz, 0.5f + 0.45f * closeness);
            }
        }

        if (flash > 0f && !Float.isNaN(bx)) {
            glow(c, bx, by, 220 * dp * (1.2f - flash * 0.4f), flash);
            flash -= dt * 4f;
        }

        for (int i = bolts.size() - 1; i >= 0; i--) {
            Bolt b = bolts.get(i);
            b.life -= dt;
            if (b.life <= 0) {
                bolts.remove(i);
                continue;
            }
            float t = b.life / b.maxLife;
            glowPaint.setColor(withAlpha(b.color, (int) (110 * t)));
            glowPaint.setStrokeWidth(b.width * 7 * dp);
            c.drawPath(b.path, glowPaint);
            glowPaint.setColor(withAlpha(b.color, (int) (170 * t)));
            glowPaint.setStrokeWidth(b.width * 3 * dp);
            c.drawPath(b.path, glowPaint);
            corePaint.setColor(withAlpha(0xFFFFFFFF, (int) (245 * t)));
            corePaint.setStrokeWidth(Math.max(1f, b.width * 1.2f * dp));
            c.drawPath(b.path, corePaint);
        }

        if (feeding || !bolts.isEmpty() || flash > 0f) postInvalidateOnAnimation();
        else running = false;
    }

    /** A short spark jumping off the circle's surface at (x,y). */
    private void spark(float x, float y, float rim, float len, float width) {
        double a = rnd.nextDouble() * Math.PI * 2;
        float sx = x + (float) Math.cos(a) * rim, sy = y + (float) Math.sin(a) * rim;
        double a2 = a + (rnd.nextFloat() - 0.5f) * 0.9f;
        addBolt(sx, sy, sx + (float) Math.cos(a2) * len, sy + (float) Math.sin(a2) * len,
                0.07f + rnd.nextFloat() * 0.08f, width, rnd.nextFloat() < 0.6f ? CYAN : (rnd.nextBoolean() ? BLUE : VIOLET), false);
    }

    /** A jagged bolt from (x1,y1) to (x2,y2), built by midpoint displacement, sometimes forked. */
    private void addBolt(float x1, float y1, float x2, float y2, float life, float width, int color, boolean fork) {
        Bolt b = new Bolt();
        b.life = b.maxLife = life;
        b.width = width;
        b.color = color;
        float len = (float) Math.hypot(x2 - x1, y2 - y1);
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{x1, y1});
        pts.add(new float[]{x2, y2});
        float disp = len * 0.22f;
        for (int depth = 0; depth < 5 && disp > 1.5f * dp; depth++) {
            List<float[]> next = new ArrayList<>();
            for (int i = 0; i < pts.size() - 1; i++) {
                float[] p = pts.get(i), q = pts.get(i + 1);
                float mx = (p[0] + q[0]) / 2, my = (p[1] + q[1]) / 2;
                float nx = -(q[1] - p[1]), ny = q[0] - p[0];
                float nl = (float) Math.hypot(nx, ny);
                if (nl > 0) {
                    float off = (rnd.nextFloat() - 0.5f) * 2 * disp;
                    mx += nx / nl * off;
                    my += ny / nl * off;
                }
                next.add(p);
                next.add(new float[]{mx, my});
            }
            next.add(pts.get(pts.size() - 1));
            pts = next;
            disp *= 0.55f;
        }
        b.path.moveTo(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 1; i < pts.size(); i++) b.path.lineTo(pts.get(i)[0], pts.get(i)[1]);
        bolts.add(b);
        if (bolts.size() > 220) bolts.remove(0);
        // fork: a thinner branch from somewhere along the bolt
        if (fork && len > 60 * dp && rnd.nextFloat() < 0.55f) {
            float[] p = pts.get(pts.size() / 3 + rnd.nextInt(Math.max(1, pts.size() / 3)));
            double a = Math.atan2(y2 - y1, x2 - x1) + (rnd.nextBoolean() ? 1 : -1) * (0.4f + rnd.nextFloat() * 0.6f);
            float bl = len * (0.2f + rnd.nextFloat() * 0.25f);
            addBolt(p[0], p[1], p[0] + (float) Math.cos(a) * bl, p[1] + (float) Math.sin(a) * bl,
                    life * 0.8f, width * 0.55f, color, false);
        }
    }

    private void glow(Canvas c, float x, float y, float radius, float strength) {
        fill.setShader(new RadialGradient(x, y, radius,
                new int[]{withAlpha(0xFFD8F7FF, (int) (150 * strength)), withAlpha(BLUE, (int) (80 * strength)),
                        withAlpha(VIOLET, (int) (30 * strength)), 0},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, fill);
    }

    private static int withAlpha(int color, int a) {
        return Color.argb(Math.max(0, Math.min(255, a)), Color.red(color), Color.green(color), Color.blue(color));
    }
}
