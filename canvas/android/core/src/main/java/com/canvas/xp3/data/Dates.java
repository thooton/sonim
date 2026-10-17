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

    /**
     * The day a week starts on, Calendar.MONDAY or Calendar.SUNDAY: the
     * week strip's first column, and the line between "this week" and
     * "next week" everywhere else, so the two never disagree.
     */
    private static int firstDay = Calendar.MONDAY;

    private Dates() {}

    public static void setTwentyFourHour(boolean value) {
        twentyFourHour = value;
    }

    public static void setWeekStart(int day) {
        firstDay = day == Calendar.SUNDAY ? Calendar.SUNDAY : Calendar.MONDAY;
    }

    public static int weekStartDay() {
        return firstDay;
    }

    /** "Tuesday, Oct 20": the due date as the assignment page states it. */
    public static String longDay(long when) {
        return format("EEEE, MMM d", when);
    }

    /**
     * "10:30" for the timeline's time column, which has room for the clock
     * and not the AM or PM; on a 12 hour phone that goes under it.
     */
    public static String clock(long when) {
        return format(twentyFourHour ? "HH:mm" : "h:mm", when);
    }

    /** "AM" or "PM" on a 12 hour phone, null on a 24 hour one. */
    public static String meridiem(long when) {
        return twentyFourHour ? null : format("a", when);
    }

    /** "TUESDAY 20", a day's heading on the timeline. */
    public static String dayHeading(long when) {
        return format("EEEE d", when).toUpperCase(Locale.US);
    }

    /** "M", "T": a column's letter on the week strip. */
    public static String initial(long when) {
        return format("EEEEE", when);
    }

    /** "Tue, Oct 20". */
    public static String shortDay(long when) {
        return format("EEE, MMM d", when);
    }

    /** "TUE": the tab on the tear-off calendar page. */
    public static String weekdayTab(long when) {
        return format("EEE", when).toUpperCase(Locale.US);
    }

    /** "20". */
    public static String dayOfMonth(long when) {
        return format("d", when);
    }

    /** "Oct 19 – 25", or "Oct 26 – Nov 1" across a month's end. */
    public static String weekRange(long weekStart) {
        long last = addDays(weekStart, 6);
        String from = format("MMM d", weekStart);
        boolean sameMonth = format("MMM", weekStart).equals(format("MMM", last));
        return from + " – " + (sameMonth ? format("d", last) : format("MMM d", last));
    }

    /** "Due Tue, Oct 20 at 10:30", the line under a title with room for one. */
    public static String dueLine(long when) {
        return "Due " + shortDay(when) + " at " + time(when);
    }

    /** Midnight at the start of the week `when` falls in. */
    public static long weekOf(long when) {
        return startOfWeek(when);
    }

    /** Midnight at the start of the day `when` falls in. */
    public static long dayOf(long when) {
        return startOfDay(when);
    }

    /**
     * Midnight `days` calendar days after the day `when` falls in. Counted
     * on the calendar rather than in milliseconds, so a daylight saving
     * change cannot land a day an hour short of its own midnight.
     */
    public static long addDays(long when, int days) {
        Calendar day = Calendar.getInstance(Locale.US);
        day.setTimeInMillis(startOfDay(when));
        day.add(Calendar.DAY_OF_MONTH, days);
        return day.getTimeInMillis();
    }

    /** 0 for the week's first day through 6 for its last. */
    public static int column(long when) {
        Calendar day = Calendar.getInstance(Locale.US);
        day.setTimeInMillis(when);
        return (day.get(Calendar.DAY_OF_WEEK) - firstDay + 7) % 7;
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
     * week, -1 last week. Weeks run Monday to Sunday unless Settings says
     * they start on Sunday.
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
        int first = firstDay;
        int shift = (week.get(Calendar.DAY_OF_WEEK) - first + 7) % 7;
        week.add(Calendar.DAY_OF_MONTH, -shift);
        return week.getTimeInMillis();
    }
}
