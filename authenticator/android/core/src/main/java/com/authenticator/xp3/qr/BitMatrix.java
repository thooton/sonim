package com.authenticator.xp3.qr;

/** A grid of on (black) and off (white) cells, addressed as (x, y). */
final class BitMatrix {

    final int width;
    final int height;
    private final boolean[] bits;

    BitMatrix(int width, int height) {
        this.width = width;
        this.height = height;
        this.bits = new boolean[width * height];
    }

    boolean get(int x, int y) {
        return bits[y * width + x];
    }

    void set(int x, int y, boolean value) {
        bits[y * width + x] = value;
    }

    void setRegion(int left, int top, int regionWidth, int regionHeight) {
        for (int y = top; y < top + regionHeight; y++) {
            for (int x = left; x < left + regionWidth; x++) set(x, y, true);
        }
    }

    /** The same symbol read across its other diagonal, for codes printed mirrored. */
    BitMatrix transposed() {
        BitMatrix out = new BitMatrix(height, width);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) out.set(y, x, get(x, y));
        }
        return out;
    }
}
