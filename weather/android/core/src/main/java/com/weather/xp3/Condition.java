package com.weather.xp3;

/**
 * The conditions the app draws, named as CARROT names its icons, and how
 * CARROT dresses the sky for each: the card's colour, the effect over it and
 * which scenery runs along its foot.
 */
public final class Condition {

    private Condition() {}

    public static final String CLEAR_DAY = "clear-day";
    public static final String CLEAR_NIGHT = "clear-night";
    public static final String PARTLY_CLOUDY_DAY = "partly-cloudy-day";
    public static final String PARTLY_CLOUDY_NIGHT = "partly-cloudy-night";
    public static final String CLOUDY_DAY = "cloudy-day";
    public static final String CLOUDY_NIGHT = "cloudy-night";
    public static final String RAIN = "rain";
    public static final String THUNDERSTORM = "thunderstorm";
    public static final String SNOW = "snow";
    public static final String SLEET = "sleet";
    public static final String FOG = "fog";
    public static final String WIND = "wind";

    public enum Effect { NONE, CLOUDS_DAY, CLOUDS_NIGHT, OVERCAST_DAY, OVERCAST_NIGHT, RAIN, SNOW, STARS, WIND }

    public enum Scenery { DAY, NIGHT, RAIN, SNOW, FOG }

    public static int card(String c) {
        switch (c) {
            case CLEAR_DAY: case PARTLY_CLOUDY_DAY: return Colors.CARD_DAY;
            case CLOUDY_DAY: return Colors.CARD_DAY_CLOUDY;
            case CLEAR_NIGHT: case PARTLY_CLOUDY_NIGHT: case CLOUDY_NIGHT: return Colors.CARD_NIGHT;
            case SNOW: case SLEET: return Colors.CARD_SNOW;
            case FOG: return Colors.CARD_FOG;
            case WIND: return Colors.CARD_WIND;
            default: return Colors.CARD_RAINY;
        }
    }

    public static Effect effect(String c) {
        switch (c) {
            case PARTLY_CLOUDY_DAY: return Effect.CLOUDS_DAY;
            case CLOUDY_DAY: return Effect.OVERCAST_DAY;
            case CLEAR_NIGHT: return Effect.STARS;
            case PARTLY_CLOUDY_NIGHT: return Effect.CLOUDS_NIGHT;
            case CLOUDY_NIGHT: return Effect.OVERCAST_NIGHT;
            case RAIN: case THUNDERSTORM: return Effect.RAIN;
            case SNOW: case SLEET: return Effect.SNOW;
            case WIND: return Effect.WIND;
            default: return Effect.NONE;
        }
    }

    public static Scenery scenery(String c) {
        switch (c) {
            case CLEAR_NIGHT: case PARTLY_CLOUDY_NIGHT: case CLOUDY_NIGHT: return Scenery.NIGHT;
            case RAIN: case THUNDERSTORM: return Scenery.RAIN;
            case SNOW: case SLEET: return Scenery.SNOW;
            case FOG: return Scenery.FOG;
            default: return Scenery.DAY;
        }
    }

    /** The daytime form, for the daily forecast. */
    public static String daytime(String c) {
        switch (c) {
            case CLEAR_NIGHT: return CLEAR_DAY;
            case PARTLY_CLOUDY_NIGHT: return PARTLY_CLOUDY_DAY;
            case CLOUDY_NIGHT: return CLOUDY_DAY;
            default: return c;
        }
    }
}
