package com.canvas.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import com.canvas.xp3.data.Dates;

import java.util.ArrayList;
import java.util.List;

/**
 * The home screen's spine: seven days, a coloured dot for each piece of work
 * due on one, and the day in view.
 *
 * The day in view is ink. When the d-pad is in the strip it is the
 * highlighter instead, and Left and Right walk it through the days. Only the
 * day changes; there is no count, badge or warning in the strip, only dots
 * in the colours of the classes the work is for.
 */
final class WeekStrip extends View {

    private final Look look;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private long weekStart;
    private int selected = -1;
    private boolean focused;
    private boolean skeleton;
    private final List<List<Integer>> dots = new ArrayList<>();
    private int today = -1;

    WeekStrip(Context context, Look look) {
        super(context);
        this.look = look;
        for (int i = 0; i < 7; i++) dots.add(new ArrayList<Integer>());
    }

    void week(long start, int selectedColumn, int todayColumn) {
        weekStart = start;
        selected = selectedColumn;
        today = todayColumn;
        invalidate();
    }

    void dots(int column, List<Integer> colors) {
        dots.set(column, colors);
        invalidate();
    }

    void select(int column) {
        selected = column;
        invalidate();
    }

    void focus(boolean value) {
        focused = value;
        invalidate();
    }

    void skeleton(boolean value) {
        skeleton = value;
        invalidate();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value * look.scale,
            getResources().getDisplayMetrics());
    }

    @Override
    protected void onMeasure(int width, int height) {
        int tall = Math.round(dp(3) + sp(8) + dp(1) + sp(12.5f) + dp(2) + dp(4) + dp(4) + dp(7));
        setMeasuredDimension(MeasureSpec.getSize(width), tall);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float side = dp(8);
        float gap = dp(2);
        float width = (getWidth() - side * 2 - gap * 6) / 7f;
        float bottom = getHeight() - dp(7);
        for (int i = 0; i < 7; i++) {
            float left = side + i * (width + gap);
            cell.set(left, 0, left + width, bottom);
            long day = Dates.addDays(weekStart, i);
            boolean isSelected = i == selected;
            int letterColor = look.faint;
            int numberColor = look.ink;
            if (isSelected && focused) {
                paint.setColor(look.mark);
                canvas.drawRoundRect(cell, dp(9), dp(9), paint);
                letterColor = 0x99101828;
                numberColor = Look.ON_MARK;
            } else if (isSelected) {
                paint.setColor(look.ink);
                canvas.drawRoundRect(cell, dp(9), dp(9), paint);
                letterColor = (look.bg & 0x00FFFFFF) | 0xA6000000;
                numberColor = look.bg;
            }
            float center = left + width / 2f;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(look.semibold);
            paint.setTextSize(sp(8));
            paint.setColor(letterColor);
            float y = dp(3) + sp(8) * 0.8f;
            canvas.drawText(Dates.initial(day), center, y, paint);
            y += dp(1) + sp(12.5f) * 0.95f;
            if (skeleton) {
                paint.setColor(look.paper);
                float half = dp(7);
                canvas.drawRoundRect(new RectF(center - half, y - sp(10), center + half, y), dp(3), dp(3), paint);
            } else {
                paint.setTypeface(look.bold);
                paint.setTextSize(sp(12.5f));
                paint.setFontFeatureSettings("tnum");
                paint.setColor(numberColor);
                canvas.drawText(Dates.dayOfMonth(day), center, y, paint);
                paint.setFontFeatureSettings(null);
            }
            List<Integer> colors = dots.get(i);
            int count = Math.min(colors.size(), 4);
            float size = dp(4);
            float total = count * size + Math.max(0, count - 1) * dp(2);
            float x = center - total / 2f + size / 2f;
            float dotY = y + dp(4) + size / 2f;
            for (int d = 0; d < count; d++) {
                paint.setColor(colors.get(d));
                canvas.drawCircle(x, dotY, size / 2f, paint);
                x += size + dp(2);
            }
        }
    }
}
