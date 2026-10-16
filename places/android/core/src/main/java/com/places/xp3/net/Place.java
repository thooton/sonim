package com.places.xp3.net;

import com.xp3.common.Json;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One place as the Places API (New) describes it, read the way `delta maps`
 * reads it (cmd/delta/maps.go in thooton/delta) and written out for a 240px
 * screen.
 *
 * It keeps Google's object as it came and reads fields as they are asked
 * for: a search result carries a handful of fields and a place's details
 * dozens, and both are the same class.
 */
public final class Place {

    /** A heading over some text: one block of a place's details. A null title runs on from the name. */
    public static final class Section {
        public final String title;
        public final String text;

        Section(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    /** The yes/no fields and their labels, in the order they are shown. */
    static final String[][] FEATURES = {
        {"dineIn", "dine-in"}, {"takeout", "takeout"}, {"delivery", "delivery"},
        {"curbsidePickup", "curbside pickup"}, {"reservable", "reservations"},
        {"servesBreakfast", "breakfast"}, {"servesBrunch", "brunch"}, {"servesLunch", "lunch"},
        {"servesDinner", "dinner"}, {"servesDessert", "dessert"}, {"servesCoffee", "coffee"},
        {"servesBeer", "beer"}, {"servesWine", "wine"}, {"servesCocktails", "cocktails"},
        {"servesVegetarianFood", "vegetarian food"}, {"outdoorSeating", "outdoor seating"},
        {"liveMusic", "live music"}, {"menuForChildren", "kids' menu"},
        {"goodForChildren", "good for kids"}, {"goodForGroups", "good for groups"},
        {"goodForWatchingSports", "good for watching sports"}, {"allowsDogs", "dogs allowed"},
        {"restroom", "restroom"},
    };

    private static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US);

    private final Map<String, Object> raw;

    public Place(Map<String, Object> raw) {
        this.raw = raw;
    }

    public String id() {
        return orEmpty(Json.text(raw, "id"));
    }

    public String name() {
        String name = Json.text(Json.child(raw, "displayName"), "text");
        return name == null || name.trim().length() == 0 ? "Unnamed place" : name;
    }

    public String type() {
        return orEmpty(Json.text(Json.child(raw, "primaryTypeDisplayName"), "text"));
    }

    public String address() {
        return orEmpty(Json.text(raw, "formattedAddress"));
    }

    /** The national number, which is what the phone dials; the international one when there is no other. */
    public String phone() {
        String phone = Json.text(raw, "nationalPhoneNumber");
        return phone != null ? phone : orEmpty(Json.text(raw, "internationalPhoneNumber"));
    }

    public String website() {
        return orEmpty(Json.text(raw, "websiteUri"));
    }

    public boolean located() {
        Map<String, Object> at = Json.child(raw, "location");
        return Json.number(at, "latitude") != null && Json.number(at, "longitude") != null;
    }

    public double lat() {
        return Json.number(Json.child(raw, "location"), "latitude");
    }

    public double lng() {
        return Json.number(Json.child(raw, "location"), "longitude");
    }

    /** "2.4 mi" from `near`, or "" when either end has no point. */
    public String distanceFrom(Anchor near) {
        if (near == null || !located()) return "";
        return Anchor.distance(near.metersTo(lat(), lng()));
    }

    /** "4.6★ (820) · $$ · Coffee shop", for a row of results. */
    public String summary() {
        List<String> parts = new ArrayList<String>();
        if (ratingCount() > 0) parts.add(String.format(Locale.US, "%.1f★ (%s)", rating(), commas(ratingCount())));
        String level = priceLevel();
        if (level.length() > 0) parts.add(level);
        if (type().length() > 0) parts.add(type());
        return join(parts, " · ");
    }

    double rating() {
        Double rating = Json.number(raw, "rating");
        return rating == null ? 0 : rating;
    }

    int ratingCount() {
        Double count = Json.number(raw, "userRatingCount");
        return count == null ? 0 : count.intValue();
    }

