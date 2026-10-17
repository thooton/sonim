package com.places.xp3;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * Five stars filled to a rating, as Google Maps draws them: 4.5 is four gold
 * stars and half of a fifth, on grey ones. A drawable rather than ★ and ☆,
 * which can only show whole stars.
 */
final class Stars extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path star = new Path();
    private final double rating;
    private final int size;
    private final int gap;
    private final int filled;
    private final int empty;

    Stars(double rating, int size, int gap, int filled, int empty) {
        this.rating = rating;
        this.size = size;
        this.gap = gap;
        this.filled = filled;
        this.empty = empty;
        // Ten points round the middle, the inner ones at 40% of the reach.
        float middle = size / 2f;
        for (int i = 0; i < 10; i++) {
            double angle = Math.PI * (i / 5.0 - 0.5);
            float reach = middle * (i % 2 == 0 ? 1f : 0.4f);
            float x = middle + (float) (Math.cos(angle) * reach);
            float y = middle * 1.06f + (float) (Math.sin(angle) * reach);
            if (i == 0) star.moveTo(x, y);
            else star.lineTo(x, y);
        }
        star.close();
        setBounds(0, 0, getIntrinsicWidth(), getIntrinsicHeight());
    }

    @Override
    public int getIntrinsicWidth() {
        return size * 5 + gap * 4;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        for (int i = 0; i < 5; i++) {
            paint.setColor(empty);
            canvas.drawPath(star, paint);
            double part = Math.max(0, Math.min(1, rating - i));
            if (part > 0) {
                canvas.save();
                canvas.clipRect(0, 0, (float) (size * part), size);
                paint.setColor(filled);
                canvas.drawPath(star, paint);
                canvas.restore();
            }
            canvas.translate(size + gap, 0);
        }
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
