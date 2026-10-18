package com.tablet.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A running job's progress: a ring that fills clockwise from the top as
 * the agent's percentage rises, with the percentage in the middle. It takes
 * the place of the state glyph the finished jobs have, at the same size.
 */
final class Ring extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private int percent;

    Ring(Context c) {
        super(c);
        float stroke = Look.px(c, 2.2f);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(Look.RULE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(Look.INK);
        label.setTypeface(Look.uiBold);
        label.setColor(Look.INK);
        label.setTextAlign(Paint.Align.CENTER);
    }

    void set(int value) {
        percent = Math.max(0, Math.min(100, value));
        setContentDescription(percent + "%");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float inset = arc.getStrokeWidth() / 2 + 0.5f;
        float side = Math.min(w, h);
        box.set((w - side) / 2 + inset, (h - side) / 2 + inset, (w + side) / 2 - inset, (h + side) / 2 - inset);
        canvas.drawOval(box, track);
        if (percent >= 100) canvas.drawOval(box, arc);
        else if (percent > 0) canvas.drawArc(box, -90, percent * 3.6f, false, arc);
        // "100" is the widest it gets; the % sign is the ring itself.
        String text = String.valueOf(percent);
        label.setTextSize(side * (percent >= 100 ? 0.3f : 0.36f));
        Paint.FontMetrics m = label.getFontMetrics();
        canvas.drawText(text, w / 2, h / 2 - (m.ascent + m.descent) / 2, label);
    }
}
