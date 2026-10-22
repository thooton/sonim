package com.weather.xp3;

import java.util.Locale;

/** US or metric, and how each kind of value is written in it. */
public final class Units {

    public static final Units US = new Units(false);
    public static final Units METRIC = new Units(true);

    public final boolean metric;

    private Units(boolean metric) {
        this.metric = metric;
    }

    public static Units of(boolean metric) {
        return metric ? METRIC : US;
    }

    /** What the menu says the units are. */
    public String label() {
        return metric ? "°C, km/h" : "°F, mph";
    }

    public int temp(double f) {
        return (int) Math.round(metric ? (f - 32) * 5 / 9 : f);
    }

    public String degrees(double f) {
        return temp(f) + "°";
    }

    public int speed(double mph) {
        return (int) Math.round(metric ? mph * 1.609344 : mph);
    }

    public String speedUnit() {
        return metric ? "km/h" : "mph";
    }

    /** An hour's rain: inches to two places without the leading 0, or millimetres. */
    public String precip(double inches) {
        if (metric) {
            double mm = inches * 25.4;
            if (mm == 0) return "0";
            return mm < 10 ? String.format(Locale.US, "%.1f", mm) : String.valueOf(Math.round(mm));
        }
        if (inches == 0) return "0\"";
        String s = String.format(Locale.US, "%.2f", inches);
        return (s.startsWith("0") ? s.substring(1) : s) + "\"";
    }

    public String pressure(double inHg) {
        return metric ? String.valueOf(Math.round(inHg * 33.8639)) : String.format(Locale.US, "%.2f", inHg);
    }

    public String distance(double miles) {
        if (metric) {
            double km = miles * 1.609344;
            return (km < 10 ? String.format(Locale.US, "%.1f", km) : String.valueOf(Math.round(km))) + "km";
        }
        return (miles < 10 && miles != Math.floor(miles) ? String.format(Locale.US, "%.1f", miles) : String.valueOf(Math.round(miles))) + "mi";
    }
}
