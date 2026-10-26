package app.buddy.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import static app.buddy.assistant.Ui.*;

/**
 * The bubble's small live picture of the background browser while an agent uses it (see Bubble.updateThumb): the
 * tab's frames from the same screencast as BrowserPanel's. A tap opens the tab full screen (BrowserActivity), a
 * sideways swipe hides it; touches never reach the page.
 */
final class BrowserThumb extends View implements Browser.Frames {
    interface Actions {
        void open();

        void dismiss();
    }

    private final Actions actions;
    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final int slop, fling;
    private VelocityTracker velocity;
    private float downX, downY;
    private boolean dragging;

    BrowserThumb(Context c, Actions actions) {
        super(c);
        this.actions = actions;
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        fling = vc.getScaledMinimumFlingVelocity() * 4; // a deliberate flick, not a tap that slid
        setClipToOutline(true); // the page's corners follow the background's
        setContentDescription(c.getString(R.string.bubble_browser_thumb));
        restyle();
    }

    /** The theme's colours (again, after Settings changed them). */
    void restyle() {
        setBackground(rounded(getContext(), CARD2, 14));
        setForeground(outlined(getContext(), Color.TRANSPARENT, 14, ACCENT, 1)); // the pill's border, over the page
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
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                dragging = false;
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(e);
                if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) dragging = true;
                if (dragging) {
                    setTranslationX(dx);
                    setAlpha(Math.max(0.2f, 1 - Math.abs(dx) / getWidth()));
                }
                return true;
            case MotionEvent.ACTION_UP:
                float vx = 0;
                if (velocity != null) {
                    velocity.addMovement(e);
                    velocity.computeCurrentVelocity(1000);
                    vx = velocity.getXVelocity();
                    velocity.recycle();
                    velocity = null;
                }
                if (dragging) {
                    // far enough, or flicked that way: off it goes
                    if (Math.abs(dx) > getWidth() / 3f || (Math.abs(vx) > fling && Math.signum(vx) == Math.signum(dx))) away(dx);
                    else animate().translationX(0).alpha(1).setDuration(160).start();
                } else if (Math.hypot(dx, dy) < slop) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (velocity != null) velocity.recycle();
                velocity = null;
                animate().translationX(0).alpha(1).setDuration(160).start();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    /** Slides out to the side it was swiped to, then is hidden. */
    private void away(float dx) {
        animate().translationX(Math.signum(dx) * getWidth()).alpha(0).setDuration(180).withEndAction(() -> {
            setTranslationX(0);
            setAlpha(1);
            actions.dismiss();
        }).start();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        actions.open();
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
