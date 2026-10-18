package com.canvas.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A block of a teacher's text whose links the d-pad lands on, one after
 * another, as they scroll into view.
 *
 * A TextView is one focusable thing or none, so each link gets an invisible
 * Item laid over its words. That is what lets the platform's focus search
 * find it and the ScrollView bring it on screen, with nothing taking over
 * Up and Down. Focus shows as the highlighter under the link's own words,
 * drawn here beneath the glyphs the way MarkText draws it under a title.
 */
final class LinkText extends FrameLayout {

    private final TextView text;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<int[]> spans = new ArrayList<>();
    private final List<Item> stops = new ArrayList<>();
    private int marked = -1;

    LinkText(Context context, TextView text, int strokeColor) {
        super(context);
        this.text = text;
        stroke.setColor(strokeColor);
        addView(text, new LayoutParams(-1, -2));
    }

    // Markdown pads each block itself, so a caller evening out a block's
    // padding (PostScreen, inside a card) means the text's, as it did before
    // the text was wrapped. The constructor's own padding comes before `text`.
    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (text == null) super.setPadding(left, top, right, bottom);
        else text.setPadding(left, top, right, bottom);
    }

    @Override
    public int getPaddingTop() {
        return text == null ? super.getPaddingTop() : text.getPaddingTop();
    }

    @Override
    public int getPaddingBottom() {
        return text == null ? super.getPaddingBottom() : text.getPaddingBottom();
    }

    /** A link over characters [start, end) of the text, reached through `stop`. */
    void add(int start, int end, Item stop) {
        final int index = stops.size();
        spans.add(new int[] { start, end });
        stop.painter = has -> {
            if (has) marked = index;
            else if (marked == index) marked = -1;
            invalidate();
        };
        stop.setId(View.generateViewId());
        // Two links on one line sit side by side, where Down by position would
        // skip the second; within a block each names the next instead.
        if (index > 0) {
            Item before = stops.get(index - 1);
            before.setNextFocusDownId(stop.getId());
            stop.setNextFocusUpId(before.getId());
        }
        stops.add(stop);
        addView(stop, new LayoutParams(0, 0));
    }

    /**
     * Coming in from outside the block, Down meets its first link and Up its
     * last, so the links read in order whichever line the d-pad arrives near.
     */
    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (stops.size() > 1 && findFocus() == null && (direction == FOCUS_DOWN || direction == FOCUS_UP)) {
            stops.get(direction == FOCUS_DOWN ? 0 : stops.size() - 1).addFocusables(views, direction, focusableMode);
            return;
        }
        super.addFocusables(views, direction, focusableMode);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        Layout layout = text.getLayout();
        if (layout == null) return;
        int dx = text.getLeft() + text.getTotalPaddingLeft();
        int dy = text.getTop() + text.getTotalPaddingTop();
        for (int i = 0; i < stops.size(); i++) {
            int[] span = spans.get(i);
            int first = layout.getLineForOffset(span[0]);
            int last = lastLine(layout, span);
            float from = Float.MAX_VALUE;
            float to = 0;
            for (int line = first; line <= last; line++) {
                from = Math.min(from, start(layout, span, line, first));
                to = Math.max(to, end(layout, span, line, last));
            }
            stops.get(i).layout(dx + (int) from, dy + layout.getLineTop(first), dx + (int) Math.ceil(to),
                dy + layout.getLineBottom(last));
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        Layout layout = text.getLayout();
        if (marked >= 0 && layout != null) {
            int[] span = spans.get(marked);
            int first = layout.getLineForOffset(span[0]);
            int last = lastLine(layout, span);
            canvas.save();
            canvas.translate(text.getLeft() + text.getTotalPaddingLeft(), text.getTop() + text.getTotalPaddingTop());
            for (int line = first; line <= last; line++) {
                float top = layout.getLineTop(line);
                float height = layout.getLineBottom(line) - top;
                float from = start(layout, span, line, first);
                float to = end(layout, span, line, last);
                if (to <= from) continue;
                canvas.drawRect(from - 1, top + height * 0.5f, to + 1, top + height * 0.94f, stroke);
            }
            canvas.restore();
        }
        super.dispatchDraw(canvas);
    }

    private static int lastLine(Layout layout, int[] span) {
        return layout.getLineForOffset(Math.max(span[0], span[1] - 1));
    }

    private static float start(Layout layout, int[] span, int line, int first) {
        return line == first ? layout.getPrimaryHorizontal(span[0]) : layout.getLineLeft(line);
    }

    /** A link ending where its line wraps reports the next line's start as its end, so that line's right edge is used. */
    private static float end(Layout layout, int[] span, int line, int last) {
        if (line == last && layout.getLineForOffset(span[1]) == line) return layout.getPrimaryHorizontal(span[1]);
        return layout.getLineRight(line);
    }
}
