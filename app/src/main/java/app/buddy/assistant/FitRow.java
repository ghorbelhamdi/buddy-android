package app.buddy.assistant;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import java.util.HashMap;
import java.util.Map;

/**
 * A row of buttons that stacks them, each the full width, when they don't fit side by side at their natural width.
 * On a narrow screen (or with a large font, or a longer translation) the last button was squeezed instead, and an
 * approval card's "Approve" came out one letter per line (issue #69).
 */
final class FitRow extends LinearLayout {
    /** Each child's own layout, as it was added: what side by side goes back to. */
    private final Map<View, LayoutParams> given = new HashMap<>();
    private final int gap;

    FitRow(Context c, int gapPx) {
        super(c);
        gap = gapPx;
        setOrientation(HORIZONTAL);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        boolean stack = false;
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
            // side by side, a weighted button gets its share of what the fixed ones leave, not its natural width: each
            // has to fit its share (a long "Approve" next to two short ones was cut off though the sum fitted)
            int room = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight(), fixed = 0;
            float weights = 0;
            int any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            java.util.List<View> shown = new java.util.ArrayList<>();
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                shown.add(v);
                LayoutParams own = own(v);
                v.measure(any, any); // its natural width, whatever the row gives it now
                fixed += own.getMarginStart() + own.getMarginEnd();
                if (own.weight > 0) weights += own.weight;
                else fixed += v.getMeasuredWidth();
            }
            int left = room - fixed;
            stack = left < 0;
            for (View v : shown) {
                LayoutParams own = own(v);
                if (stack) break;
                if (own.weight > 0) stack = v.getMeasuredWidth() > left * own.weight / weights;
            }
        }
        // left and right as well as start and end: the row reads the resolved ones, set when the child was added
        boolean first = true, rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            LayoutParams own = own(v), lp = (LayoutParams) v.getLayoutParams();
            if (stack) {
                lp.width = LayoutParams.MATCH_PARENT;
                lp.weight = 0;
                lp.setMarginStart(0);
                lp.setMarginEnd(0);
                lp.leftMargin = lp.rightMargin = 0;
                lp.topMargin = first || v.getVisibility() == GONE ? 0 : gap;
            } else {
                lp.width = own.width;
                lp.weight = own.weight;
                lp.setMarginStart(own.getMarginStart());
                lp.setMarginEnd(own.getMarginEnd());
                lp.leftMargin = rtl ? own.getMarginEnd() : own.getMarginStart();
                lp.rightMargin = rtl ? own.getMarginStart() : own.getMarginEnd();
                lp.topMargin = own.topMargin;
            }
            if (v.getVisibility() != GONE) first = false;
        }
        setOrientation(stack ? VERTICAL : HORIZONTAL);
        super.onMeasure(widthSpec, heightSpec);
    }

    private LayoutParams own(View v) {
        LayoutParams o = given.get(v);
        if (o == null) {
            o = new LayoutParams((LayoutParams) v.getLayoutParams());
            given.put(v, o);
        }
        return o;
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        given.remove(child);
    }
}
