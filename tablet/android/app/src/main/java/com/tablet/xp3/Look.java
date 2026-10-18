package com.tablet.xp3;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The visual system (DESIGN.md, "Tablet"): e-ink grey paper, graphite
 * ink, and one colour, the reMarkable highlighter, which always means "this
 * is selected" and is never a filled box.
 *
 * Literata sets titles, Atkinson Hyperlegible everything else. Sizes in
 * the code are the proposal's pixels, drawn SCALE times larger: at its own
 * size the mockup's 9px text was too small to read on the phone at arm's
 * length. They are dp rather than sp because the screens are laid out to
 * the pixel.
 */
final class Look {

    /** How much larger than the proposal's pixels everything is drawn. */
    static final float SCALE = 1.25f;

    static final int PAPER = 0xFFE3E4DF;
    static final int SHEET = 0xFFF7F7F3;
    static final int INK = 0xFF222428;
    static final int GRAPHITE = 0xFF62666D;
    static final int RULE = 0xFFC5C7C1;
    static final int MARK = 0xFFF2D343;
    /** What lies over the page behind a sheet or the drawer. */
    static final int SCRIM = 0x73222428;
    /** The page itself, under what the tablet drew on it. */
    static final int PAGE = 0xFFFFFFFF;

    static Typeface serifBold = Typeface.SERIF;
    static Typeface ui = Typeface.SANS_SERIF;
    static Typeface uiBold = Typeface.DEFAULT_BOLD;
    private static boolean loaded;

    private Look() {}

    static void load(Context context) {
        if (loaded) return;
        serifBold = context.getResources().getFont(R.font.literata_semibold);
        ui = context.getResources().getFont(R.font.atkinson);
        uiBold = context.getResources().getFont(R.font.atkinson_bold);
        loaded = true;
    }

    /** A size from the proposal, in pixels on this screen. */
    static int dp(Context context, float value) {
        return Math.round(px(context, value));
    }

    static float px(Context context, float value) {
        return value * unit(context);
    }

    /** Screen pixels per proposal pixel. */
    static float unit(Context context) {
        return context.getResources().getDisplayMetrics().density * SCALE;
    }

    static TextView text(Context context, CharSequence value, Typeface face, float size, int color) {
        TextView view = new TextView(context);
        style(view, face, size, color);
        view.setText(value);
        return view;
    }

    static void style(TextView view, Typeface face, float size, int color) {
        view.setTypeface(face);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size * SCALE);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
    }

    /** A small uppercase label over a section ("ACTIVE", "TRANSFORM"). */
    static TextView cap(Context context, String value, int color) {
        TextView view = text(context, value.toUpperCase(java.util.Locale.US), uiBold, 8.5f, color);
        view.setLetterSpacing(0.13f);
        return view;
    }

    static Drawable icon(Context context, int res, int color, float size) {
        Drawable icon = context.getDrawable(res).mutate();
        icon.setTint(color);
        int side = dp(context, size);
        icon.setBounds(0, 0, side, side);
        return icon;
    }

    static GradientDrawable round(int fill, float radius, Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(px(context, radius));
        return shape;
    }

    static int alpha(int color, float amount) {
        return (Math.round(amount * 255) << 24) | (color & 0xFFFFFF);
    }
}
