package com.canvas.xp3.data;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Dates written the way someone says them out loud.
 *
 * A date on this screen is only ever read to answer "how much time have I
 * got", and "10/22/20" makes you do arithmetic to find that out. So the
 * nearest days get their names, the fortnight around today gets its weekday,
 * and only past that does a calendar date appear, still with its weekday in
 * front of it:
 *
 *   today, tomorrow, yesterday   Today / Tomorrow / Yesterday
 *   elsewhere in this week       Thursday
 *   the week either side         Next Thu / Last Thu
 *   further out                  Thu, Oct 3
 *
 * The Next and Last prefixes follow the calendar week rather than a count of
 * days, which is the only way they can agree with the section a row is filed
 * under. Thirteen days out from a Friday is a fortnight away in the sense
 * anybody means it, so it belongs under Later with a date on it, not under a
 * heading that says next week.
 *
 * A bare weekday is deliberately allowed to mean either direction, because
 * every list that shows one is already sitting under a section header saying
 * which week it is in, and "Thursday" is what you would have said anyway.
 */
public final class Dates {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /**
     * Whether the phone is set to a 24 hour clock, which the app reads once at
     * startup and every clock time here then follows.
     *
     * It is a plain boolean rather than a Context so that this class stays
     * ordinary Java that runs and can be checked off the device. The one thing
     * it needs to know about the phone is worth a setter; a Context would drag
     * the whole framework in behind it.
     */
    private static boolean twentyFourHour;

    private Dates() {}

    public static void setTwentyFourHour(boolean value) {
        twentyFourHour = value;
    }

    /** "Today", "Thursday", "Next Thu", "Thu, Oct 3". */
    public static String day(long when, long now) {
        int days = daysBetween(now, when);
        if (days == 0) return "Today";
        if (days == 1) return "Tomorrow";
        if (days == -1) return "Yesterday";
        int weeks = weeksBetween(now, when);
        if (weeks == 0) return weekday(when, false);
        if (weeks == 1) return "Next " + weekday(when, true);
        if (weeks == -1) return "Last " + weekday(when, true);
        return format("EEE, MMM d", when);
    }

    /** "11:59 PM", or "23:59" on a phone set to a 24 hour clock. */
    public static String time(long when) {
        return format(twentyFourHour ? "HH:mm" : "h:mm a", when);
    }

    /** "Tomorrow, 11:59 PM", for the places with a full line to spend. */
    public static String dayAndTime(long when, long now) {
        return day(when, now) + ", " + time(when);
    }

    /**
     * Whole days from one instant to the other, counted between the calendar
     * days they fall on rather than between the instants themselves. Something
     * due at 11:59 tonight is due today, not in eleven hours.
     */
    public static int daysBetween(long from, long to) {
        // Rounding absorbs the hour a daylight saving change adds to or takes
        // out of the difference; nothing else here is within twelve hours of a
        // boundary, so nearest-day is exact.
        return (int) Math.round(
            (double) (startOfDay(to) - startOfDay(from)) / (double) DAY_MS
        );
    }

    /**
     * Which week an instant falls in relative to now: 0 this week, 1 next
     * week, -1 last week. Weeks run Monday to Sunday, so Sunday night is the
     * end of "this week" and Monday morning starts the next one.
     */
    public static int weeksBetween(long from, long to) {
        return (int) Math.round(
            (double) (startOfWeek(to) - startOfWeek(from)) / (double) (7L * DAY_MS)
        );
    }

    /** Whether two instants fall in the same calendar year. */
    public static boolean sameYear(long from, long to) {
        Calendar first = Calendar.getInstance(Locale.US);
        first.setTimeInMillis(from);
        Calendar second = Calendar.getInstance(Locale.US);
        second.setTimeInMillis(to);
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR);
    }

    private static String weekday(long when, boolean shortened) {
        return format(shortened ? "EEE" : "EEEE", when);
    }

    private static String format(String pattern, long when) {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(when));
    }

    private static long startOfDay(long when) {
        Calendar day = Calendar.getInstance(Locale.US);
        day.setTimeInMillis(when);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }

    private static long startOfWeek(long when) {
        Calendar week = Calendar.getInstance(Locale.US);
        week.setTimeInMillis(startOfDay(when));
        int first = Calendar.MONDAY;
        int shift = (week.get(Calendar.DAY_OF_WEEK) - first + 7) % 7;
        week.add(Calendar.DAY_OF_MONTH, -shift);
        return week.getTimeInMillis();
    }
}
