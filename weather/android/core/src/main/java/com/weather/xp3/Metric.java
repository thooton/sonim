package com.weather.xp3;

/**
 * What the hourly forecast shows under each hour, as CARROT's chips on iOS
 * choose it: Menu's Hourly data, or * and #.
 */
public enum Metric {
    TEMP("Temperature", "Temp", Kind.LINE),
    FEELS("Feels like", "Feels like", Kind.LINE),
    POP("Precip chance", "Precip chance", Kind.BAR),
    PRECIP("Precip amount", "Precip", Kind.BAR),
    WIND("Wind", "Wind", Kind.WIND),
    HUMIDITY("Humidity", "Humidity", Kind.BAR),
    UV("UV index", "UV index", Kind.BAR),
    CLOUD("Cloud cover", "Cloud cover", Kind.BAR),
    DEW("Dew point", "Dew point", Kind.LINE),
    PRESSURE("Pressure", "Pressure", Kind.LINE),
    VISIBILITY("Visibility", "Visibility", Kind.BAR);

    /** A line through the values, bars under them, or wind's arrows. */
    public enum Kind { LINE, BAR, WIND }

    public final String label, chip;
    public final Kind kind;

    Metric(String label, String chip, Kind kind) {
        this.label = label;
        this.chip = chip;
        this.kind = kind;
    }

    public double value(Forecast.Hour h) {
        switch (this) {
            case TEMP: return h.temp;
            case FEELS: return h.feels;
            case POP: return h.pop;
            case PRECIP: return h.precip;
            case WIND: return h.wind;
            case HUMIDITY: return h.humidity;
            case UV: return h.uv;
            case CLOUD: return h.cloud;
            case DEW: return h.dew;
            case PRESSURE: return h.pressure;
            default: return h.visibility;
        }
    }

    /** A bar's full height. */
    public double max() {
        switch (this) {
            case PRECIP: return 0.3;
            case UV: return 11;
            case VISIBILITY: return 10;
            default: return 100;
        }
    }

    public String format(double v, Units u) {
        switch (this) {
            case TEMP: case FEELS: case DEW: return u.degrees(v);
            case POP: case HUMIDITY: case CLOUD: return Math.round(v) + "%";
            case PRECIP: return u.precip(v);
            case WIND: return String.valueOf(u.speed(v));
            case UV: return String.valueOf(Math.round(v));
            case PRESSURE: return u.pressure(v);
            default: return u.distance(v);
        }
    }

    /**
     * What a line is drawn through: temperatures in the units shown, so a
     * line through °C values bends as the labels on it do.
     */
    public double plotted(double v, Units u) {
        switch (this) {
            case TEMP: case FEELS: case DEW: return u.temp(v);
            default: return v;
        }
    }

    public Metric next() {
        Metric[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public Metric previous() {
        Metric[] all = values();
        return all[(ordinal() + all.length - 1) % all.length];
    }

    public static Metric named(String name) {
        for (Metric m : values()) if (m.name().equals(name)) return m;
        return TEMP;
    }
}
