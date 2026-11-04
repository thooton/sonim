package com.musica.xp3;

import java.util.Locale;

/** Pixel and colour work for covers and backgrounds, on plain ARGB arrays. */
public final class Pixels {

    private Pixels() {}

    /**
     * Blurs an ARGB image in place: three box blurs of `radius`, which come
     * close to a Gaussian. Edges repeat their outermost pixel.
     */
    public static void blur(int[] px, int w, int h, int radius) {
        if (radius < 1 || w < 1 || h < 1) return;
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
    }

    private static void box(int[] src, int[] dst, int w, int h, int r, boolean rows) {
        int lines = rows ? h : w;
        int len = rows ? w : h;
        int step = rows ? 1 : w;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = rows ? line * w : line;
            int a = 0, red = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[base + clamp(i, 0, len - 1) * step];
                a += c >>> 24; red += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = ((a / div) << 24) | ((red / div) << 16) | ((g / div) << 8) | (b / div);
                int out = src[base + clamp(i - r, 0, len - 1) * step];
                int in = src[base + clamp(i + r + 1, 0, len - 1) * step];
                a += (in >>> 24) - (out >>> 24);
                red += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** Relative luminance of a colour, 0 (black) to 1 (white). */
    public static double luma(int c) {
        return (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF)) / 255.0;
    }

    /** The mean luminance of an image. */
    public static double meanLuma(int[] px) {
        if (px.length == 0) return 0;
        double sum = 0;
        for (int c : px) sum += luma(c);
        return sum / px.length;
    }

    /**
     * How dark a veil the player lays over a blurred cover so white text
     * reads on it: Apple's light 15%, more on a pale cover.
     */
    public static int veilAlpha(double luma) {
        double a = 0.15 + Math.max(0, luma - 0.45) * 0.9;
        return (int) Math.round(Math.min(0.6, a) * 255);
    }

    /**
     * Two colours for a drawn cover, from a title: the same title always
     * gets the same pair, a deep one and a lighter one of a neighbouring hue.
     */
    public static int[] coverColors(String title) {
        int h = 0;
        for (char ch : Names.fold(title).toCharArray()) h = h * 31 + ch;
        float hue = ((h & 0x7FFFFFFF) % 360);
        int deep = hsv(hue, 0.72f, 0.62f);
        int light = hsv((hue + 28) % 360, 0.58f, 0.93f);
        return new int[] {light, deep};
    }

    /** An opaque colour from hue (0 to 360), saturation and value (0 to 1). */
    public static int hsv(float hue, float sat, float val) {
        float c = val * sat;
        float x = c * (1 - Math.abs((hue / 60f) % 2 - 1));
        float m = val - c;
        float r, g, b;
        if (hue < 60) { r = c; g = x; b = 0; }
        else if (hue < 120) { r = x; g = c; b = 0; }
        else if (hue < 180) { r = 0; g = c; b = x; }
        else if (hue < 240) { r = 0; g = x; b = c; }
        else if (hue < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return 0xFF000000 | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    /** "1:05", or "1:02:05" past the hour. */
    public static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600
            ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
