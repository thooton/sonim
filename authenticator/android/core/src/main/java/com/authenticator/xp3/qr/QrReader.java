package com.authenticator.xp3.qr;

import java.util.List;

/**
 * Reads the QR code in a picture. The picture comes in as luminance, one
 * value from 0 (black) to 255 (white) per pixel, row by row.
 *
 * A photo can fool any one way of looking, so several are tried, cheapest
 * first: local then global thresholds, a quick then a thorough scan for the
 * finder patterns, the code as printed then mirrored, and last light on dark.
 */
public final class QrReader {

    private QrReader() {}

    /** The text the QR code holds, or null when no code could be read. */
    public static String read(int[] luminance, int width, int height) {
        if (width <= 0 || height <= 0 || luminance.length < width * height) return null;
        String text = attempt(luminance, width, height);
        if (text != null) return text;
        int[] inverted = new int[width * height];
        for (int i = 0; i < inverted.length; i++) inverted[i] = 255 - luminance[i];
        return attempt(inverted, width, height);
    }

    private static String attempt(int[] luminance, int width, int height) {
        BitMatrix local = Binarizer.local(luminance, width, height);
        String text = scan(local);
        if (text != null) return text;
        return scan(Binarizer.global(luminance, width, height));
    }

    private static String scan(BitMatrix image) {
        for (boolean hard : new boolean[] {false, true}) {
            Finder.Pattern[] patterns = new Finder(image).find(hard);
            if (patterns == null) continue;
            List<BitMatrix> grids = new Detector(image).grids(patterns);
            for (BitMatrix grid : grids) {
                String text = Decoder.decode(grid);
                if (text == null) text = Decoder.decode(grid.transposed());
                if (text != null) return text;
            }
        }
        return null;
    }
}
