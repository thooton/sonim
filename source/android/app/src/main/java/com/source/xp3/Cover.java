package com.source.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.source.xp3.data.Monogram;
import com.source.xp3.net.Music;

/**
 * A drawn cover, since neither Soulseek nor the book source sends artwork:
 * the name's initials on a gradient between two of the cover tones (as the
 * proposal's generated covers run from one container tone to another), in
 * a shape that says what
 * it is. A song is a rounded square; an album the same square with a second
 * record behind it; a book a 2:3 cover with a darker spine. A failed
 * download's cover loses its colour, and a detail page's cover can carry a
 * saved or failed badge.
 */
final class Cover extends View {

    private String kind = Music.SONG;
    private String name = "";
    private boolean muted;
    private int badge = Download.IDLE;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    Cover(Context context) {
        super(context);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Tonal.BOLD);
    }

    void set(String kind, String name, boolean muted, int badge) {
        if (kind.equals(this.kind) && name.equals(this.name) && muted == this.muted && badge == this.badge) return;
        this.kind = kind;
        this.name = name;
        this.muted = muted;
        this.badge = badge;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        int tone = Monogram.tone(name, Tonal.COVERS.length);
        int fill = getContext().getColor(muted ? R.color.container_highest : Tonal.COVERS[tone]);
        int fillTo = getContext().getColor(muted ? R.color.container_high : Tonal.COVERS[(tone + 2) % Tonal.COVERS.length]);
        int ink = getContext().getColor(muted ? R.color.text_muted : Tonal.INKS[tone]);
        float textX;
        float size;
        if (Download.BOOK.equals(kind)) {
            float bh = h;
            float bw = h * 2 / 3;
            if (bw > w) {
                bw = w;
                bh = w * 3 / 2;
            }
            rect.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
            float radius = bw * 0.16f;
            shade(fill, fillTo);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);
            // The spine: the cover's ink, faint, down its left edge.
            float spine = bw * 0.13f;
            canvas.save();
            canvas.clipRect(rect.left, rect.top, rect.left + spine, rect.bottom);
            paint.setColor(0x4D000000);
            canvas.drawRoundRect(rect, radius, radius, paint);
            canvas.restore();
            textX = rect.centerX() + spine / 2;
            size = bw * 0.38f;
        } else {
            float side = Math.min(w, h);
            if (Music.ALBUM.equals(kind)) {
                float front = side * 0.9f;
                float offset = side - front;
                float left = (w - side) / 2;
                float top = (h - side) / 2;
                float radius = front * 0.3f;
                rect.set(left + offset, top, left + offset + front, top + front);
                // The record behind: the outline colour at 45%.
                paint.setColor(getContext().getColor(R.color.outline) & 0x00FFFFFF | 0x73000000);
                canvas.drawRoundRect(rect, radius, radius, paint);
                rect.set(left, top + offset, left + front, top + offset + front);
                shade(fill, fillTo);
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.setShader(null);
                size = front * 0.32f;
            } else {
                rect.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
                float radius = side * 0.28f;
                shade(fill, fillTo);
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.setShader(null);
                size = side * 0.32f;
            }
            textX = rect.centerX();
        }
        String initials = Monogram.initials(name);
        if (initials.length() > 0) {
            paint.setColor(ink);
            paint.setTextSize(size);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2;
            canvas.drawText(initials, textX, baseline, paint);
        }
        if (badge == Download.SAVED || badge == Download.FAILED) drawBadge(canvas);
    }

    /** A 135 degree gradient across `rect`, as CSS draws linear-gradient(135deg, from, to). */
    private void shade(int from, int to) {
        paint.setColor(0xFF000000);
        paint.setShader(new android.graphics.LinearGradient(rect.left, rect.top, rect.right, rect.bottom, from, to, android.graphics.Shader.TileMode.CLAMP));
    }

    /** A round badge on the cover's lower right corner, ringed in the page's colour. */
    private void drawBadge(Canvas canvas) {
        boolean saved = badge == Download.SAVED;
        float r = Math.min(rect.width(), rect.height()) * 0.17f;
        float cx = rect.right - r * 0.5f;
        float cy = rect.bottom - r * 0.5f;
        paint.setColor(getContext().getColor(R.color.page));
        canvas.drawCircle(cx, cy, r + r * 0.22f, paint);
        paint.setColor(getContext().getColor(saved ? R.color.success : R.color.error));
        canvas.drawCircle(cx, cy, r, paint);
        Drawable icon = getContext().getDrawable(saved ? R.drawable.ic_check : R.drawable.ic_close).mutate();
        icon.setTint(getContext().getColor(R.color.page));
        int half = Math.round(r * 0.62f);
        icon.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
        icon.draw(canvas);
    }
}
