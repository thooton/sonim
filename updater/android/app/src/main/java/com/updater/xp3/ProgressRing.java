package com.updater.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The App Store's download ring: a pale track, the part downloaded in blue
 * from twelve o'clock, and a small blue square in the middle. While there is
 * no fraction to show (waiting its turn, installing, checking) a quarter of
 * the ring spins instead.
 */
public final class ProgressRing extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final RectF square = new RectF();
    /** 0 to 100, or -1 to spin. */
    private int percent = -1;
    private boolean showStop = true;
    private float angle;

    public ProgressRing(Context context) {
        this(context, null);
    }

    public ProgressRing(Context context, AttributeSet attrs) {
        super(context, attrs);
        float stroke = getResources().getDisplayMetrics().density * 2f;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(context.getColor(R.color.ring_track));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(context.getColor(R.color.blue));
        stop.setColor(context.getColor(R.color.blue));
    }

    /** How far along, 0 to 100, or -1 when there is no telling. */
    public void setProgress(int percent) {
        if (this.percent == percent) return;
        this.percent = percent;
        invalidate();
    }

    /** Whether to draw the stop square; a plain spinner has none. */
    public void setShowStop(boolean showStop) {
        this.showStop = showStop;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float inset = track.getStrokeWidth() / 2f + 0.5f;
        float left = (getWidth() - size) / 2f;
        float top = (getHeight() - size) / 2f;
        oval.set(left + inset, top + inset, left + size - inset, top + size - inset);
        canvas.drawOval(oval, track);
        if (percent >= 0) {
            canvas.drawArc(oval, -90, 360f * percent / 100f, false, arc);
        } else {
            canvas.drawArc(oval, angle, 90, false, arc);
            angle = (angle + 7) % 360;
            postInvalidateOnAnimation();
        }
        if (showStop) {
            float half = size * 0.15f;
            float cx = left + size / 2f;
            float cy = top + size / 2f;
            square.set(cx - half, cy - half, cx + half, cy + half);
            float corner = half / 3f;
            canvas.drawRoundRect(square, corner, corner, stop);
        }
    }
}
