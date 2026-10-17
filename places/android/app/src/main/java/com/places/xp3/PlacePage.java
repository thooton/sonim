package com.places.xp3;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.places.xp3.net.Anchor;
import com.places.xp3.net.Place;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A place's own screen, laid out the way Google Maps lays out a place.
 *
 * At the top, on white: the name, large; the rating with its stars; what
 * kind of place, its price and how far; whether it is open, in green or
 * red, and when that changes; and chips for what Menu can do (Call,
 * Website). Under that, on the grouped page, a card of how to reach it, a
 * row each led by an icon rather than a label: address, phone, website.
 * Then, each under a heading, Hours (the week, today's day
 * in Medium), About, Amenities (chips, ticked or crossed) and Reviews (the
 * rating large, then each review with its writer's initial).
 */
final class PlacePage {

    /** The screen's sizes, in sp: the name, the headings, what is read, what follows it, the rating. */
    private static final float TITLE = 17f;
    private static final float HEADING = 14f;
    private static final float BODY = 12.5f;
    private static final float SMALL = 11.5f;
    private static final float BIG = 30f;

    private static final String NO_HOURS = "No opening hours listed.";
    private static final List<String> DAYS = java.util.Arrays.asList("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

    private final Context context;

    PlacePage(Context context) {
        this.context = context;
    }

    /**
     * Fills `body` with `place`. A place that only has what the search
     * returned (`partial`) shows its head and address, not "no hours" for
     * hours not yet fetched.
     */
    void fill(LinearLayout body, Place place, boolean partial, long now, Anchor near) {
        body.removeAllViews();
        body.addView(head(place, now, near));

        LinearLayout reach = card(body);
        if (place.address().length() > 0) row(reach, R.drawable.ic_place, line(place.address(), BODY, R.color.text));
        if (place.phone().length() > 0) row(reach, R.drawable.ic_call, line(place.phone(), BODY, R.color.focus_rule));
        if (place.website().length() > 0) row(reach, R.drawable.ic_language, line(host(place.website()), BODY, R.color.focus_rule));
        if (reach.getChildCount() == 0) body.removeView(reach);
        if (partial) return;

        hours(body, place, now);
        about(body, place);
        amenities(body, place);
        reviews(body, place);
    }

    // ---- The head ----

    private View head(Place place, long now, Anchor near) {
        LinearLayout head = vertical();
        head.setBackgroundColor(color(R.color.row));
        head.setPadding(dp(10), dp(11), dp(10), dp(12));
        TextView name = line(place.name(), TITLE, R.color.text);
        name.setTypeface(Family.MEDIUM);
        name.setLineSpacing(dp(1), 1f);
        head.addView(name);

        if (place.ratingCount() > 0) {
            LinearLayout rating = horizontal();
            rating.addView(line(String.format(Locale.US, "%.1f", place.rating()), 12f, R.color.text));
            rating.addView(stars(place.rating(), 11), at(dp(4), 0));
            rating.addView(line("(" + String.format(Locale.US, "%,d", place.ratingCount()) + ")", 12f, R.color.text_muted), at(dp(4), 0));
            head.addView(rating, at(0, dp(5)));
        }

        SpannableStringBuilder kind = new SpannableStringBuilder();
        for (String part : new String[] {place.type(), place.priceShort(), place.distanceFrom(near)}) {
            if (part.length() == 0) continue;
            if (kind.length() > 0) kind.append(" · ");
            kind.append(part);
        }
        if (place.wheelchairEntrance()) {
            if (kind.length() > 0) kind.append(" · ");
            span(kind, " ", new ImageSpan(icon(R.drawable.ic_accessible, 12, R.color.focus_rule), ImageSpan.ALIGN_BASELINE));
        }
        if (kind.length() > 0) head.addView(line(kind, 12f, R.color.text_muted), at(0, dp(4)));

        String[] status = place.statusParts(now);
        if (!status[0].equals("Hours unknown")) head.addView(line(status(place, status), 12f, R.color.text_muted), at(0, dp(4)));

        Flow chips = new Flow(context, dp(6), dp(6));
        if (place.phone().length() > 0) chips.addView(action("Call", R.drawable.ic_call, chips.getChildCount() == 0));
        if (place.website().length() > 0) chips.addView(action("Website", R.drawable.ic_language, chips.getChildCount() == 0));
        if (chips.getChildCount() > 0) head.addView(chips, at(0, dp(10)));
        return head;
    }

    /** "Open" in green or "Closed" in red, Medium, then "· Closes 9:00 PM" in what it is set in. */
    private CharSequence status(Place place, String[] parts) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        Boolean open = place.openNow();
        int tone = open == null ? R.color.text_muted : open ? R.color.open : R.color.closed;
        span(out, parts[0], new ForegroundColorSpan(color(tone)), new TypefaceSpan("sans-serif-medium"));
        if (parts[1].length() > 0) out.append(" · ").append(parts[1]);
        return out;
    }

    /** One of the actions under the name: an icon and a word on a pill, the first filled with the accent. */
    private View action(String label, int iconRes, boolean first) {
        int tone = first ? R.color.on_primary : R.color.focus_rule;
        TextView chip = line(label, 12f, tone);
        chip.setTypeface(Family.MEDIUM);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(first ? R.drawable.chip_primary : R.drawable.chip_tonal);
        chip.setPadding(dp(10), 0, dp(12), 0);
        chip.setCompoundDrawables(icon(iconRes, 14, tone), null, null, null);
        chip.setCompoundDrawablePadding(dp(5));
        chip.setMinHeight(dp(26));
        chip.setHeight(dp(26));
        return chip;
    }

    // ---- How to reach it ----

    /**
     * The week, a day a line, today's in Medium; then any other hours
     * (usual ones that differ, happy hour) under their own caption, and the
     * time there when it differs from here. Below the ways to reach the
     * place, as Google Maps folds them away, so the address and number come
     * first on a screen this small.
     */
    private void hours(LinearLayout body, Place place, long now) {
        heading(body, "Hours");
        LinearLayout column = padded(null);
        String today = place.today(now);
        boolean first = true;
        for (Place.Section block : place.hours()) {
            if (block.text.equals(NO_HOURS)) {
                column.addView(line(NO_HOURS, BODY, R.color.text_muted));
                continue;
            }
            if (!first) {
                TextView caption = line(block.title, SMALL, R.color.text_muted);
                caption.setTypeface(Family.MEDIUM);
                column.addView(caption, at(0, dp(10)));
            }
            for (String day : block.text.split("\n")) column.addView(day(day, today), at(0, column.getChildCount() == 0 ? 0 : dp(5)));
            first = false;
        }
        int here = TimeZone.getDefault().getOffset(now) / 60000;
        String there = place.localTime(now, here);
        if (there.length() > 0) column.addView(line("Local time there: " + there, SMALL, R.color.text_muted), at(0, dp(10)));
        card(body).addView(column);
    }

    /** "Mon 7:00 AM - 9:00 PM" as two columns, each range of a split day on its own line. */
    private View day(String text, String today) {
        String day = text.length() > 4 && DAYS.contains(text.substring(0, 3)) && text.charAt(3) == ' ' ? text.substring(0, 3) : "";
        String times = (day.length() > 0 ? text.substring(4) : text).replace(", ", "\n");
        boolean now = day.equals(today);
        int tone = now ? R.color.text : R.color.text_muted;
        LinearLayout row = horizontal();
        row.setGravity(Gravity.TOP);
        TextView name = line(day, BODY, tone);
        TextView open = line(times, BODY, tone);
        open.setLineSpacing(dp(2), 1f);
        if (now) {
            name.setTypeface(Family.MEDIUM);
            open.setTypeface(Family.MEDIUM);
        }
        row.addView(name, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(open);
        return row;
    }

    // ---- About, amenities, reviews ----

    private void about(LinearLayout body, Place place) {
        String editorial = place.editorial();
        String overview = place.overview();
        if (editorial.length() == 0 && overview.length() == 0) return;
        heading(body, "About");
        LinearLayout card = card(body);
        if (editorial.length() > 0) card.addView(padded(paragraph(editorial)));
        if (overview.length() > 0) {
            if (editorial.length() > 0) rule(card, 10);
            LinearLayout block = padded(paragraph(overview));
            block.addView(aiNote(), at(0, dp(7)));
            card.addView(block);
        }
    }

    /** What it has and hasn't as chips, then payment, parking and access as rows of chips, then fuel prices. */
    private void amenities(LinearLayout body, Place place) {
        Place.Options features = place.features();
        Place.Options payment = place.payment();
        Place.Options parking = place.parking();
        Place.Options access = place.accessibility();
        List<String[]> fuel = place.fuel();
        if (features.isEmpty() && payment.isEmpty() && parking.isEmpty() && access.isEmpty() && fuel.isEmpty()) return;
        heading(body, "Amenities");
        LinearLayout card = card(body);
        if (!features.isEmpty()) {
            Flow chips = facts(features);
            chips.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.addView(chips);
        }
        group(card, R.drawable.ic_credit_card, "Payment", payment);
        group(card, R.drawable.ic_local_parking, "Parking", parking);
        group(card, R.drawable.ic_accessible, "Wheelchair access", access);
        if (!fuel.isEmpty()) {
            LinearLayout column = vertical();
            column.addView(caption("Fuel prices"));
            for (String[] price : fuel) {
                LinearLayout line = horizontal();
                line.addView(line(capital(price[0]), BODY, R.color.text), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView amount = line(price[1], BODY, R.color.text);
                amount.setTypeface(Family.MEDIUM);
                line.addView(amount);
                column.addView(line, at(0, dp(5)));
            }
            row(card, R.drawable.ic_local_gas_station, column);
        }
    }

    private void group(LinearLayout card, int iconRes, String title, Place.Options options) {
        if (options.isEmpty()) return;
        LinearLayout column = vertical();
        column.addView(caption(title));
        column.addView(facts(options), at(0, dp(6)));
        row(card, iconRes, column);
    }

    /** A chip for each option: the ones it has ticked, in the text colour; the ones it hasn't crossed, muted. */
    private Flow facts(Place.Options options) {
        Flow chips = new Flow(context, dp(4), dp(4));
        for (String yes : options.yes) chips.addView(fact(yes, true));
        for (String no : options.no) chips.addView(fact(no, false));
        return chips;
    }

    private View fact(String label, boolean has) {
        TextView chip = line(capital(label), SMALL, has ? R.color.text : R.color.text_muted);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.fact_chip);
        chip.setPadding(dp(6), 0, dp(9), 0);
        chip.setHeight(dp(22));
        chip.setCompoundDrawables(icon(has ? R.drawable.ic_check : R.drawable.ic_close, 12, has ? R.color.focus_rule : R.color.text_muted), null, null, null);
        chip.setCompoundDrawablePadding(dp(4));
        return chip;
    }

    /** The rating large over its stars and count, Google's summary of the reviews, then each review. */
    private void reviews(LinearLayout body, Place place) {
        List<Place.Review> reviews = place.reviews();
        String summary = place.reviewSummary();
        if (place.ratingCount() == 0 && reviews.isEmpty() && summary.length() == 0) return;
        heading(body, "Reviews");
        if (place.ratingCount() > 0 || summary.length() > 0) {
            LinearLayout card = card(body);
            LinearLayout block = padded(null);
            if (place.ratingCount() > 0) {
                LinearLayout score = horizontal();
                TextView big = line(String.format(Locale.US, "%.1f", place.rating()), BIG, R.color.text);
                big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                score.addView(big);
                LinearLayout side = vertical();
                side.addView(stars(place.rating(), 12));
                int count = place.ratingCount();
                side.addView(line(String.format(Locale.US, "%,d review%s", count, count == 1 ? "" : "s"), SMALL, R.color.text_muted), at(0, dp(4)));
                score.addView(side, at(dp(10), 0));
                block.addView(score);
            }
            if (summary.length() > 0) {
                block.addView(paragraph(summary), at(0, block.getChildCount() == 0 ? 0 : dp(10)));
                block.addView(aiNote(), at(0, dp(7)));
            }
            card.addView(block);
        }
        if (reviews.isEmpty()) return;
        LinearLayout card = card(body);
        for (Place.Review review : reviews) {
            if (card.getChildCount() > 0) rule(card, 10);
            card.addView(review(review));
        }
    }

    /** The writer's initial in a circle, their name, the stars and how long ago; then what they wrote. */
    private View review(Place.Review review) {
        String author = review.author.length() > 0 ? review.author : "Someone";
        LinearLayout block = padded(null);
        LinearLayout top = horizontal();
        TextView initial = line(author.substring(0, author.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault()), 13f, R.color.focus_rule);
        initial.setTypeface(Family.MEDIUM);
        initial.setGravity(Gravity.CENTER);
        initial.setBackgroundResource(R.drawable.avatar);
        top.addView(initial, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout who = vertical();
        TextView name = line(author, BODY, R.color.text);
        name.setTypeface(Family.MEDIUM);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        who.addView(name);
        LinearLayout meta = horizontal();
        if (review.rating > 0) meta.addView(stars(review.rating, 9));
        if (review.ago.length() > 0) meta.addView(line(review.ago, SMALL, R.color.text_muted), at(meta.getChildCount() == 0 ? 0 : dp(5), 0));
        if (meta.getChildCount() > 0) who.addView(meta, at(0, dp(4)));
        top.addView(who, at(dp(8), 0));
        block.addView(top);
        if (review.text.length() > 0) block.addView(paragraph(review.text), at(0, dp(8)));
        return block;
    }

    // ---- Pieces ----

    /** A white card on the grouped page, 6dp in from its edges. */
    private LinearLayout card(LinearLayout body) {
        LinearLayout card = vertical();
        card.setBackgroundResource(com.xp3.common.ui.R.drawable.family_card);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        at.setMargins(dp(6), dp(6), dp(6), 0);
        body.addView(card, at);
        return card;
    }

    /** A heading over the cards that follow, on the page, in line with the text on them. */
    private void heading(LinearLayout body, String title) {
        TextView heading = line(title, HEADING, R.color.text);
        heading.setTypeface(Family.MEDIUM);
        heading.setPadding(dp(16), dp(16), dp(16), dp(4));
        body.addView(heading);
    }

    /** A line of a card: a muted icon, and beside it what it stands for; a hairline above all but the first, inset to the text. */
    private void row(LinearLayout card, int iconRes, View content) {
        if (card.getChildCount() > 0) rule(card, 40);
        LinearLayout row = horizontal();
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(10), dp(9), dp(10), dp(10));
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(color(R.color.text_muted)));
        row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout.LayoutParams beside = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        beside.leftMargin = dp(12);
        beside.topMargin = dp(2);
        row.addView(content, beside);
        card.addView(row);
    }

    private void rule(LinearLayout card, int inset) {
        View rule = new View(context);
        rule.setBackgroundColor(color(R.color.divider));
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        line.leftMargin = dp(inset);
        card.addView(rule, line);
    }

    private TextView caption(String title) {
        TextView caption = line(title, BODY, R.color.text);
        caption.setTypeface(Family.MEDIUM);
        return caption;
    }

    private TextView paragraph(String text) {
        TextView paragraph = line(text, BODY, R.color.text);
        paragraph.setLineSpacing(dp(2.5f), 1f);
        return paragraph;
    }

    /** Under what Google's AI wrote: a sparkle in the accent and who wrote it. */
    private TextView aiNote() {
        TextView note = line("Summarized by Google's AI", SMALL, R.color.text_muted);
        note.setCompoundDrawables(icon(R.drawable.ic_auto_awesome, 12, R.color.focus_rule), null, null, null);
        note.setCompoundDrawablePadding(dp(4));
        note.setGravity(Gravity.CENTER_VERTICAL);
        return note;
    }

    /** A block of a card with the card's padding, holding `first` if given. */
    private LinearLayout padded(View first) {
        LinearLayout block = vertical();
        block.setPadding(dp(10), dp(10), dp(10), dp(11));
        if (first != null) block.addView(first);
        return block;
    }

    private View stars(double rating, int size) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(new Stars(rating, dp(size), Math.max(1, dp(size / 8f)), color(R.color.star), color(R.color.star_empty)));
        return view;
    }

    private Drawable icon(int res, int size, int tone) {
        Drawable icon = context.getDrawable(res).mutate();
        icon.setTint(color(tone));
        icon.setBounds(0, 0, dp(size), dp(size));
        return icon;
    }

    private TextView line(CharSequence text, float sp, int tone) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color(tone));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    /** Wrap-content layout params, `left` and `top` in from what comes before. */
    private static LinearLayout.LayoutParams at(int left, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = left;
        params.topMargin = top;
        return params;
    }

    private static void span(SpannableStringBuilder out, String text, Object... spans) {
        int start = out.length();
        out.append(text);
        for (Object span : spans) out.setSpan(span, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
        return Ui.dp(context, value);
    }

    private int color(int res) {
        return context.getColor(res);
    }
}