    String priceLevel() {
        String level = orEmpty(Json.text(raw, "priceLevel"));
        if (level.equals("PRICE_LEVEL_FREE")) return "free";
        if (level.equals("PRICE_LEVEL_INEXPENSIVE")) return "$";
        if (level.equals("PRICE_LEVEL_MODERATE")) return "$$";
        if (level.equals("PRICE_LEVEL_EXPENSIVE")) return "$$$";
        if (level.equals("PRICE_LEVEL_VERY_EXPENSIVE")) return "$$$$";
        return "";
    }

    /** "$$ ($10–20 per person)", or as much of it as Google knows. */
    String price() {
        String level = priceLevel();
        Map<String, Object> range = Json.child(raw, "priceRange");
        if (!(range.get("startPrice") instanceof Map)) return level;
        String text = money(Json.child(range, "startPrice"));
        if (range.get("endPrice") instanceof Map) {
            String end = money(Json.child(range, "endPrice"));
            text += "–" + (end.startsWith("$") ? end.substring(1) : end);
        } else {
            text += "+";
        }
        return (level + " (" + text + " per person)").trim();
    }

    /** Open or closed now and until when, in the place's own time: "Open until 9:00 PM". */
    public String status(long now) {
        String business = orEmpty(Json.text(raw, "businessStatus"));
        if (business.equals("CLOSED_PERMANENTLY")) return "Permanently closed";
        if (business.equals("CLOSED_TEMPORARILY")) return "Temporarily closed";
        Map<String, Object> hours = Json.child(raw, "currentOpeningHours");
        Object open = hours.get("openNow");
        if (!(open instanceof Boolean)) return "Hours unknown";
        ZoneOffset zone = zone();
        OffsetDateTime here = Instant.ofEpochMilli(now).atOffset(zone == null ? ZoneOffset.UTC : zone);
        boolean isOpen = (Boolean) open;
        OffsetDateTime next = time(hours, isOpen ? "nextCloseTime" : "nextOpenTime", here.getOffset());
        if (isOpen) return next == null ? "Open now" : "Open until " + when(next, here);
        return next == null ? "Closed now" : "Closed until " + when(next, here);
    }

    /** Whether the place is open right now: null when Google doesn't say. */
    public Boolean openNow() {
        String business = orEmpty(Json.text(raw, "businessStatus"));
        if (business.equals("CLOSED_PERMANENTLY") || business.equals("CLOSED_TEMPORARILY")) return Boolean.FALSE;
        Object open = Json.child(raw, "currentOpeningHours").get("openNow");
        return open instanceof Boolean ? (Boolean) open : null;
    }

    /**
     * A time relative to now, both in the place's offset: "9:00 PM" today,
     * "midnight" at the end of today, "tomorrow 7:00 AM", "Wed 7:00 AM" this
     * week, "Wed Jan 7, 7:00 AM" after that.
     */
    static String when(OffsetDateTime t, OffsetDateTime now) {
        if (t.toLocalDate().equals(now.toLocalDate())) return CLOCK.format(t);
        boolean tomorrow = t.toLocalDate().equals(now.toLocalDate().plusDays(1));
        if (tomorrow && t.getHour() == 0 && t.getMinute() == 0) return "midnight";
        if (tomorrow) return "tomorrow " + CLOCK.format(t);
        if (t.isBefore(now.plusDays(6))) return WEEKDAY.format(t);
        return DATE.format(t);
    }

    private ZoneOffset zone() {
        Double minutes = Json.number(raw, "utcOffsetMinutes");
        if (minutes == null || Math.abs(minutes) > 18 * 60) return null;
        return ZoneOffset.ofTotalSeconds(minutes.intValue() * 60);
    }

