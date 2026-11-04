package com.musica.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Apple Music's look, as the mockup (mockups/index.html, direction B) has
 * it: Inter standing in for SF Pro, the system red, and its greys. Sizes
 * are the mockup's pixels, which are the XP3's dp.
 */
final class Look {

    static final int RED = 0xFFFA2D48;
    static final int GREY = 0xFF8A8A8E;
    static final int HAIRLINE = 0xFFC6C6C8;
    static final int CAPSULE = 0xFFF2F2F7;
    static final int PRESSED = 0xFFEDEDF0;

    final float d;
    final Typeface w400;
    final Typeface w500;
    final Typeface w650;
    final Typeface w750;
    final Typeface w800;
    private final Context context;
    private final Map<Integer, Drawable> icons = new HashMap<>();

    private static Look shared;

    static Look get(Context context) {
        if (shared == null) shared = new Look(context.getApplicationContext());
        return shared;
    }

    private Look(Context c) {
        context = c;
        d = c.getResources().getDisplayMetrics().density;
        w400 = c.getResources().getFont(R.font.inter_400);
        w500 = c.getResources().getFont(R.font.inter_500);
        w650 = c.getResources().getFont(R.font.inter_650);
        w750 = c.getResources().getFont(R.font.inter_750);
        w800 = c.getResources().getFont(R.font.inter_800);
    }

    float px(float dp) {
        return dp * d;
    }

    /** A text paint in a face and size (dp), letter-spaced in ems as CSS's letter-spacing. */
    TextPaint paint(Typeface face, float size, int color, float tracking) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(px(size));
        p.setColor(color);
        p.setLetterSpacing(tracking);
        return p;
    }

    /**
     * The baseline for a line of text whose CSS line box (line-height
     * `lineHeight`, in dp) starts at `top` (px), as a browser centres it.
     */
    float baseline(Paint p, float top, float lineHeight) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return top + (px(lineHeight) - (fm.descent - fm.ascent)) / 2 - fm.ascent;
    }

    /** One line of text, cut with an ellipsis to `width` px. */
    void line(Canvas c, String text, TextPaint p, float x, float top, float lineHeight, float width) {
        if (text == null || text.isEmpty() || width <= 0) return;
        CharSequence cut = TextUtils.ellipsize(text, p, width, TextUtils.TruncateAt.END);
        c.drawText(cut, 0, cut.length(), x, baseline(p, top, lineHeight), p);
    }

    /** An icon from res/drawable, `size` dp square with its top left at (x, y) px. */
    void icon(Canvas c, int id, float x, float y, float size, int color) {
        Drawable icon = icons.get(id);
        if (icon == null) {
            icon = context.getDrawable(id).mutate();
            icons.put(id, icon);
        }
        int s = Math.round(px(size));
        int left = Math.round(x);
        int top = Math.round(y);
        icon.setBounds(left, top, left + s, top + s);
        icon.setTint(color);
        icon.draw(c);
    }

    /** Colour `c` with alpha `a` (0 to 1) times its own. */
    static int alpha(int c, float a) {
        int al = Math.round((c >>> 24) * Math.max(0, Math.min(1, a)));
        return (al << 24) | (c & 0xFFFFFF);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
