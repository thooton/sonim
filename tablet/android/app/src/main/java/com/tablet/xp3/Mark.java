package com.tablet.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.TextView;

/**
 * Text the highlighter can be drawn across: a marker stroke behind the
 * whole view, its ends cut on the slant a chisel tip leaves, the way the
 * proposal marks the selected file's title and the selected action.
 *
 * Drawn under the text rather than as a background, so the view keeps its
 * size and the text never moves when the mark arrives.
 */
class Mark extends TextView {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean marked;

    Mark(Context context) {
        super(context);
        setIncludeFontPadding(false);
        stroke.setColor(Look.MARK);
        stroke.setPathEffect(new CornerPathEffect(Look.px(context, 2)));
    }

    void mark(boolean value) {
        if (marked == value) return;
        marked = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (marked) {
            float w = getWidth();
            float h = getHeight();
            // The 100-degree slant of the proposal's gradient: the top of each
            // end sits a little right of its foot.
            float slant = h * 0.18f;
            float in = w * 0.02f;
            path.reset();
            path.moveTo(in + slant, 0);
            path.lineTo(w - in, 0);
            path.lineTo(w - in - slant, h);
            path.lineTo(in, h);
            path.close();
            canvas.drawPath(path, stroke);
        }
        super.onDraw(canvas);
    }
}
