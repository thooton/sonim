package com.canvas.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.widget.TextView;

/**
 * Text that can wear the highlighter: a yellow stroke across the lower half
 * of every line it runs to, the way a pen marks a printed title.
 *
 * Drawn under the glyphs rather than as a background span, so it follows the
 * text's own line breaks and stops at the end of each line instead of filling
 * the box, and the text never moves when it arrives.
 */
final class MarkText extends TextView {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean marked;

    MarkText(Context context) {
        super(context);
        setIncludeFontPadding(false);
    }

    void mark(boolean value, int color) {
        stroke.setColor(color);
        if (marked == value) return;
        marked = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (marked && layout != null) {
            canvas.save();
            canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());
            for (int i = 0; i < layout.getLineCount(); i++) {
                float top = layout.getLineTop(i);
                float height = layout.getLineBottom(i) - top;
                float left = layout.getLineLeft(i);
                float right = layout.getLineRight(i);
                if (right <= left) continue;
                canvas.drawRect(left - 1, top + height * 0.5f, right + 1, top + height * 0.94f, stroke);
            }
            canvas.restore();
        }
        super.onDraw(canvas);
    }
}
