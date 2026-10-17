package com.source.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

/**
 * Progress in Material 3's wavy style, drawn as a sine path: wavy means
 * moving. While a download runs the wave's phase drifts; when it has failed
 * the bar goes straight and still, in the error colour, so the shape says at
 * a glance whether anything is happening. Before the size is known (while
 * the command center is still fetching from Soulseek) a stretch of wave
 * travels along the track.
 */
final class Wavy extends View {

    private float progress = -1;
    private boolean moving;
    private boolean failed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    Wavy(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** `progress` 0 to 1, or -1 while unknown. */
    void set(float progress, boolean moving, boolean failed) {
        if (progress == this.progress && moving == this.moving && failed == this.failed) return;
        this.progress = progress;
        this.moving = moving;
        this.failed = failed;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float stroke = 3 * density;
        float y = getHeight() / 2f;
        float left = stroke;
        float right = getWidth() - stroke;
        int active = getContext().getColor(failed ? R.color.error : R.color.accent);
        int track = failed ? active & 0x00FFFFFF | 0x40000000 : getContext().getColor(R.color.track);
        long now = SystemClock.uptimeMillis();
        float phase = moving ? now % 1000 / 1000f * (float) (2 * Math.PI) : 0;
        paint.setStrokeWidth(stroke);
        float from = left;
        float to;
        if (progress >= 0 || !moving) {
            to = left + (right - left) * Math.max(0, progress);
            float gap = to > left ? stroke * 1.5f : 0;
            paint.setColor(track);
            if (to + gap < right) canvas.drawLine(to + gap, y, right, y, paint);
            // The track's end, a dot of the active colour: where the bar is going.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active);
            canvas.drawCircle(right, y, stroke / 2, paint);
            paint.setStyle(Paint.Style.STROKE);
        } else {
            paint.setColor(track);
            canvas.drawLine(left, y, right, y, paint);
            float span = (right - left) * 0.35f;
            float t = now % 1600 / 1600f;
            to = left - span + (right - left + span) * t + span;
            from = Math.max(left, to - span);
            to = Math.min(right, to);
        }
        if (to > from) {
            paint.setColor(active);
            if (moving && !failed) {
                float amplitude = 1.8f * density;
                float wavelength = 10 * density;
                path.reset();
                for (float x = from; x <= to; x += 1) {
                    float wy = y + amplitude * (float) Math.sin(2 * Math.PI * (x - left) / wavelength - phase);
                    if (x == from) path.moveTo(x, wy);
                    else path.lineTo(x, wy);
                }
                canvas.drawPath(path, paint);
            } else {
                canvas.drawLine(from, y, to, y, paint);
            }
        }
        if (moving && !failed) postInvalidateOnAnimation();
    }
}
