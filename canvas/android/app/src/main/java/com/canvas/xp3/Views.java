package com.canvas.xp3;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The pieces every screen is built from, in the sizes of the type scale.
 *
 * Sizes are the design's native pixels, which on the XP3's 240x320 mdpi
 * panel are dp one for one. Text sizes are sp scaled by the Large text
 * setting, so every step of the scale grows together.
 */
final class Views {

    final Context context;
    final Look look;

    Views(Context context, Look look) {
        this.context = context;
        this.look = look;
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    TextView text(CharSequence value, float sp, Typeface face, int color) {
        TextView view = new TextView(context);
        style(view, value, sp, face, color);
        return view;
    }

    MarkText mark(CharSequence value, float sp, Typeface face, int color) {
        MarkText view = new MarkText(context);
        style(view, value, sp, face, color);
        return view;
    }

    private void style(TextView view, CharSequence value, float sp, Typeface face, int color) {
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * look.scale);
        view.setTypeface(face);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
    }

    /** Screen titles: "Assigned", "Classes", "Settings". */
    TextView screenTitle(String value) {
        TextView view = text(value, 18, look.heavy, look.ink);
        view.setLetterSpacing(-0.02f);
        return view;
    }

    /** Assignment and question titles. */
    MarkText pageTitle(String value) {
        MarkText view = mark(value, 16, look.heavy, look.ink);
        view.setLetterSpacing(-0.02f);
        view.setLineSpacing(0, 1.05f);
        return view;
    }

    /** A row's title, which is what the highlighter marks. */
    MarkText itemTitle(String value) {
        MarkText view = mark(value, 12, look.semibold, look.ink);
        view.setLineSpacing(0, 1.1f);
        return view;
    }

    TextView meta(String value) {
        TextView view = text(value, 9, look.medium, look.muted);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    TextView bodyText(CharSequence value) {
        TextView view = text(value, 10.5f, look.regular, look.body);
        view.setLineSpacing(0, 1.3f);
        return view;
    }

    /** "TUESDAY 20", "YOUR WORK": small capitals, spaced. */
    TextView label(String value) {
        // Upper-cased here rather than with setAllCaps, which setSingleLine's
        // own transformation would silently replace.
        TextView view = text(value.toUpperCase(java.util.Locale.US), 8.5f, look.bold, look.muted);
        view.setLetterSpacing(0.09f);
        view.setSingleLine(true);
        return view;
    }

    /** Due times line up in a column, so the figures are all one width. */
    static void tabular(TextView view) {
        view.setFontFeatureSettings("tnum");
    }

    // ------------------------------------------------------------------
    // Shapes
    // ------------------------------------------------------------------

    GradientDrawable round(int fill, float radius, int stroke, float strokeWidth) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (strokeWidth > 0) shape.setStroke(Math.max(1, Math.round(strokeWidth * density())), stroke);
        return shape;
    }

    GradientDrawable dashed(int fill, float radius, int stroke) {
        GradientDrawable shape = round(fill, radius, stroke, 1.5f);
        shape.setStroke(Math.round(1.5f * density()), stroke, dp(4), dp(3));
        return shape;
    }

    private float density() {
        return context.getResources().getDisplayMetrics().density;
    }

    View dot(int color, int size) {
        View dot = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        dot.setBackground(shape);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return dot;
    }

    ImageView icon(int resource, int tint, int size) {
        ImageView image = new ImageView(context);
        image.setImageResource(resource);
        image.setColorFilter(tint);
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return image;
    }

    /** A rounded square with an icon in it, as rows and menus lead with. */
    FrameLayout tile(int resource, int fill, int tint, int size, int iconSize, float radius) {
        FrameLayout tile = new FrameLayout(context);
        tile.setBackground(round(fill, radius, 0, 0));
        ImageView image = icon(resource, tint, iconSize);
        tile.addView(image, new FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER));
        tile.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return tile;
    }

    /** A quiet pill: "20 points". `outlined` draws it in ink for a status. */
    TextView pill(String value, boolean outlined) {
        TextView view = text(value, 9.5f, look.bold, outlined ? look.ink : look.body);
        view.setBackground(outlined ? round(0, 20, look.ink, 1.5f) : round(look.paper, 20, look.paper, 1.5f));
        view.setPadding(dp(8), dp(2), dp(8), dp(2));
        return view;
    }

    TextView greenPill(String value) {
        TextView view = text(value, 9.5f, look.bold, look.okText);
        view.setBackground(round(look.okBg, 20, 0, 0));
        view.setPadding(dp(8), dp(2), dp(8), dp(2));
        return view;
    }

    /** A grade in a plain outlined chip. The same ink whatever the grade. */
    TextView gradeChip(String value) {
        TextView view = text(value, 10, look.bold, look.ink);
        tabular(view);
        view.setBackground(round(0, 20, look.ink, 1.5f));
        view.setPadding(dp(6), dp(1), dp(6), dp(1));
        return view;
    }

    /** The numbered badge a menu row leads with; yellow when its row has focus. */
    TextView key(String value) {
        TextView view = text(value, 8.5f, look.bold, look.muted);
        view.setGravity(Gravity.CENTER);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(15), dp(15)));
        view.setBackground(round(0, 4, look.line, 1));
        return view;
    }

    View hairline() {
        View view = new View(context);
        view.setBackgroundColor(look.line);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return view;
    }

    View space(int height) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return view;
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    // ------------------------------------------------------------------
    // Layout parameters
    // ------------------------------------------------------------------

    static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    static LinearLayout.LayoutParams tall() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    }

    LinearLayout.LayoutParams margins(LinearLayout.LayoutParams params, float left, float top, float right,
                                      float bottom) {
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    void pad(View view, float left, float top, float right, float bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }
}
