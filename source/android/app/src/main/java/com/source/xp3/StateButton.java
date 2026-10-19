package com.source.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

/**
 * The round button at the end of a download's row: where it stands.
 * Downloading is a ring that fills, with the percentage in it (or an arc
 * going round while the size isn't known); queued an outline; saved the
 * success container with a tick; failed the error container with a retry
 * arrow. Not yet asked for (a result's rows hide it then) is a tonal download
 * arrow, filled with primary on the row under the d-pad.
 */
final class StateButton extends View {

    private int state = Download.IDLE;
    private float progress = -1;
    private boolean picked;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    StateButton(Context context) {
        super(context);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Tonal.BOLD);
    }

    void set(int state, float progress, boolean picked) {
        if (state == this.state && progress == this.progress && picked == this.picked) return;
        this.state = state;
        this.progress = progress;
        this.picked = picked;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f;
        float stroke = Math.max(2f, r * 0.17f);
        Context c = getContext();
        paint.setStyle(Paint.Style.FILL);
        switch (state) {
            case Download.RUNNING: {
                oval.set(cx - r + stroke / 2, cy - r + stroke / 2, cx + r - stroke / 2, cy + r - stroke / 2);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(c.getColor(R.color.track));
                canvas.drawOval(oval, paint);
                paint.setColor(c.getColor(R.color.accent));
                if (progress >= 0) {
                    canvas.drawArc(oval, -90, Math.max(4, 360 * progress), false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(c.getColor(R.color.text));
                    paint.setTextSize(r * 0.62f);
                    Paint.FontMetrics m = paint.getFontMetrics();
                    canvas.drawText(String.valueOf(Math.round(progress * 100)), cx, cy - (m.ascent + m.descent) / 2, paint);
                } else {
                    float turn = SystemClock.uptimeMillis() % 1200 / 1200f * 360;
                    canvas.drawArc(oval, turn - 90, 100, false, paint);
                    postInvalidateOnAnimation();
                }
                return;
            }
            case Download.QUEUED:
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.5f, r * 0.09f));
                paint.setColor(c.getColor(R.color.outline));
                canvas.drawCircle(cx, cy, r - stroke / 2, paint);
                icon(canvas, R.drawable.ic_schedule, R.color.text_muted, cx, cy, r);
                return;
            case Download.SAVED:
                paint.setColor(c.getColor(R.color.success_container));
                canvas.drawCircle(cx, cy, r, paint);
                icon(canvas, R.drawable.ic_check, R.color.on_success_container, cx, cy, r);
                return;
            case Download.FAILED:
                paint.setColor(c.getColor(R.color.error_container));
                canvas.drawCircle(cx, cy, r, paint);
                icon(canvas, R.drawable.ic_refresh, R.color.on_error_container, cx, cy, r);
                return;
            default:
                paint.setColor(c.getColor(picked ? R.color.accent : R.color.container_high));
                canvas.drawCircle(cx, cy, r, paint);
                icon(canvas, R.drawable.ic_download, picked ? R.color.on_accent : R.color.accent, cx, cy, r);
        }
    }

    private void icon(Canvas canvas, int id, int color, float cx, float cy, float r) {
        Drawable icon = getContext().getDrawable(id).mutate();
        icon.setTint(getContext().getColor(color));
        int half = Math.round(r * 0.55f);
        icon.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
        icon.draw(canvas);
    }
}
