package com.tablet.xp3.data;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Times in the words the screens use: "2h ago" for when a file was edited,
 * "10:42" for when a job finished today and "Oct 12" before that.
 */
public final class When {

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private When() {}

    /** How long ago: "just now", "5m ago", "2h ago", "yesterday", "Oct 1", "Oct 1, 2025". */
    public static String ago(long then, long now, TimeZone zone) {
        long minutes = (now - then) / 60000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        if (minutes < 24 * 60 && sameDay(then, now, zone)) return minutes / 60 + "h ago";
        if (sameDay(then, now - 24 * 3600 * 1000L, zone)) return "yesterday";
        if (minutes < 24 * 60) return minutes / 60 + "h ago";
        return date(then, now, zone);
    }

    /** The time of day, "14:42" on the 24-hour clock or "2:42" on the 12-hour one. */
    public static String clock(long at, TimeZone zone, boolean hours24) {
        Calendar c = calendar(at, zone);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        if (!hours24) hour = hour % 12 == 0 ? 12 : hour % 12;
        return String.format(Locale.US, "%d:%02d", hour, c.get(Calendar.MINUTE));
    }

    /** When something happened, as briefly as is clear: "10:42" today, "yesterday", else the date. */
    public static String day(long at, long now, TimeZone zone, boolean hours24) {
        if (sameDay(at, now, zone)) return clock(at, zone, hours24);
        if (sameDay(at, now - 24 * 3600 * 1000L, zone)) return "yesterday";
        return date(at, now, zone);
    }

    /** "Oct 1", with the year only when it isn't this one. */
    static String date(long at, long now, TimeZone zone) {
        Calendar c = calendar(at, zone);
        String out = MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.DAY_OF_MONTH);
        return c.get(Calendar.YEAR) == calendar(now, zone).get(Calendar.YEAR) ? out : out + ", " + c.get(Calendar.YEAR);
    }

    static boolean sameDay(long a, long b, TimeZone zone) {
        Calendar x = calendar(a, zone);
        Calendar y = calendar(b, zone);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    private static Calendar calendar(long at, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(at);
        return c;
    }
}
