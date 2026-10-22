package com.weather.xp3;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * A place's weather: now, by the hour and by the day.
 *
 * Every value is kept in US units (°F, mph, inches, inHg, miles), whatever the
 * phone shows: CARROT's temperature colours are a °F scale, and `Units` turns
 * the rest into metric only when it is written out.
 */
public final class Forecast {

    /** Where, as shown at the top of the screen. */
    public final String place;
    /** Seconds east of UTC at the place, so hours read as the place's own. */
    public final int utcOffset;
    /** When it was fetched, epoch milliseconds. */
    public final long fetched;
    public final Now now;
    public final List<Hour> hours;
    public final List<Day> days;

    public Forecast(String place, int utcOffset, long fetched, Now now, List<Hour> hours, List<Day> days) {
        this.place = place;
        this.utcOffset = utcOffset;
        this.fetched = fetched;
        this.now = now;
        this.hours = hours;
        this.days = days;
    }

    public static final class Now {
        public final String condition;
        public final double temp, feels;

        public Now(String condition, double temp, double feels) {
            this.condition = condition;
            this.temp = temp;
            this.feels = feels;
        }
    }

    public static final class Hour {
        /** The start of the hour, epoch seconds. */
        public final long time;
        public final String condition;
        public double temp, feels, dew;
        /** Percent. */
        public double pop, humidity, cloud;
        /** Inches in the hour. */
        public double precip;
        /** mph, and where it blows from in degrees. */
        public double wind, gust, direction;
        public double uv;
        /** inHg and miles. */
        public double pressure, visibility;

        public Hour(long time, String condition) {
            this.time = time;
            this.condition = condition;
        }
    }

    public static final class Day {
        /** Local midnight at the place, epoch seconds. */
        public final long time;
        public final String condition;
        public final double low, high, pop;

        public Day(long time, String condition, double low, double high, double pop) {
            this.time = time;
            this.condition = condition;
            this.low = low;
            this.high = high;
            this.pop = pop;
        }
    }

    /** The hours from the one we are in now on. */
    public List<Hour> hoursFrom(long nowMillis) {
        List<Hour> out = new ArrayList<>();
        long now = nowMillis / 1000;
        for (Hour h : hours) {
            if (h.time + 3600 > now) out.add(h);
        }
        return out;
    }

    /** "10" and "AM": an hour's label, the suffix drawn smaller. */
    public String[] hourLabel(long time, boolean clock24) {
        Calendar c = local(time);
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (clock24) return new String[] {String.format(java.util.Locale.US, "%02d", h), ""};
        int twelve = h % 12 == 0 ? 12 : h % 12;
        return new String[] {String.valueOf(twelve), h < 12 ? "AM" : "PM"};
    }

    private static final String[] DAY_NAMES = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    /** "TODAY", then "SAT", "SUN"… */
    public String dayLabel(Day day, long nowMillis) {
        Calendar today = local(nowMillis / 1000);
        Calendar that = local(day.time + 12 * 3600);
        if (today.get(Calendar.YEAR) == that.get(Calendar.YEAR)
            && today.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)) return "TODAY";
        return DAY_NAMES[that.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /** The days from today on. */
    public List<Day> daysFrom(long nowMillis) {
        List<Day> out = new ArrayList<>();
        long now = nowMillis / 1000;
        for (Day d : days) {
            if (d.time + 24 * 3600 > now) out.add(d);
        }
        return out;
    }

    private Calendar local(long epochSeconds) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis((epochSeconds + utcOffset) * 1000L);
        return c;
    }
}
