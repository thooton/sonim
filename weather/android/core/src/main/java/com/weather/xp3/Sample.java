package com.weather.xp3;

import java.util.ArrayList;
import java.util.List;

/**
 * Made-up forecasts, the mockups' own, for drawing the screen before (and
 * without) the weather service: `adb shell am start -e sample rain`.
 */
public final class Sample {

    private Sample() {}

    public static final String[] NAMES = {
        "clear-day", "partly-cloudy-day", "cloudy-day", "rain", "thunderstorm", "snow",
        "fog", "wind", "clear-night", "partly-cloudy-night",
    };

    /** A forecast for `name` (one of NAMES), its hours starting this hour. */
    public static Forecast of(String name, long nowMillis, int utcOffset) {
        switch (name) {
            case "partly-cloudy-day":
                return make("San Diego, CA", name, 72, 72, nowMillis, utcOffset,
                    new String[] {"partly-cloudy-day", "partly-cloudy-day", "clear-day", "clear-day", "partly-cloudy-day", "partly-cloudy-day", "clear-day", "clear-night"},
                    new int[] {0, 1, 2, 3, 3, 2, 1, -1, -3, -5, -6, -7}, 0, null, null);
            case "cloudy-day":
                return make("Portland, OR", name, 58, 56, nowMillis, utcOffset, new String[] {"cloudy-day"},
                    new int[] {0, 1, 2, 2, 3, 2, 1, 0, -1, -2, -3, -3}, -2, null, null);
            case "rain":
                return make("Seattle, WA", name, 44, 41, nowMillis, utcOffset,
                    new String[] {"rain", "rain", "rain", "rain", "cloudy-day", "cloudy-day"},
                    new int[] {0, 0, 1, 1, 2, 2, 1, 0, 0, -1, -1, -2}, -3,
                    new int[] {90, 90, 80, 70, 50, 40, 30, 30, 40, 50, 60, 60},
                    new double[] {.12, .09, .06, .04, .02, .01, 0, 0, .01, .03, .05, .06});
            case "thunderstorm":
                return make("Tampa, FL", name, 79, 84, nowMillis, utcOffset,
                    new String[] {"thunderstorm", "thunderstorm", "rain", "rain", "cloudy-day", "partly-cloudy-day"},
                    new int[] {0, -1, -2, -2, -1, 0, 1, 1, 0, -1, -2, -3}, 3, null, null);
            case "snow":
                return make("Jackson, WY", name, 24, 15, nowMillis, utcOffset, new String[] {"snow"},
                    new int[] {0, 0, -1, -2, -3, -3, -4, -5, -5, -6, -6, -7}, -9, null, null);
            case "fog":
                return make("San Francisco, CA", name, 57, 57, nowMillis, utcOffset,
                    new String[] {"fog", "fog", "fog", "cloudy-day", "partly-cloudy-day", "partly-cloudy-day"},
                    new int[] {0, 1, 2, 3, 4, 4, 3, 2, 0, -1, -2, -2}, 0, null, null);
            case "wind":
                return make("Chicago, IL", name, 61, 55, nowMillis, utcOffset,
                    new String[] {"wind", "wind", "wind", "partly-cloudy-day"},
                    new int[] {0, 1, 1, 2, 2, 1, 0, -1, -2, -3, -4, -5}, -6, null, null);
            case "clear-night":
                return make("New York, NY", name, 37, 33, nowMillis, utcOffset, new String[] {"clear-night"},
                    new int[] {0, -1, -1, -2, -2, -3, -3, -4, -4, -3, -2, 0}, -4, null, null);
            case "partly-cloudy-night":
                return make("Denver, CO", name, 48, 46, nowMillis, utcOffset,
                    new String[] {"partly-cloudy-night", "partly-cloudy-night", "clear-night"},
                    new int[] {0, -1, -2, -3, -4, -5, -6, -6, -7, -6, -4, -1}, 3, null, null);
            default:
                return make("Miami, FL", "clear-day", 86, 91, nowMillis, utcOffset,
                    new String[] {"clear-day", "clear-day", "partly-cloudy-day", "partly-cloudy-day", "clear-day", "clear-day", "partly-cloudy-day", "clear-day", "clear-day", "clear-night", "clear-night", "clear-night"},
                    new int[] {-3, 0, 2, 4, 5, 6, 5, 3, 0, -3, -5, -7}, 3, null, null);
        }
    }

    private static Forecast make(String place, String cond, int temp, int feels, long nowMillis, int utcOffset,
                                 String[] conds, int[] curve, int feelsOff, int[] pop, double[] precip) {
        int[] pops = pop != null ? pop : new int[] {0, 0, 5, 10, 10, 20, 30, 20, 10, 5, 0, 0};
        double[] amounts = precip != null ? precip : new double[] {0, 0, 0, 0, 0, .02, .05, .02, 0, 0, 0, 0};
        int[] wind = {6, 8, 9, 11, 12, 13, 12, 10, 8, 7, 6, 5};
        int[] hum = {72, 68, 63, 58, 55, 53, 55, 60, 66, 71, 75, 78};
        int[] uv = {5, 7, 8, 9, 9, 8, 6, 4, 2, 1, 0, 0};
        int[] cloud = {10, 10, 20, 30, 35, 40, 35, 30, 20, 10, 5, 5};
        double[] pres = {30.02, 30.02, 30.01, 30.0, 29.98, 29.97, 29.96, 29.96, 29.97, 29.98, 29.99, 30.0};
        int[] vis = {10, 10, 10, 10, 9, 8, 7, 8, 10, 10, 10, 10};
        long hour = nowMillis / 1000 / 3600 * 3600;
        List<Forecast.Hour> hours = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            int k = i % 12;
            Forecast.Hour h = new Forecast.Hour(hour + i * 3600L, conds[Math.min(i, conds.length - 1)]);
            int base = temp - curve[0];
            h.temp = base + curve[k] - (i / 12) % 2;
            h.feels = h.temp + feelsOff - (k > 6 ? 2 : 0);
            h.pop = pops[k];
            h.precip = amounts[k];
            h.wind = wind[k];
            h.direction = 80 + k * 9;
            h.humidity = hum[k];
            h.uv = uv[k];
            h.cloud = cloud[k];
            h.dew = base - 12 + Math.round(curve[k] / 3.0);
            h.pressure = pres[k];
            h.visibility = vis[k];
            hours.add(h);
        }
        // Ten days round the city's temperature, starting with its own weather.
        String day = Condition.daytime(cond);
        String[] seq = {day, day, "partly-cloudy-day", "cloudy-day", "rain", "partly-cloudy-day", "clear-day", "clear-day", "cloudy-day", "partly-cloudy-day"};
        int[] swing = {0, -3, 2, 4, -2, -5, 1, 3, 0, -1};
        long midnight = (nowMillis / 1000 + utcOffset) / 86400 * 86400 - utcOffset;
        List<Forecast.Day> days = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String c = seq[i];
            int chance = c.equals("rain") ? 80 : c.equals("thunderstorm") ? 70 : c.equals("snow") ? 70 : c.equals("sleet") ? 60 : c.equals("cloudy-day") || c.equals("fog") ? 10 : 0;
            days.add(new Forecast.Day(midnight + i * 86400L, c, temp - 10 + swing[i], temp + 4 + swing[i] + i % 3, chance));
        }
        return new Forecast(place, utcOffset, nowMillis, new Forecast.Now(cond, temp, feels), hours, days);
    }
}
