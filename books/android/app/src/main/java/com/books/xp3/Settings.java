package com.books.xp3;

import android.content.Context;
import android.content.SharedPreferences;

/** How the page looks, kept across books and launches. */
final class Settings {

    static final int MIN_SIZE = 11;
    static final int MAX_SIZE = 26;
    static final String[] SPACING_NAMES = {"Compact", "Normal", "Relaxed"};
    static final float[] SPACING = {1.12f, 1.28f, 1.48f};
    static final String[] MARGIN_NAMES = {"Narrow", "Normal", "Wide"};
    static final int[] MARGINS = {6, 11, 18};

    int font = Fonts.CHARTER;
    /** Text size in pixels at the phone's mdpi density, which is to say dp. */
    int size = 15;
    boolean nightShift;
    boolean justify = true;
    int spacing = 1;
    int margins = 1;

    private final SharedPreferences prefs;

    Settings(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        font = clamp(prefs.getInt("font", font), 0, Fonts.count() - 1);
        size = clamp(prefs.getInt("size", size), MIN_SIZE, MAX_SIZE);
        nightShift = prefs.getBoolean("nightShift", nightShift);
        justify = prefs.getBoolean("justify", justify);
        spacing = clamp(prefs.getInt("spacing", spacing), 0, SPACING.length - 1);
        margins = clamp(prefs.getInt("margins", margins), 0, MARGINS.length - 1);
    }

    void save() {
        prefs.edit()
            .putInt("font", font)
            .putInt("size", size)
            .putBoolean("nightShift", nightShift)
            .putBoolean("justify", justify)
            .putInt("spacing", spacing)
            .putInt("margins", margins)
            .apply();
    }

    /**
     * Everything that changes where lines break. Night Shift is not in it: it
     * only recolours the page, which needs no new layout.
     */
    String layoutKey() {
        return font + "/" + size + "/" + justify + "/" + spacing + "/" + margins;
    }

    static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