    private static OffsetDateTime time(Map<String, Object> hours, String key, ZoneOffset zone) {
        String text = Json.text(hours, key);
        if (text == null) return null;
        try {
            return OffsetDateTime.parse(text.trim()).withOffsetSameInstant(zone);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Everything about the place, top to bottom, for its own screen. */
    public List<Section> details(long now, Anchor near) {
        List<Section> out = new ArrayList<Section>();
        List<String> top = new ArrayList<String>();
        String type = type();
        if (type.length() > 0) top.add(type);
        if (ratingCount() > 0) {
            top.add(String.format(Locale.US, "%s %.1f (%s review%s)", stars(rating()), rating(),
                commas(ratingCount()), ratingCount() == 1 ? "" : "s"));
        }
        String price = price();
        if (price.length() > 0) top.add(price);
        top.add(status(now));
        String distance = distanceFrom(near);
        if (distance.length() > 0) top.add(distance + " from " + near.label);
        out.add(new Section(null, join(top, "\n")));

        add(out, "Address", address());
        add(out, "Phone", phone());
        add(out, "Website", website());
        ZoneOffset zone = zone();
        if (zone != null) {
            add(out, "Local time there", DATE.format(Instant.ofEpochMilli(now).atOffset(zone)));
        }

        List<String> current = hours(Json.child(raw, "currentOpeningHours"));
        List<String> usual = hours(Json.child(raw, "regularOpeningHours"));
        if (!current.isEmpty()) {
            add(out, "Hours this week", join(current, "\n"));
            if (!usual.isEmpty() && !usual.equals(current)) add(out, "Usual hours", join(usual, "\n"));
        } else if (!usual.isEmpty()) {
            add(out, "Hours", join(usual, "\n"));
        } else {
            add(out, "Hours", "No opening hours listed.");
        }
        for (Object item : Json.children(raw, "currentSecondaryOpeningHours")) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> secondary = Json.object(item);
            List<String> lines = hours(secondary);
            if (lines.isEmpty()) continue;
            String kind = orEmpty(Json.text(secondary, "secondaryHoursType")).toLowerCase(Locale.US).replace('_', ' ').trim();
            if (kind.length() == 0) kind = "other";
            if (!kind.endsWith("hour")) kind += " hours";
            add(out, Character.toUpperCase(kind.charAt(0)) + kind.substring(1), join(lines, "\n"));
        }

        add(out, "About", Json.text(Json.child(raw, "editorialSummary"), "text"));
        add(out, "Overview (written by Google's AI)",
            Json.text(Json.child(Json.child(raw, "generativeSummary"), "overview"), "text"));
        add(out, "What reviewers say (written by Google's AI)",
            Json.text(Json.child(Json.child(raw, "reviewSummary"), "text"), "text"));

        List<String> yes = new ArrayList<String>();
        List<String> no = new ArrayList<String>();
        for (String[] feature : FEATURES) {
            Object value = raw.get(feature[0]);
            if (Boolean.TRUE.equals(value)) yes.add(feature[1]);
            else if (Boolean.FALSE.equals(value)) no.add(feature[1]);
        }
        add(out, "Has", join(yes, ", "));
        add(out, "Doesn't have", join(no, ", "));
        add(out, "Payment", options(Json.child(raw, "paymentOptions"), "accepts"));
        add(out, "Parking", options(Json.child(raw, "parkingOptions"), ""));
        add(out, "Wheelchair access", options(Json.child(raw, "accessibilityOptions"), "wheelchairAccessible"));

        List<String> fuel = new ArrayList<String>();
        for (Object item : Json.children(Json.child(raw, "fuelOptions"), "fuelPrices")) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> entry = Json.object(item);
            String kind = orEmpty(Json.text(entry, "type")).toLowerCase(Locale.US).replace('_', ' ');
            fuel.add(kind + ": " + money(Json.child(entry, "price")));
        }
        add(out, "Fuel", join(fuel, "\n"));

        List<String> reviews = new ArrayList<String>();
        for (Object item : Json.children(raw, "reviews")) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> review = Json.object(item);
            Double rating = Json.number(review, "rating");
            List<String> head = new ArrayList<String>();
            if (rating != null) head.add(stars(rating));
            String who = Json.text(Json.child(review, "authorAttribution"), "displayName");
            if (who != null && who.trim().length() > 0) head.add(who.trim());
            String ago = Json.text(review, "relativePublishTimeDescription");
            if (ago != null && ago.trim().length() > 0) head.add(ago.trim());
            String text = Json.text(Json.child(review, "text"), "text");
            text = text == null || text.trim().length() == 0 ? "(no text)" : text.trim();
            reviews.add(join(head, " · ") + "\n" + text);
        }
        add(out, reviews.size() == 1 ? "Review" : "Reviews", join(reviews, "\n\n"));
        return out;
    }

