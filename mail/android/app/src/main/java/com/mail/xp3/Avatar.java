package com.mail.xp3;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;

/**
 * The reader's mark for who sent a message: their initials in a pale circle
 * beside the sender's name and the date line under it, as the phone's own
 * mail apps do, so where one message of a conversation starts can be seen
 * while scrolling. Set on the name's paragraph; the date's paragraph takes
 * a plain margin of the same width ({@link #margin}) so the two stay aligned.
 */
final class Avatar implements LeadingMarginSpan {

    private final String initials;
    private final int size;
    private final int gap;
    private final int fill;
    private final int ink;

    Avatar(String sender, int size, int gap, int fill, int ink) {
        this.initials = initials(sender);
        this.size = size;
        this.gap = gap;
        this.fill = fill;
        this.ink = ink;
    }

    int margin() {
        return size + gap;
    }

    @Override
    public int getLeadingMargin(boolean first) {
        return size + gap;
    }

    @Override
    public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int dir, int top, int baseline, int bottom,
                                  CharSequence text, int start, int end, boolean first, Layout layout) {
        if (!(text instanceof Spanned) || ((Spanned) text).getSpanStart(this) != start) return;
        Paint.Style style = paint.getStyle();
        int color = paint.getColor();
        Typeface face = paint.getTypeface();
        float textSize = paint.getTextSize();
        boolean aa = paint.isAntiAlias();

        float radius = size / 2f;
        float cx = dir > 0 ? x + radius : x - radius;
        float cy = top + radius + (bottom - top - size) / 4f;
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(ink);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(size * 0.4f);
        Paint.FontMetrics m = paint.getFontMetrics();
        float w = paint.measureText(initials);
        canvas.drawText(initials, cx - w / 2f, cy - (m.ascent + m.descent) / 2f, paint);

        paint.setStyle(style);
        paint.setColor(color);
        paint.setTypeface(face);
        paint.setTextSize(textSize);
        paint.setAntiAlias(aa);
    }

    /** "Desert Financial" → "DF", "jane@example.com" → "J". */
    static String initials(String name) {
        String clean = name == null ? "" : name.replaceAll("[\"'(),.<>]", " ").trim();
        int at = clean.indexOf('@');
        if (at > 0 && clean.indexOf(' ') < 0) clean = clean.substring(0, at);
        StringBuilder out = new StringBuilder();
        for (String word : clean.split("\\s+")) {
            if (word.length() == 0) continue;
            int point = word.codePointAt(0);
            if (!Character.isLetterOrDigit(point)) continue;
            out.appendCodePoint(Character.toUpperCase(point));
            if (out.length() >= 2) break;
        }
        return out.length() == 0 ? "?" : out.toString();
    }
}
