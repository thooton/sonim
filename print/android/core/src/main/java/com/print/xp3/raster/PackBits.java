package com.print.xp3.raster;

import java.io.IOException;
import java.io.OutputStream;

/**
 * TIFF PackBits, which is also PCL's compression mode 2 and PostScript's
 * RunLengthDecode: 0 to 127 means the next n + 1 bytes as they are, and 129
 * to 255 means the next byte 257 - n times.
 */
final class PackBits {

    private PackBits() {}

    /** Codes `length` bytes from `offset` into `out`, returning how many bytes were written. */
    static int encode(byte[] data, int offset, int length, OutputStream out) throws IOException {
        int written = 0;
        int i = 0;
        while (i < length) {
            int run = 1;
            while (i + run < length && run < 128 && data[offset + i + run] == data[offset + i]) run++;
            if (run > 1) {
                out.write(257 - run);
                out.write(data[offset + i]);
                written += 2;
                i += run;
                continue;
            }
            int start = i;
            i++;
            while (i < length && i - start < 128
                && !(i + 1 < length && data[offset + i] == data[offset + i + 1])) i++;
            int count = i - start;
            out.write(count - 1);
            out.write(data, offset + start, count);
            written += count + 1;
        }
        return written;
    }
}
