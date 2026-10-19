package com.print.xp3.raster;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * The run-length code PWG Raster and Apple Raster share. Each run of
 * identical lines (up to 256) is a repeat byte, count - 1, then the line
 * as runs of pixels: 0 to 127 means the next pixel 1 to 128 times, and 129
 * to 255 means 257 - n pixels copied as they are.
 */
final class LineCoder {

    private final int pixelBytes;
    private final byte[] line;
    private int repeats;

    LineCoder(int lineBytes, int pixelBytes) {
        this.pixelBytes = pixelBytes;
        this.line = new byte[lineBytes];
    }

    void row(OutputStream out, byte[] data, int offset) throws IOException {
        if (repeats > 0 && repeats < 256 && same(data, offset)) {
            repeats++;
            return;
        }
        flush(out);
        System.arraycopy(data, offset, line, 0, line.length);
        repeats = 1;
    }

    void flush(OutputStream out) throws IOException {
        if (repeats == 0) return;
        out.write(repeats - 1);
        encode(out, line, 0, line.length / pixelBytes, pixelBytes);
        repeats = 0;
    }

    private boolean same(byte[] data, int offset) {
        for (int i = 0; i < line.length; i++) if (line[i] != data[offset + i]) return false;
        return true;
    }

    static void encode(OutputStream out, byte[] data, int offset, int pixels, int size) throws IOException {
        int i = 0;
        while (i < pixels) {
            int run = 1;
            while (i + run < pixels && run < 128 && equal(data, offset, size, i, i + run)) run++;
            if (run > 1 || i + 1 == pixels) {
                out.write(run - 1);
                out.write(data, offset + i * size, size);
                i += run;
                continue;
            }
            int start = i;
            i++;
            while (i < pixels && i - start < 128 && !(i + 1 < pixels && equal(data, offset, size, i, i + 1))) i++;
            int count = i - start;
            if (count == 1) out.write(0);
            else out.write(257 - count);
            out.write(data, offset + start * size, count * size);
        }
    }

    private static boolean equal(byte[] data, int offset, int size, int a, int b) {
        int x = offset + a * size;
        int y = offset + b * size;
        for (int k = 0; k < size; k++) if (data[x + k] != data[y + k]) return false;
        return true;
    }

    /** Decodes one coded page back to rows, for the tests. */
    static byte[] decodePage(byte[] coded, int[] at, int lineBytes, int lines, int size) {
        byte[] out = new byte[lineBytes * lines];
        int y = 0;
        while (y < lines) {
            int repeat = (coded[at[0]++] & 0xFF) + 1;
            int x = 0;
            byte[] row = new byte[lineBytes];
            while (x < lineBytes) {
                int n = coded[at[0]++] & 0xFF;
                if (n < 128) {
                    for (int k = 0; k <= n; k++) {
                        System.arraycopy(coded, at[0], row, x, size);
                        x += size;
                    }
                    at[0] += size;
                } else {
                    int count = 257 - n;
                    System.arraycopy(coded, at[0], row, x, count * size);
                    x += count * size;
                    at[0] += count * size;
                }
            }
            for (int r = 0; r < repeat; r++) System.arraycopy(row, 0, out, (y++) * lineBytes, lineBytes);
        }
        return Arrays.copyOf(out, out.length);
    }
}
