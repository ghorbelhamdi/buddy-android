package app.buddy.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import static app.buddy.assistant.Ui.*;

/**
 * The bubble's small live picture of the background browser while an agent uses it (see Bubble.updateThumb): the
 * tab's frames from the same screencast as BrowserPanel's. A tap opens the tab full screen (BrowserActivity), a
 * drag moves it anywhere on the screen (dragged off a side, it hides), and a drag on its bottom corner grip or a pinch
 * resizes it (Bubble keeps place and size);
 * touches never reach the page.
 */
final class BrowserThumb extends View implements Browser.Frames {
    interface Actions {
        void open();

        void dismiss();

        /** A tap on the tiny picture in the pill: back to its full size. */
        void restore();

        /** The finger moved this far (px) from where it took the picture: it follows. */
        void drag(float dx, float dy);

        /** Let go there. Dragged more than half off a side, it is hidden (not when cancelled). */
        void drop(float dx, float dy, boolean cancelled);

        /** The user drags the grip or pinches: the picture should be this wide (px); Bubble clamps and keeps it. */
        void resize(int width);
    }

    private final Actions actions;
    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final int slop;
    private float downX, downY;
    private boolean dragging;
    /** Resizing by the corner grip (from DOWN there) or by a pinch; the gesture is then neither a tap nor a swipe. */
    private boolean resizing, pinched;
    private int startW;
    /** The grip sits in the bottom corner away from the window's anchored edge: left when the bubble is on the right. */
    private boolean gripLeft;
    private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.view.ScaleGestureDetector pinch;

    /** Tiny, inside the bubble's pill: a tap brings it back; no moving, no resizing, no grip. */
    private boolean mini;

    void setMini(boolean on) {
        if (mini == on) return;
        mini = on;
        setBackground(rounded(getContext(), CARD2, on ? 6 : 14));
        setForeground(outlined(getContext(), Color.TRANSPARENT, on ? 6 : 14, ACCENT, 1));
        invalidate();
    }

    void setGripLeft(boolean left) {
        if (gripLeft == left) return;
        gripLeft = left;
        invalidate();
    }

    private boolean onGrip(MotionEvent e) {
        float g = dp(getContext(), 36);
        return e.getY() > getHeight() - g && (gripLeft ? e.getX() < g : e.getX() > getWidth() - g);
    }

    BrowserThumb(Context c, Actions actions) {
        super(c);
        this.actions = actions;
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        grip.setStyle(Paint.Style.STROKE);
        grip.setStrokeCap(Paint.Cap.ROUND);
        grip.setStrokeWidth(dp(c, 2));
        pinch = new android.view.ScaleGestureDetector(c, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(android.view.ScaleGestureDetector d) {
                pinched = true;
                actions.resize(Math.round(getWidth() * d.getScaleFactor()));
                return true;
            }
        });
        setClipToOutline(true); // the page's corners follow the background's
        setContentDescription(c.getString(R.string.bubble_browser_thumb));
        restyle();
    }

    /** The theme's colours (again, after Settings changed them). */
    void restyle() {
        setBackground(rounded(getContext(), CARD2, mini ? 6 : 14));
        setForeground(outlined(getContext(), Color.TRANSPARENT, mini ? 6 : 14, ACCENT, 1)); // the pill's border, over the page
    }

    /** Another tab: its last picture goes. */
    void clear() {
        bitmap = null;
        invalidate();
    }

    @Override
    public void onFrame(Bitmap b, float w, float h) {
        post(() -> {
            bitmap = b;
            invalidate();
        });
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null) return; // the background until the first frame
        dst.set(0, 0, getWidth(), getHeight()); // sized to the page (see Bubble.updateThumb)
        c.drawBitmap(bitmap, null, dst, paint);
        if (mini) return;
        // the resize grip: two short diagonal strokes in the free bottom corner, readable on light and dark pages
        float u = dp(getContext(), 1), h = getHeight(), x0 = gripLeft ? 0 : getWidth(), sx = gripLeft ? 1 : -1;
        for (int pass = 0; pass < 2; pass++) {
            grip.setColor(pass == 0 ? 0x99000000 : 0xE6FFFFFF);
            grip.setStrokeWidth(u * (pass == 0 ? 3.5f : 1.75f));
            c.drawLine(x0 + sx * 6 * u, h - 14 * u, x0 + sx * 14 * u, h - 6 * u, grip);
            c.drawLine(x0 + sx * 6 * u, h - 20 * u, x0 + sx * 20 * u, h - 6 * u, grip);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mini) { // just a button
            if (e.getActionMasked() == MotionEvent.ACTION_UP) actions.restore();
            return true;
        }
        pinch.onTouchEvent(e);
        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
        int action = e.getActionMasked();
        boolean end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (action != MotionEvent.ACTION_DOWN && (resizing || pinched)) {
            if (resizing && !pinched && action == MotionEvent.ACTION_MOVE) {
                // the corner follows the finger: whichever of the two directions moved more decides
                float byX = (gripLeft ? -dx : dx), byY = dy * getWidth() / Math.max(1, getHeight());
                actions.resize(Math.round(startW + (Math.abs(byX) > Math.abs(byY) ? byX : byY)));
            }
            if (dragging && pinched) { // a second finger came down mid-drag: the move ends where it is
                dragging = false;
                actions.drop(dx, dy, true);
            }
            if (end) resizing = pinched = false;
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                dragging = false;
                pinched = false;
                resizing = onGrip(e);
                startW = getWidth();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                if (dragging) actions.drag(dx, dy);
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) actions.drop(dx, dy, false);
                else if (Math.hypot(dx, dy) < slop) performClick();
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) actions.drop(dx, dy, true);
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (mini) actions.restore();
        else actions.open();
        return true;
    }

    // screen readers: double-tap opens it, and the swipe is the Dismiss action

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClickable(true);
        info.setDismissable(true);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS,
                getContext().getString(R.string.bubble_browser_thumb_hide)));
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
            actions.dismiss();
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
