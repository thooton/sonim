package com.places.xp3;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Children side by side, wrapping to a new line when the next would not
 * fit: a place's chips (Call, Website; takeout, credit cards).
 */
final class Flow extends ViewGroup {

    private final int across;
    private final int down;

    Flow(Context context, int across, int down) {
        super(context);
        this.across = across;
        this.down = down;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int room = width - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = 0;
        int line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + child.getMeasuredWidth() > room) {
                x = 0;
                y += line + down;
                line = 0;
            }
            x += child.getMeasuredWidth() + across;
            line = Math.max(line, child.getMeasuredHeight());
        }
        setMeasuredDimension(width, getPaddingTop() + y + line + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int room = right - left - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = 0;
        int line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            if (x > 0 && x + child.getMeasuredWidth() > room) {
                x = 0;
                y += line + down;
                line = 0;
            }
            int l = getPaddingLeft() + x;
            int t = getPaddingTop() + y;
            child.layout(l, t, l + child.getMeasuredWidth(), t + child.getMeasuredHeight());
            x += child.getMeasuredWidth() + across;
            line = Math.max(line, child.getMeasuredHeight());
        }
    }
}
