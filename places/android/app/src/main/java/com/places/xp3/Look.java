package com.places.xp3;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.places.xp3.net.Anchor;
import com.places.xp3.net.Place;
import com.xp3.common.ui.Ui;
import java.util.Locale;

/**
 * How Google Maps draws things, in its dark theme, shared by the results and
 * a place's page: Google Sans, round buttons, rounded photos, the lines of
 * facts under a name ("4.8 ★ (112) · $10-20 · ♿ · Cafe", "Closed · Opens
 * 7:00 AM · 3.4 mi").
 */
final class Look {

    final Context context;
    final Typeface regular;
    final Typeface medium;

    Look(Context context) {
        this.context = context;
        this.regular = context.getResources().getFont(R.font.google_sans);
        this.medium = context.getResources().getFont(R.font.google_sans_medium);
    }

    // ---- Lines of facts ----

    /**
     * "4.8 ★ (112) · $10-20 · ♿ · Cafe": the rating in the text colour, the
     * rest muted. A place's sheet puts the rating and the kind elsewhere.
     */
    CharSequence facts(Place place, boolean rating, boolean kind) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (rating && place.ratingCount() > 0) {
            span(out, String.format(Locale.US, "%.1f", place.rating()), new ForegroundColorSpan(color(R.color.text)));
            out.append(' ');
            Stars star = new Stars(1, 1, dp(11), 0, color(R.color.star), color(R.color.star_empty));
            span(out, " ", new ImageSpan(star, ImageSpan.ALIGN_BASELINE));
            out.append(String.format(Locale.US, " (%,d)", place.ratingCount()));
        }
        String price = place.priceShort();
        if (price.length() > 0) dot(out).append(price);
        if (place.wheelchairEntrance()) span(dot(out), " ", new ImageSpan(icon(R.drawable.ic_accessible, 13, R.color.wheelchair), ImageSpan.ALIGN_BASELINE));
        if (kind && place.type().length() > 0) dot(out).append(place.type());
        return out;
    }

    /** "Closed · Opens 7:00 AM · 3.4 mi": only the open-or-closed word in colour. */
    CharSequence status(Place place, long now, Anchor near) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] parts = place.statusParts(now);
        if (!parts[0].equals("Hours unknown")) {
            Boolean open = place.openNow();
            int tone = open == null ? R.color.text_muted : open ? R.color.open : R.color.closed;
            span(out, parts[0], new ForegroundColorSpan(color(tone)));
            if (parts[1].length() > 0) dot(out).append(parts[1]);
        }
        String distance = place.distanceFrom(near);
        if (distance.length() > 0) dot(out).append(distance);
        return out;
    }

    /** " · " between the parts of a line, none before the first. */
    static SpannableStringBuilder dot(SpannableStringBuilder out) {
        return out.length() == 0 ? out : out.append(" · ");
    }

    static void span(SpannableStringBuilder out, CharSequence text, Object... spans) {
        int start = out.length();
        out.append(text);
        for (Object span : spans) out.setSpan(span, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---- Views ----

    TextView text(CharSequence value, float sp, int tone) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color(tone));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(regular);
        view.setIncludeFontPadding(false);
        return view;
    }

    TextView line(CharSequence value, float sp, int tone) {
        TextView view = text(value, sp, tone);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    /** A round button: an icon on a circle. */
    ImageView round(int iconRes, int size, int iconSize, int fill, int tone) {
        ImageView view = new ImageView(context);
        view.setImageResource(iconRes);
        view.setImageTintList(ColorStateList.valueOf(color(tone)));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = (dp(size) - dp(iconSize)) / 2;
        view.setPadding(pad, pad, pad, pad);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color(fill));
        view.setBackground(circle);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return view;
    }

    /** A photo's frame: rounded, the grey Maps shows while it loads, cropping what it holds. */
    ImageView photo(int width, int height) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackground(rounded(color(R.color.photo), dp(10)));
        view.setClipToOutline(true);
        view.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        return view;
    }

    View hairline(int left) {
        View rule = new View(context);
        rule.setBackgroundColor(color(R.color.hairline));
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        line.leftMargin = left;
        rule.setLayoutParams(line);
        return rule;
    }

    View stars(double rating, int size) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(new Stars(rating, dp(size), Math.max(1, dp(size / 7f)), color(R.color.star), color(R.color.star_empty)));
        return view;
    }

    GradientDrawable pill(int fill, int stroke, int strokeColor) {
        GradientDrawable pill = rounded(fill, dp(100));
        if (stroke > 0) pill.setStroke(stroke, strokeColor);
        return pill;
    }

    static GradientDrawable rounded(int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        return shape;
    }

    Drawable icon(int res, int size, int tone) {
        Drawable icon = context.getDrawable(res).mutate();
        icon.setTint(color(tone));
        icon.setBounds(0, 0, dp(size), dp(size));
        return icon;
    }

    LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    /** Wrap-content layout params, `left` and `top` in from what comes before. */
    static LinearLayout.LayoutParams at(int left, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = left;
        params.topMargin = top;
        return params;
    }

    int dp(float value) {
        return Ui.dp(context, value);
    }

    int color(int res) {
        return context.getColor(res);
    }
}
