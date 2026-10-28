package com.newyorker.xp3;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.style.ReplacementSpan;

/**
 * The ♦ that ends a piece, drawn as a small diamond in the text's own ink.
 * The Caslon has no such glyph, and Android's fallback for it is the red
 * colour emoji, on screen and in the PDF alike.
 */
final class EndMark extends ReplacementSpan {

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
        if (metrics != null) paint.getFontMetricsInt(metrics);
        return Math.round(paint.getTextSize() * 0.62f);
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        float size = paint.getTextSize();
        float half = size * 0.28f;
        float middle = x + size * 0.31f;
        // Centred on the lower-case letters' middle, as the magazine sets it.
        float centre = y - size * 0.3f;
        Path diamond = new Path();
        diamond.moveTo(middle, centre - half);
        diamond.lineTo(middle + half * 0.8f, centre);
        diamond.lineTo(middle, centre + half);
        diamond.lineTo(middle - half * 0.8f, centre);
        diamond.close();
        Paint.Style style = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(diamond, paint);
        paint.setStyle(style);
    }
}
