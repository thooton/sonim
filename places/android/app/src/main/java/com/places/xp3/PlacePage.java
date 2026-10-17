package com.places.xp3;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.places.xp3.net.Anchor;
import com.places.xp3.net.Place;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A place's page, in Google Maps' dark look, all on one page for the d-pad
 * to scroll through.
 *
 * At the top, staying put, the name: large, and shrinking to one small line
 * as the page scrolls under it. Then the rating, kind and whether it is
 * open; one photo; a row each for the address, the hours, the website and
 * the phone, each after a teal icon; then Reviews (the rating large,
 * Google's summary, each review) and About (what Google says of it, then
 * its amenities as ticked or crossed lines), each under a heading.
 */
final class PlacePage {

    private static final String NO_HOURS = "No opening hours listed.";
    private static final List<String> DAYS = java.util.Arrays.asList("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

    /** The name's size at the top of the page, and once scrolled. */
    private static final float NAME_LARGE = 18f;
    private static final float NAME_SMALL = 14f;
    /** How far the page scrolls while the name shrinks, in dp. */
    private static final float SHRINK = 56f;

    private final Look look;
    private final Photos photos;
    private final LinearLayout head;
    private final ScrollView scroll;
    private final LinearLayout body;

    private Place place;
    private boolean partial;
    private long now;
    private Anchor near;
    private TextView name;
    private View rule;

    PlacePage(Look look, Photos photos, LinearLayout head, ScrollView scroll, LinearLayout body) {
        this.look = look;
        this.photos = photos;
        this.head = head;
        this.scroll = scroll;
        this.body = body;
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> shrink(y));
    }

    /** A place freshly opened, at the top. One that only has what the search returned is `partial`. */
    void show(Place place, boolean partial, long now, Anchor near) {
        fill(place, partial, now, near);
        scroll.scrollTo(0, 0);
        shrink(0);
    }

    /** The same place with its details come, staying where it was scrolled to. */
    void update(Place place, long now, Anchor near) {
        fill(place, false, now, near);
        shrink(scroll.getScrollY());
    }

    private void fill(Place place, boolean partial, long now, Anchor near) {
        this.place = place;
        this.partial = partial;
        this.now = now;
        this.near = near;
        drawHead();
        body.removeAllViews();
        summary();
        photo();
        reach();
        if (partial) return;
        reviews();
        about();
    }

    // ---- The name ----

    private void drawHead() {
        head.removeAllViews();
        name = look.text(place.name(), NAME_LARGE, R.color.text);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLineSpacing(look.dp(1), 1f);
        head.addView(name);
        rule = look.hairline(0);
        head.addView(rule);
    }

