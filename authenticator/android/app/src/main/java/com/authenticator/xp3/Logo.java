package com.authenticator.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.xp3.common.ui.Ui;

/**
 * An account's logo as Authy's list draws it: a round badge, here the
 * service's initial on its colour, inside a thin ring that runs down in the
 * same colour as the code's time passes, over a grey track.
 */
final class Logo extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float ring;
    private final float gap;
    private String initial = "";
    private float left = 1f;

    Logo(Context context) {
        super(context);
        ring = Ui.dp(context, 2.5f);
        gap = Ui.dp(context, 2f);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(ring);
        track.setColor(context.getColor(R.color.track));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(ring);
        arc.setStrokeCap(Paint.Cap.ROUND);
        letter.setTypeface(Look.semiBold(context));
        letter.setTextAlign(Paint.Align.CENTER);
    }

    void show(Account account) {
        int color = Look.brand(account);
        arc.setColor(color);
        badge.setColor(color);
        letter.setColor(Look.light(color) ? getContext().getColor(R.color.ink) : 0xFFFFFFFF);
        initial = Look.initial(account);
        invalidate();
    }

    /** How much of the code's time is left, 1 to 0. */
    void left(float fraction) {
        if (Math.abs(fraction - left) < 0.002f) return;
        left = fraction;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float inset = ring / 2f;
        bounds.set(cx - size / 2f + inset, cy - size / 2f + inset, cx + size / 2f - inset, cy + size / 2f - inset);
        canvas.drawOval(bounds, track);
        // From twelve o'clock, what is left of the time, shrinking clockwise.
        canvas.drawArc(bounds, -90f + 360f * (1f - left), 360f * left, false, arc);
        float radius = size / 2f - ring - gap;
        canvas.drawCircle(cx, cy, radius, badge);
        letter.setTextSize(radius * 1.05f);
        Paint.FontMetrics metrics = letter.getFontMetrics();
        canvas.drawText(initial, cx, cy - (metrics.ascent + metrics.descent) / 2f, letter);
    }
}
