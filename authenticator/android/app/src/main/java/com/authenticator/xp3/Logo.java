package com.authenticator.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import com.xp3.common.ui.Ui;

/**
 * An account's logo as Authy's list draws it: a round badge, the service's
 * logo (or, without one, its initial on its colour), inside a thin ring that
 * runs down in the service's colour as the code's time passes, over a grey
 * track.
 */
final class Logo extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint picture = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF inside = new RectF();
    private final Matrix fit = new Matrix();
    private Bitmap image;
    /** Whether the logo fills its square, so is cut round, or sits on white. */
    private boolean bleeds;
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
        plate.setColor(0xFFFFFFFF);
    }

    /** `logo` is the service's, or null for the monogram; `tint` the logo's colour, if known. */
    void show(Account account, Bitmap logo, Integer tint) {
        Integer named = Look.named(account);
        int color = named != null ? named : tint != null ? tint : Look.brand(account);
        if (logo != image) {
            image = logo;
            picture.setShader(logo == null ? null : new BitmapShader(logo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            bleeds = logo != null && opaque(logo);
        }
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
        if (image != null) {
            canvas.drawCircle(cx, cy, radius, bleeds ? badge : plate);
            // A logo that fills its square is cut round; one with its own
            // shape sits in the square inside the circle, on white.
            float half = bleeds ? radius : radius * 0.72f;
            inside.set(0, 0, image.getWidth(), image.getHeight());
            bounds.set(cx - half, cy - half, cx + half, cy + half);
            fit.setRectToRect(inside, bounds, Matrix.ScaleToFit.CENTER);
            picture.getShader().setLocalMatrix(fit);
            if (bleeds) canvas.drawCircle(cx, cy, radius, picture);
            else canvas.drawRect(bounds, picture);
            return;
        }
        canvas.drawCircle(cx, cy, radius, badge);
        letter.setTextSize(radius * 1.05f);
        Paint.FontMetrics metrics = letter.getFontMetrics();
        canvas.drawText(initial, cx, cy - (metrics.ascent + metrics.descent) / 2f, letter);
    }

    /** Whether the logo's corners are painted, as a square icon's are. */
    private static boolean opaque(Bitmap logo) {
        int w = logo.getWidth() - 1;
        int h = logo.getHeight() - 1;
        int[] corners = {logo.getPixel(0, 0), logo.getPixel(w, 0), logo.getPixel(0, h), logo.getPixel(w, h)};
        for (int pixel : corners) if ((pixel >>> 24) < 250) return false;
        return true;
    }
}
