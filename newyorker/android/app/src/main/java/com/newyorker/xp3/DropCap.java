package com.newyorker.xp3;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;

/**
 * The large initial that opens each section of a magazine piece, three lines
 * deep, its top level with the first line's capitals and its foot on the
 * third line's baseline, the text wrapping beside it.
 */
final class DropCap implements LeadingMarginSpan.LeadingMarginSpan2 {

    static final int LINES = 3;

    private final String letter;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int width;
    private final int gap;

    /**
     * `lineHeight` is the body's, in pixels, and `bodyCapHeight` how tall its
     * capitals stand, so the initial can be sized to span exactly.
     */
    DropCap(String letter, Typeface face, int color, int lineHeight, float bodyCapHeight, int gap) {
        this.letter = letter;
        this.gap = gap;
        paint.setTypeface(face);
        paint.setColor(color);
        // Sized so its capital spans from the first line's cap height to the third line's baseline.
        float span = (LINES - 1) * lineHeight + bodyCapHeight;
        paint.setTextSize(100);
        Rect bounds = new Rect();
        paint.getTextBounds("H", 0, 1, bounds);
        paint.setTextSize(100 * span / Math.max(1, bounds.height()));
        width = (int) Math.ceil(paint.measureText(letter));
    }

    @Override
    public int getLeadingMarginLineCount() {
        return LINES;
    }

    @Override
    public int getLeadingMargin(boolean first) {
        return first ? width + gap : 0;
    }

    @Override
    public void drawLeadingMargin(Canvas canvas, Paint p, int x, int dir, int top, int baseline, int bottom,
                                  CharSequence text, int start, int end, boolean first, Layout layout) {
        if (!(text instanceof Spanned) || ((Spanned) text).getSpanStart(this) != start) return;
        int line = layout.getLineForOffset(start);
        int last = Math.min(layout.getLineCount() - 1, line + LINES - 1);
        canvas.drawText(letter, x, layout.getLineBaseline(last), paint);
    }
}