    /** A block of opening hours, one day a line: "Mon 7:00 AM – 9:00 PM". */
    private static List<String> hours(Map<String, Object> block) {
        List<String> lines = new ArrayList<String>();
        for (Object item : Json.children(block, "weekdayDescriptions")) {
            if (!(item instanceof String)) continue;
            String line = plainSpaces((String) item);
            for (String day : DAYS) {
                if (line.startsWith(day + ": ")) {
                    line = day.substring(0, 3) + " " + line.substring(day.length() + 2);
                    break;
                }
            }
            lines.add(line);
        }
        return lines;
    }

    /**
     * A set of yes/no options, the yes ones first: "credit cards, NFC (not:
     * cash only)". `prefix` comes off each name: "acceptsCreditCards".
     */
    static String options(Map<String, Object> set, String prefix) {
        List<String> keys = new ArrayList<String>(set.keySet());
        Collections.sort(keys);
        List<String> yes = new ArrayList<String>();
        List<String> no = new ArrayList<String>();
        for (String key : keys) {
            String label = words(key.startsWith(prefix) ? key.substring(prefix.length()) : key);
            if (label.length() == 0) label = "yes";
            if (Boolean.TRUE.equals(set.get(key))) yes.add(label);
            else if (Boolean.FALSE.equals(set.get(key))) no.add(label);
        }
        String text = join(yes, ", ");
        if (!no.isEmpty()) {
            if (text.length() == 0) text = "none";
            text += " (not: " + join(no, ", ") + ")";
        }
        return text;
    }

    /** "freeParkingLot" as "free parking lot", and "Nfc" as "NFC". */
    static String words(String camel) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) out.append(' ');
            out.append(Character.toLowerCase(c));
        }
        return out.toString().trim().replace("nfc", "NFC");
    }

    /** {"currencyCode": "USD", "units": "10", "nanos": 500000000} as "$10.50". */
    static String money(Map<String, Object> money) {
        Object unitsValue = money.get("units");
        String units = unitsValue == null ? "0" : String.valueOf(unitsValue);
        Double nanos = Json.number(money, "nanos");
        String amount = units;
        if (nanos != null && nanos != 0) {
            try {
                amount = String.format(Locale.US, "%.2f", Double.parseDouble(units) + nanos / 1e9);
            } catch (NumberFormatException e) {
                amount = units;
            }
        }
        String currency = Json.text(money, "currencyCode");
        if (currency == null || currency.equals("USD")) return "$" + amount;
        return amount + " " + currency;
    }

    /** ★★★★☆ for a rating, rounded to the nearest star. */
    static String stars(double rating) {
        int whole = (int) Math.max(0, Math.min(5, Math.round(rating)));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 5; i++) out.append(i < whole ? '★' : '☆');
        return out.toString();
    }

    static String commas(int n) {
        return String.format(Locale.US, "%,d", n);
    }

    /** Google writes hours with thin and non-breaking spaces; made plain, they wrap. */
    static String plainSpaces(String text) {
        return text.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ');
    }

    private static void add(List<Section> out, String title, String text) {
        if (text != null && text.trim().length() > 0) out.add(new Section(title, text.trim()));
    }

    private static String join(List<String> parts, String between) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.length() == 0) continue;
            if (out.length() > 0) out.append(between);
            out.append(part);
        }
        return out.toString();
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }
}
