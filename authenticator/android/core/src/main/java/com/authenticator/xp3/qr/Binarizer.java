package com.authenticator.xp3.qr;

/**
 * Turns a greyscale picture into black and white.
 *
 * A photo of a screen or a page is never evenly lit, so one threshold for the
 * whole picture loses half the code to glare or shadow. `local` judges each
 * 8x8 block against the average brightness of the 5x5 blocks around it (the
 * approach of ZXing's HybridBinarizer); `global` takes one threshold between
 * the two peaks of the histogram, for small or evenly lit pictures.
 */
final class Binarizer {

    private static final int BLOCK = 8;
    /** A block whose brightest and darkest pixels are this close is taken to be all one colour. */
    private static final int FLAT = 24;

    private Binarizer() {}

    static BitMatrix local(int[] luminance, int width, int height) {
        int columns = (width + BLOCK - 1) / BLOCK;
        int rows = (height + BLOCK - 1) / BLOCK;
        if (columns < 5 || rows < 5) return global(luminance, width, height);
        int[][] blackPoints = new int[rows][columns];
        for (int row = 0; row < rows; row++) {
            int top = Math.min(row * BLOCK, height - BLOCK);
            for (int column = 0; column < columns; column++) {
                int left = Math.min(column * BLOCK, width - BLOCK);
                int sum = 0;
                int min = 255;
                int max = 0;
                for (int y = top; y < top + BLOCK; y++) {
                    int offset = y * width;
                    for (int x = left; x < left + BLOCK; x++) {
                        int pixel = luminance[offset + x];
                        sum += pixel;
                        if (pixel < min) min = pixel;
                        if (pixel > max) max = pixel;
                    }
                }
                int average = sum / (BLOCK * BLOCK);
                if (max - min <= FLAT) {
                    // A flat block: assume it is background (white) unless
                    // its neighbours say the area is darker than it.
                    average = min / 2;
                    if (row > 0 && column > 0) {
                        int neighbours = (blackPoints[row - 1][column] + 2 * blackPoints[row][column - 1]
                            + blackPoints[row - 1][column - 1]) / 4;
                        if (min < neighbours) average = neighbours;
                    }
                }
                blackPoints[row][column] = average;
            }
        }
        BitMatrix matrix = new BitMatrix(width, height);
        for (int row = 0; row < rows; row++) {
            int top = Math.min(row * BLOCK, height - BLOCK);
            int centreRow = Math.max(2, Math.min(row, rows - 3));
            for (int column = 0; column < columns; column++) {
                int left = Math.min(column * BLOCK, width - BLOCK);
                int centreColumn = Math.max(2, Math.min(column, columns - 3));
                int sum = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    int[] around = blackPoints[centreRow + dy];
                    for (int dx = -2; dx <= 2; dx++) sum += around[centreColumn + dx];
                }
                int threshold = sum / 25;
                for (int y = top; y < top + BLOCK; y++) {
                    int offset = y * width;
                    for (int x = left; x < left + BLOCK; x++) {
                        if (luminance[offset + x] <= threshold) matrix.set(x, y, true);
                    }
                }
            }
        }
        return matrix;
    }

    static BitMatrix global(int[] luminance, int width, int height) {
        int[] histogram = new int[32];
        for (int pixel : luminance) histogram[pixel >> 3]++;
        // The tallest bucket, then the bucket farthest from it weighted by its
        // height: the two colours. The threshold is the emptiest bucket
        // between them, leaning toward the white.
        int first = 0;
        for (int i = 1; i < 32; i++) if (histogram[i] > histogram[first]) first = i;
        int second = 0;
        long secondScore = -1;
        for (int i = 0; i < 32; i++) {
            long distance = i - first;
            long score = histogram[i] * distance * distance;
            if (score > secondScore) {
                second = i;
                secondScore = score;
            }
        }
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        int threshold = (low + high) / 2;
        long bestScore = -1;
        for (int i = high - 1; i > low; i--) {
            long fromLow = i - low;
            long score = fromLow * fromLow * (high - i) * (histogram[high] - histogram[i]);
            if (score > bestScore) {
                threshold = i;
                bestScore = score;
            }
        }
        int cut = (threshold << 3) + 4;
        BitMatrix matrix = new BitMatrix(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (luminance[y * width + x] < cut) matrix.set(x, y, true);
            }
        }
        return matrix;
    }
}
