package com.weather.xp3;

/**
 * CARROT's colours, read from its Android app (2.5.2): the card behind the
 * sky for each condition, its temperature scale and the blue it marks rain
 * with. ARGB ints, as android.graphics.Color takes them.
 */
public final class Colors {

    private Colors() {}

    public static final int CARD_DAY = 0xFF0077F8;
    public static final int CARD_DAY_CLOUDY = 0xFF0045A3;
    public static final int CARD_NIGHT = 0xFF001986;
    public static final int CARD_RAINY = 0xFF001986;
    public static final int CARD_SNOW = 0xFF61A3DE;
    public static final int CARD_FOG = 0xFF44C5EE;
    public static final int CARD_WIND = 0xFF0045A2;

    public static final int BLUE = 0xFF0B84FE;

    /** °F at each step, and its colour. */
    private static final int[] STEPS = {-60, -50, -40, -30, -20, -10, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130};
    private static final int[] SCALE = {
        0xFF8A01C1, 0xFF6F027D, 0xFFA50099, 0xFFEC02DD, 0xFFF589FF, 0xFFD3A6FF,
        0xFF9348ED, 0xFF6F5BF0, 0xFF9BC6FF, 0xFF18A5FC, 0xFF0CB1CC, 0xFF68FC57,
        0xFFF7D505, 0xFFE06F00, 0xFFF23400, 0xFFD64600, 0xFFC70003, 0xFFD3064E,
        0xFFFF539B, 0xFFFFCBB0,
    };

    /** A temperature's colour on CARROT's scale, blended between its steps. */
    public static int temp(double f) {
        if (f <= STEPS[0]) return SCALE[0];
        for (int i = 1; i < STEPS.length; i++) {
            if (f <= STEPS[i]) {
                return mix(SCALE[i - 1], SCALE[i], (f - STEPS[i - 1]) / (STEPS[i] - STEPS[i - 1]));
            }
        }
        return SCALE[SCALE.length - 1];
    }

    public static int uv(double index) {
        if (index < 3) return 0xFF34C759;
        if (index < 6) return 0xFFFFCC00;
        if (index < 8) return 0xFFFF9500;
        if (index < 11) return 0xFFFF3B30;
        return 0xFFAF52DE;
    }

    public static int mix(int a, int b, double t) {
        int out = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int x = (a >> shift) & 0xFF, y = (b >> shift) & 0xFF;
            out |= ((int) Math.round(x + (y - x) * t) & 0xFF) << shift;
        }
        return out;
    }
}
