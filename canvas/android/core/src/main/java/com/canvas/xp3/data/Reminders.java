package com.canvas.xp3.data;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * When to remind the student about due work, and about what.
 *
 * Reminders are opt-in in spirit even where the default is on: the student
 * picks the morning of (08:00), the evening before (18:00), both, or off, and
 * can silence one class. A reminder names the work and the date it is due.
 * It is never repeated and never says how little time is left.
 */
public final class Reminders {

    private Reminders() {}

    public static final int MORNING_HOUR = 8;
    public static final int EVENING_HOUR = 18;

    /** The next reminder: when it fires, and the work it names. */
    public static final class Next {
        public final long at;
        /** True for the morning-of reminder, false for the evening before. */
        public final boolean morning;
        public final List<Assignment> work;

        Next(long at, boolean morning, List<Assignment> work) {
            this.at = at;
            this.morning = morning;
            this.work = work;
        }
    }

    /**
     * The first reminder after `now` with work to name, looking a fortnight
     * ahead, or null. Work counts if it is still assigned when the reminder
     * fires, so something turned in early is never brought up.
     */
    public static Next next(long now, String mode, List<Assignment> all, java.util.Set<String> quiet) {
        if (Prefs.OFF.equals(mode)) return null;
        boolean mornings = Prefs.MORNING.equals(mode) || Prefs.BOTH.equals(mode);
        boolean evenings = Prefs.EVENING.equals(mode) || Prefs.BOTH.equals(mode);
        // Each day offers a morning reminder, about that day, and an evening
        // one, about the next; in that order, which is the order they fire.
        for (int day = 0; day < 15; day++) {
            long midnight = Dates.addDays(now, day);
            if (mornings) {
                long at = at(midnight, MORNING_HOUR);
                if (at > now) {
                    List<Assignment> due = dueOn(all, midnight, at, quiet);
                    if (!due.isEmpty()) return new Next(at, true, due);
                }
            }
            if (evenings) {
                long at = at(midnight, EVENING_HOUR);
                if (at > now) {
                    List<Assignment> due = dueOn(all, Dates.addDays(midnight, 1), at, quiet);
                    if (!due.isEmpty()) return new Next(at, false, due);
                }
            }
        }
        return null;
    }

    private static long at(long midnight, int hour) {
        Calendar time = Calendar.getInstance(Locale.US);
        time.setTimeInMillis(midnight);
        time.set(Calendar.HOUR_OF_DAY, hour);
        return time.getTimeInMillis();
    }

    private static List<Assignment> dueOn(List<Assignment> all, long midnight, long firing,
                                          java.util.Set<String> quiet) {
        List<Assignment> due = new ArrayList<>();
        long end = Dates.addDays(midnight, 1);
        for (Assignment item : all) {
            if (item.due == null || item.done || quiet.contains(item.courseId)) continue;
            long when = item.due.longValue();
            if (when >= midnight && when < end && when > firing) due.add(item);
        }
        return due;
    }

    /** "Reminder for Wednesday" / "Module 8 Reading, due Oct 21". */
    public static String title(Next next) {
        return "Reminder for " + Dates.longDay(next.work.get(0).due.longValue()).split(",")[0];
    }

    public static String text(Next next) {
        Assignment first = next.work.get(0);
        String line = first.title + ", due " + Dates.shortDay(first.due.longValue()).split(", ")[1];
        if (next.work.size() > 1) line += " and " + (next.work.size() - 1) + " more";
        return line;
    }
}
