package com.hbsa.xp3;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dates and times a caption mentions ("Friday, Oct 31 at 6pm",
 * "10/31", "7-9 PM"), for marking them in the caption and for finding the
 * club's next event.
 *
 * A date without a year is taken in the year of the post, or the next one if
 * that would put it well before the post. A weekday on its own ("this
 * Thursday") is the first such day on or after the post.
 */
public final class Dates {

    private static final String MONTH =
        "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
        + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?";
    private static final String DAY = "(3[01]|[12][0-9]|0?[1-9])(?:st|nd|rd|th)?";
    private static final String WEEKDAY = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)";
    private static final String CLOCK = "(1[0-2]|0?[1-9])(?::([0-5][0-9]))?";
    private static final String MERIDIEM = "([ap])\\.?\\s?m\\b\\.?";

    private static final Pattern MONTH_DAY = Pattern.compile(
        "\\b" + MONTH + "\\s+" + DAY + "\\b(?:,?\\s+(20[0-9]{2})\\b)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAY_MONTH = Pattern.compile(
        "\\b" + DAY + "(?:\\s+of)?\\s+" + MONTH + "(?![a-z])(?:,?\\s+(20[0-9]{2})\\b)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC = Pattern.compile(
        "(?<![0-9/$.])(1[0-2]|0?[1-9])/(3[01]|[12][0-9]|0?[1-9])(?:/(20[0-9]{2}|[0-9]{2}))?(?![0-9/])");
    private static final Pattern WEEKDAYS = Pattern.compile("\\b" + WEEKDAY + "\\b", Pattern.CASE_INSENSITIVE);
    /** "6pm", "6:30 PM", "7-9pm", "7pm - 9pm", "noon". */
    private static final Pattern TIME = Pattern.compile(
        "\\b" + CLOCK + "(?:\\s*" + MERIDIEM + ")?\\s*(?:-|–|—|to)\\s*" + CLOCK + "\\s*" + MERIDIEM
        + "|\\b" + CLOCK + "\\s*" + MERIDIEM
        + "|\\bnoon\\b",
        Pattern.CASE_INSENSITIVE);

    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final String[] DAYS = {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};
    private static final long DAY_MS = 86400000L;

    private Dates() {}

    /** A stretch of a caption that names a day, a time, or both run together. */
    public static final class Mention {
        public final int start;
        public final int end;
        /** Local midnight of the day named, in epoch milliseconds, or -1 for a time alone. */
        public final long day;
        /** Minutes after midnight, or -1 for a day alone. */
        public final int minutes;
        /** Whether the day was written out (a month and day), not just a weekday. */
        public final boolean exact;

        Mention(int start, int end, long day, int minutes, boolean exact) {
            this.start = start;
            this.end = end;
            this.day = day;
            this.minutes = minutes;
            this.exact = exact;
        }
    }

    /** An upcoming event, as the gold card on the list shows it. */
    public static final class Event {
        public final Post post;
        public final long day;
        public final int minutes;

        Event(Post post, long day, int minutes) {
            this.post = post;
            this.day = day;
            this.minutes = minutes;
        }
    }

    /** Every date and time in `caption`, in order, a weekday next to its date counted as one. */
    public static List<Mention> find(String caption, long postedAt, TimeZone zone) {
        List<Mention> found = new ArrayList<Mention>();
        long posted = midnight(postedAt, zone);
        Matcher m = MONTH_DAY.matcher(caption);
        while (m.find()) {
            add(found, m.start(), m.end(), day(posted, zone, month(m.group(1)), Integer.parseInt(m.group(2)), year(m.group(3))), true);
        }
        m = DAY_MONTH.matcher(caption);
        while (m.find()) {
            add(found, m.start(), m.end(), day(posted, zone, month(m.group(2)), Integer.parseInt(m.group(1)), year(m.group(3))), true);
        }
        m = NUMERIC.matcher(caption);
        while (m.find()) {
            add(found, m.start(), m.end(), day(posted, zone, Integer.parseInt(m.group(1)) - 1, Integer.parseInt(m.group(2)), year(m.group(3))), true);
        }
        m = WEEKDAYS.matcher(caption);
        while (m.find()) {
            add(found, m.start(), m.end(), weekday(posted, zone, m.group(1).toLowerCase(Locale.US)), false);
        }
        m = TIME.matcher(caption);
        while (m.find()) {
            int minutes = minutes(m);
            if (minutes >= 0) addTime(found, m.start(), m.end(), minutes);
        }
        sort(found);
        return merge(caption, found);
    }

    /**
     * The soonest event, from all the posts, that hasn't passed: a written-out
     * date is trusted over a weekday on its own, which is used only when a
     * caption has no other date.
     */
    public static Event next(List<Post> posts, long now, TimeZone zone) {
        long today = midnight(now, zone);
        Event best = null;
        for (Post post : posts) {
            List<Mention> mentions = find(post.caption, post.postedAt, zone);
            boolean anyExact = false;
            for (Mention mention : mentions) anyExact |= mention.exact;
            for (Mention mention : mentions) {
                if (mention.day < today || anyExact && !mention.exact) continue;
                // Nothing more than a few weeks after the post: "Oct 3" in a
                // recap is last year's, not next year's.
                if (mention.day > midnight(post.postedAt, zone) + 120 * DAY_MS) continue;
                int minutes = mention.minutes >= 0 ? mention.minutes : timeNear(mentions, mention);
                if (best == null || mention.day < best.day
                    || mention.day == best.day && best.minutes < 0 && minutes >= 0) {
                    best = new Event(post, mention.day, minutes);
                }
            }
        }
        return best;
    }

    /** The time mentioned closest after a date, or else just before it. */
    private static int timeNear(List<Mention> mentions, Mention date) {
        int best = -1;
        int distance = Integer.MAX_VALUE;
        for (Mention m : mentions) {
            if (m.day >= 0 || m.minutes < 0) continue;
            int gap = m.start >= date.end ? m.start - date.end : (date.start - m.end) * 3;
            if (gap >= 0 && gap < distance && gap <= 120) {
                distance = gap;
                best = m.minutes;
            }
        }
        return best;
    }

    /** "6 PM", "6:30 PM", "12 PM". */
    public static String clock(int minutes) {
        int hour = minutes / 60;
        int minute = minutes % 60;
        int twelve = hour % 12 == 0 ? 12 : hour % 12;
        return twelve + (minute == 0 ? "" : String.format(Locale.US, ":%02d", minute)) + (hour < 12 ? " AM" : " PM");
    }

    /** When a post went up: "just now", "5 min ago", "3 h ago", "Yesterday", "4 days ago", "Oct 14". */
    public static String ago(long posted, long now, TimeZone zone) {
        long minutes = (now - posted) / 60000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        if (minutes < 24 * 60) return (minutes / 60) + " h ago";
        int days = daysBetween(midnight(posted, zone), midnight(now, zone));
        if (days <= 1) return "Yesterday";
        if (days < 7) return days + " days ago";
        return date(posted, now, zone);
    }

    /** "Oct 14", with the year when it isn't this one. */
    public static String date(long time, long now, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(now);
        int thisYear = c.get(Calendar.YEAR);
        c.setTimeInMillis(time);
        String out = c.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.US) + " " + c.get(Calendar.DAY_OF_MONTH);
        return c.get(Calendar.YEAR) == thisYear ? out : out + ", " + c.get(Calendar.YEAR);
    }

    /** When an event is, as the card says it: "Today · 6 PM", "Tomorrow", "Fri 6 PM · in 8 days". */
    public static String when(Event event, long now, TimeZone zone) {
        int days = daysBetween(midnight(now, zone), event.day);
        String time = event.minutes >= 0 ? clock(event.minutes) : null;
        if (days == 0) return time == null ? "Today" : "Today · " + time;
        if (days == 1) return time == null ? "Tomorrow" : "Tomorrow · " + time;
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(event.day);
        String day = c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US);
        return (time == null ? day : day + " " + time) + " · in " + days + " days";
    }

    /** "OCT" and "31", for the tear-off calendar page on the card. */
    public static String[] page(long day, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(day);
        return new String[] {
            c.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.US).toUpperCase(Locale.US),
            String.valueOf(c.get(Calendar.DAY_OF_MONTH)),
        };
    }

    /** A caption's first line, cut at the end of its first sentence: the event's name. */
    public static String title(String caption) {
        for (String line : caption.split("\n")) {
            String text = line.trim();
            if (text.isEmpty()) continue;
            Matcher end = Pattern.compile("[.!?](\\s|$)").matcher(text);
            if (end.find() && end.start() >= 8) text = text.substring(0, end.start() + 1);
            return text;
        }
        return "";
    }

    /** Local midnight on the day of `time`. */
    public static long midnight(long time, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(time);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Whole days from local `today` to local `day` (both midnights). */
    public static int daysBetween(long today, long day) {
        return (int) Math.round((day - today) / (double) DAY_MS);
    }

    private static int month(String name) {
        String key = name.toLowerCase(Locale.US).substring(0, 3);
        for (int i = 0; i < MONTHS.length; i++) if (MONTHS[i].equals(key)) return i;
        return -1;
    }

    private static int year(String text) {
        if (text == null) return -1;
        int year = Integer.parseInt(text);
        return year < 100 ? 2000 + year : year;
    }

    /** Midnight of a month and day, in `year` or else the year that suits the post. */
    private static long day(long posted, TimeZone zone, int month, int dayOfMonth, int year) {
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(posted);
        c.setLenient(false);
        if (year > 0) c.set(Calendar.YEAR, year);
        c.set(Calendar.MONTH, month);
        c.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        try {
            long day = c.getTimeInMillis();
            if (year < 0 && day < posted - 45 * DAY_MS) {
                c.add(Calendar.YEAR, 1);
                day = c.getTimeInMillis();
            }
            return day;
        } catch (IllegalArgumentException e) {
            return -1; // February 30th and the like
        }
    }

    private static long weekday(long posted, TimeZone zone, String name) {
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(posted);
        int want = 0;
        for (int i = 0; i < DAYS.length; i++) if (DAYS[i].equals(name)) want = i + 1;
        while (c.get(Calendar.DAY_OF_WEEK) != want) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** The start of a time or range, in minutes after midnight. */
    private static int minutes(Matcher m) {
        String text = m.group().toLowerCase(Locale.US);
        if (text.equals("noon")) return 12 * 60;
        int hour;
        String minute;
        String meridiem;
        if (m.group(1) != null) {
            // A range takes the first hour's half of the day from its own
            // "am"/"pm", or from the end's when it has none ("7-9pm").
            hour = Integer.parseInt(m.group(1));
            minute = m.group(2);
            meridiem = m.group(3) != null ? m.group(3) : m.group(6);
            if (m.group(3) == null && Integer.parseInt(m.group(4)) < hour && "p".equalsIgnoreCase(m.group(6))) {
                meridiem = "a"; // "11-1pm" starts in the morning
                if (hour == 12) meridiem = "p";
            }
        } else {
            hour = Integer.parseInt(m.group(7));
            minute = m.group(8);
            meridiem = m.group(9);
        }
        hour %= 12;
        if ("p".equalsIgnoreCase(meridiem)) hour += 12;
        return hour * 60 + (minute == null ? 0 : Integer.parseInt(minute));
    }

    private static void add(List<Mention> found, int start, int end, long day, boolean exact) {
        if (day < 0) return;
        for (Mention m : found) if (start < m.end && end > m.start) return;
        found.add(new Mention(start, end, day, -1, exact));
    }

    private static void addTime(List<Mention> found, int start, int end, int minutes) {
        for (Mention m : found) if (start < m.end && end > m.start) return;
        found.add(new Mention(start, end, -1, minutes, false));
    }

    private static void sort(List<Mention> found) {
        java.util.Collections.sort(found, (a, b) -> Integer.compare(a.start, b.start));
    }

    /**
     * A weekday right before its date ("Friday, Oct 31") becomes one
     * mention, the date's; so does a date right before a time with only
     * "at", "@" or a comma between them.
     */
    private static List<Mention> merge(String caption, List<Mention> found) {
        List<Mention> out = new ArrayList<Mention>();
        for (Mention m : found) {
            Mention last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.day >= 0 && last.minutes < 0) {
                String between = caption.substring(last.end, m.start);
                if (m.day >= 0 && m.exact && !last.exact && between.matches("[\\s,]*")) {
                    out.set(out.size() - 1, new Mention(last.start, m.end, m.day, -1, true));
                    continue;
                }
                if (m.day < 0 && between.matches("(?i)[\\s,]*(?:at|@|from|,)?[\\s,]*")) {
                    out.set(out.size() - 1, new Mention(last.start, m.end, last.day, m.minutes, last.exact));
                    continue;
                }
            }
            out.add(m);
        }
        return out;
    }
}