    /**
     * The name in step with the scroll: from large, on up to two lines, at
     * the top, down to one small line once the page has moved under it, with
     * a hairline under it then, as Maps draws its sheet's header.
     */
    private void shrink(int scrolled) {
        if (name == null) return;
        float t = Math.max(0f, Math.min(1f, scrolled / (float) look.dp(SHRINK)));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_LARGE + (NAME_SMALL - NAME_LARGE) * t);
        name.setMaxLines(t < 0.5f ? 2 : 1);
        name.setTypeface(t < 0.5f ? look.regular : look.medium);
        int top = Math.round(look.dp(10) - look.dp(4) * t);
        name.setPadding(dp(12), top, dp(12), Math.round(look.dp(6) - look.dp(1) * t));
        rule.setVisibility(scrolled > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    // ---- The top of the page ----

    /** "4.3 ★★★★☆ (1,099)", "Coffee shop · $1-10 · ♿", "Closed · Opens 7:00 AM · 3.6 mi". */
    private void summary() {
        LinearLayout summary = look.vertical();
        summary.setPadding(dp(12), dp(2), dp(12), 0);
        if (place.ratingCount() > 0) {
            LinearLayout rating = look.horizontal();
            rating.addView(look.text(String.format(Locale.US, "%.1f", place.rating()), 12.5f, R.color.text));
            rating.addView(look.stars(place.rating(), 11), Look.at(dp(4), 0));
            rating.addView(look.text(String.format(Locale.US, "(%,d)", place.ratingCount()), 12.5f, R.color.text_muted), Look.at(dp(4), 0));
            summary.addView(rating);
        }
        SpannableStringBuilder kind = new SpannableStringBuilder();
        if (place.type().length() > 0) kind.append(place.type());
        CharSequence facts = look.facts(place, false, false);
        if (facts.length() > 0) Look.dot(kind).append(facts);
        if (kind.length() > 0) summary.addView(look.text(kind, 12.5f, R.color.text_muted), Look.at(0, dp(5)));
        CharSequence status = look.status(place, now, near);
        if (status.length() > 0) summary.addView(look.text(status, 12.5f, R.color.text_muted), Look.at(0, dp(5)));
        body.addView(summary);
    }

    /** The first of its photos, the width of the page: the only one fetched, so it costs one. */
    private void photo() {
        List<String> names = place.photos();
        if (names.isEmpty()) return;
        int width = (scroll.getWidth() > 0 ? scroll.getWidth() : dp(240)) - dp(24);
        ImageView frame = look.photo(width, dp(130));
        LinearLayout.LayoutParams at = (LinearLayout.LayoutParams) frame.getLayoutParams();
        at.leftMargin = dp(12);
        at.topMargin = dp(12);
        body.addView(frame);
        photos.show(frame, names.get(0));
    }

    /** Address, hours, website and phone, a row each after a teal icon; then the time there. */
    private void reach() {
        body.addView(rule(dp(12)));
        if (place.address().length() > 0) info(R.drawable.ic_place, look.text(place.address(), 13f, R.color.text));
        if (!partial) hours();
        if (place.website().length() > 0) info(R.drawable.ic_language, look.line(host(place.website()), 13f, R.color.text));
        if (place.phone().length() > 0) info(R.drawable.ic_call, look.line(place.phone(), 13f, R.color.text));
        if (!partial) {
            int here = TimeZone.getDefault().getOffset(now) / 60000;
            String there = place.localTime(now, here);
            if (there.length() > 0) info(R.drawable.ic_schedule, look.text("Local time: " + there, 13f, R.color.text_muted));
        }
    }

    /** The week under a clock: whether it is open leading, then a day a line, today in Medium. */
    private void hours() {
        LinearLayout column = look.vertical();
        String today = place.today(now);
        boolean any = false;
        boolean first = true;
        for (Place.Section block : place.hours()) {
            if (block.text.equals(NO_HOURS)) continue;
            any = true;
            if (!first) {
                TextView caption = look.text(block.title, 12.5f, R.color.text);
                caption.setTypeface(look.medium);
                column.addView(caption, Look.at(0, dp(10)));
            }
            for (String day : block.text.split("\n")) column.addView(day(day, today), Look.at(0, column.getChildCount() == 0 ? 0 : dp(5)));
            first = false;
        }
        if (!any) return;
        LinearLayout block = look.vertical();
        if (!place.statusParts(now)[0].equals("Hours unknown")) {
            block.addView(look.text(look.status(place, now, null), 13f, R.color.text_muted));
            block.addView(column, Look.at(0, dp(8)));
        } else {
            block.addView(column);
        }
        info(R.drawable.ic_schedule, block);
    }

    /** "Mon  7:00 AM - 9:00 PM" as two columns, each range of a split day on its own line. */
    private View day(String text, String today) {
        String day = text.length() > 4 && DAYS.contains(text.substring(0, 3)) && text.charAt(3) == ' ' ? text.substring(0, 3) : "";
        String times = (day.length() > 0 ? text.substring(4) : text).replace(", ", "\n");
        boolean now = day.equals(today);
        int tone = now ? R.color.text : R.color.text_muted;
        LinearLayout row = look.horizontal();
        row.setGravity(Gravity.TOP);
        TextView name = look.text(day, 12.5f, tone);
        TextView open = look.text(times, 12.5f, tone);
        open.setLineSpacing(dp(2), 1f);
        if (now) {
            name.setTypeface(look.medium);
            open.setTypeface(look.medium);
        }
        row.addView(name, new LinearLayout.LayoutParams(dp(38), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(open);
        return row;
    }

    // ---- Reviews ----

    /** The rating large, its stars and count; Google's summary; then each review. */
    private void reviews() {
        List<Place.Review> reviews = place.reviews();
        String summary = place.reviewSummary();
        if (place.ratingCount() == 0 && reviews.isEmpty() && summary.length() == 0) return;
        body.addView(rule(dp(12)));
        heading("Reviews");
        if (place.ratingCount() > 0) {
            LinearLayout score = look.horizontal();
            score.setPadding(dp(12), dp(2), dp(12), 0);
            score.addView(look.text(String.format(Locale.US, "%.1f", place.rating()), 36f, R.color.text));
            LinearLayout side = look.vertical();
            side.addView(look.stars(place.rating(), 13));
            int count = place.ratingCount();
            side.addView(look.text(String.format(Locale.US, "%,d review%s", count, count == 1 ? "" : "s"), 12f, R.color.text_muted), Look.at(0, dp(5)));
            score.addView(side, Look.at(dp(12), 0));
            body.addView(score);
        }
        if (summary.length() > 0) {
            LinearLayout card = card();
            card.addView(paragraph(summary));
            card.addView(aiNote(), Look.at(0, dp(8)));
        }
        for (Place.Review review : reviews) {
            body.addView(rule(dp(12)));
            body.addView(review(review));
        }
    }

    /**
     * A review as Maps shows one: the writer's initial in a coloured
     * circle beside their name, the stars and how long ago, then what they
     * wrote.
     */
    private View review(Place.Review review) {
        String author = review.author.length() > 0 ? review.author : "Someone";
        LinearLayout block = look.vertical();
        block.setPadding(dp(12), dp(12), dp(12), 0);
        LinearLayout top = look.horizontal();
        TextView initial = look.text(author.substring(0, author.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault()), 14f, R.color.text);
        initial.setTypeface(look.medium);
        initial.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(AVATARS[(author.hashCode() & 0x7fffffff) % AVATARS.length]);
        initial.setBackground(circle);
        top.addView(initial, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView who = look.line(author, 13.5f, R.color.text);
        LinearLayout.LayoutParams beside = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        beside.leftMargin = dp(10);
        top.addView(who, beside);
        block.addView(top);
        LinearLayout meta = look.horizontal();
        if (review.rating > 0) meta.addView(look.stars(review.rating, 11));
        if (review.ago.length() > 0) meta.addView(look.text(review.ago, 12f, R.color.text_muted), Look.at(meta.getChildCount() == 0 ? 0 : dp(6), 0));
        if (meta.getChildCount() > 0) block.addView(meta, Look.at(0, dp(9)));
        if (review.text.length() > 0) block.addView(paragraph(review.text), Look.at(0, dp(7)));
        return block;
    }

    /** The colours Google gives the circles of people without a photo. */
    private static final int[] AVATARS = {
        0xFF5C6BC0, 0xFF00897B, 0xFF8E24AA, 0xFFEF6C00, 0xFF6D4C41, 0xFF3949AB, 0xFF43A047, 0xFFD81B60, 0xFF546E7A,
    };

    // ---- About ----

    /** What Google says of it, then each group of amenities under a heading, as Maps' About lists them. */
    private void about() {
        String editorial = place.editorial();
        String overview = place.overview();
        boolean any = editorial.length() > 0 || overview.length() > 0 || !place.features().isEmpty()
            || !place.payment().isEmpty() || !place.parking().isEmpty() || !place.accessibility().isEmpty()
            || !place.fuel().isEmpty();
        if (!any) return;
        body.addView(rule(dp(12)));
        heading("About");
        if (editorial.length() > 0) {
            TextView text = paragraph(editorial);
            text.setPadding(dp(12), dp(2), dp(12), 0);
            body.addView(text);
        }
        if (overview.length() > 0) {
            LinearLayout card = card();
            card.addView(paragraph(overview));
            card.addView(aiNote(), Look.at(0, dp(8)));
        }
        options("Service options", place.features());
        options("Payments", place.payment());
        options("Parking", place.parking());
        options("Accessibility", place.accessibility());
        List<String[]> fuel = place.fuel();
        if (!fuel.isEmpty()) {
            subheading("Fuel prices");
            for (String[] price : fuel) {
                LinearLayout line = look.horizontal();
                line.setPadding(dp(12), dp(4), dp(12), dp(4));
                line.addView(look.text(capital(price[0]), 13f, R.color.text), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView amount = look.text(price[1], 13f, R.color.text);
                amount.setTypeface(look.medium);
                line.addView(amount);
                body.addView(line);
            }
        }
    }

    /** A caption, then a line each: a teal tick for what it has, a muted cross for what it hasn't. */
    private void options(String title, Place.Options options) {
        if (options.isEmpty()) return;
        subheading(title);
        for (String yes : options.yes) body.addView(option(yes, true));
        for (String no : options.no) body.addView(option(no, false));
    }

    private View option(String label, boolean has) {
        TextView line = look.text(capital(label), 13f, has ? R.color.text : R.color.text_muted);
        line.setCompoundDrawables(look.icon(has ? R.drawable.ic_check : R.drawable.ic_close, 16, has ? R.color.link : R.color.text_muted), null, null, null);
        line.setCompoundDrawablePadding(dp(10));
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(12), dp(5), dp(12), dp(5));
        return line;
    }

    // ---- Pieces ----

    /** A row: a teal icon, and beside it what it stands for. */
    private void info(int iconRes, View content) {
        LinearLayout row = look.horizontal();
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        ImageView icon = new ImageView(look.context);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(look.color(R.color.link)));
        row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout.LayoutParams beside = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        beside.leftMargin = dp(14);
        beside.topMargin = dp(2);
        row.addView(content, beside);
        body.addView(row);
    }

    private void heading(String title) {
        TextView heading = look.text(title, 15f, R.color.text);
        heading.setTypeface(look.medium);
        heading.setPadding(dp(12), dp(12), dp(12), dp(8));
        body.addView(heading);
    }

    private void subheading(String title) {
        TextView heading = look.text(title, 13f, R.color.text_muted);
        heading.setTypeface(look.medium);
        heading.setPadding(dp(12), dp(14), dp(12), dp(4));
        body.addView(heading);
    }

    /** A tonal card, as Maps sets off what its AI wrote. */
    private LinearLayout card() {
        LinearLayout card = look.vertical();
        card.setBackground(Look.rounded(look.color(R.color.card), dp(12)));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        at.setMargins(dp(10), dp(12), dp(10), 0);
        body.addView(card, at);
        return card;
    }

    private View rule(int top) {
        View rule = look.hairline(0);
        ((LinearLayout.LayoutParams) rule.getLayoutParams()).topMargin = top;
        return rule;
    }

    private TextView paragraph(String text) {
        TextView paragraph = look.text(text, 13f, R.color.text);
        paragraph.setLineSpacing(dp(3), 1f);
        return paragraph;
    }

    /** Under what Google's AI wrote: its sparkle in teal, and who wrote it. */
    private TextView aiNote() {
        TextView note = look.text("Summarized by Google's AI", 11.5f, R.color.text_muted);
        note.setCompoundDrawables(look.icon(R.drawable.ic_auto_awesome, 13, R.color.link), null, null, null);
        note.setCompoundDrawablePadding(dp(5));
        note.setGravity(Gravity.CENTER_VERTICAL);
        return note;
    }

    /** "avenuebrew.com" for "https://www.avenuebrew.com/menu", as Google Maps shows a website. */
    static String host(String url) {
        String host = Uri.parse(url).getHost();
        if (host == null || host.length() == 0) return url;
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private static String capital(String text) {
        return text.length() == 0 ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private int dp(float value) {
        return look.dp(value);
    }
}
