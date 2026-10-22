package com.books.xp3.book;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * PalmDOC compression, the LZ77 variant MOBI text records are packed with.
 *
 * Each record is compressed on its own, so a match can only reach back into
 * the same record. A compressed byte is one of:
 *
 * - 0x00, 0x09-0x7F: itself.
 * - 0x01-0x08: that many bytes follow, copied as they are.
 * - 0x80-0xBF: with the next byte, a 14-bit pair: 11 bits of distance back
 *   (1-2047) and 3 of length (3-10).
 * - 0xC0-0xFF: a space, then the byte XOR 0x80 (0x40-0x7F).
 */
final class PalmDoc {

    private static final int WINDOW = 2047;
    private static final int MIN_MATCH = 3;
    private static final int MAX_MATCH = 10;
    /** How many earlier places with the same three bytes are tried. */
    private static final int CHAIN = 48;

    private PalmDoc() {}

    static byte[] compress(byte[] data, int start, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        int end = start + length;
        // Hash chains over three-byte prefixes, positions relative to start.
        int[] head = new int[4096];
        Arrays.fill(head, -1);
        int[] previous = new int[Math.max(1, length)];
        int i = start;
        int indexed = start;
        while (i < end) {
            // Index every position before i, so matches only point backwards.
            while (indexed < i) {
                if (indexed + MIN_MATCH <= end) {
                    int key = hash(data, indexed);
                    previous[indexed - start] = head[key];
                    head[key] = indexed - start;
                }
                indexed++;
            }

            int bestLength = 0;
            int bestDistance = 0;
            if (i + MIN_MATCH <= end) {
                int candidate = head[hash(data, i)];
                int tries = 0;
                while (candidate >= 0 && tries++ < CHAIN) {
                    int from = start + candidate;
                    int distance = i - from;
                    if (distance > WINDOW) break;
                    // Matches never overlap the bytes they produce.
                    int limit = Math.min(Math.min(MAX_MATCH, end - i), distance);
                    int n = 0;
                    while (n < limit && data[from + n] == data[i + n]) n++;
                    if (n > bestLength) {
                        bestLength = n;
                        bestDistance = distance;
                        if (n == MAX_MATCH) break;
                    }
                    candidate = previous[candidate];
                }
            }
            if (bestLength >= MIN_MATCH) {
                int code = 0x8000 | (bestDistance << 3) | (bestLength - MIN_MATCH);
                out.write(code >> 8);
                out.write(code & 0xFF);
                i += bestLength;
                continue;
            }

            int c = data[i] & 0xFF;
            if (c == ' ' && i + 1 < end) {
                int next = data[i + 1] & 0xFF;
                if (next >= 0x40 && next <= 0x7F) {
                    out.write(next ^ 0x80);
                    i += 2;
                    continue;
                }
            }
            if (plain(c)) {
                out.write(c);
                i++;
                continue;
            }
            // A run of up to eight bytes that cannot stand for themselves.
            int run = 1;
            while (run < 8 && i + run < end && !plain(data[i + run] & 0xFF)) run++;
            out.write(run);
            out.write(data, i, run);
            i += run;
        }
        return out.toByteArray();
    }

    /** The other way, for the tests. */
    static byte[] decompress(byte[] data, int start, int length) {
        byte[] out = new byte[Math.max(16, length * 8)];
        int size = 0;
        int end = start + length;
        int i = start;
        while (i < end) {
            if (size + 16 > out.length) out = Arrays.copyOf(out, out.length * 2);
            int c = data[i++] & 0xFF;
            if (c >= 1 && c <= 8) {
                if (size + c > out.length) out = Arrays.copyOf(out, out.length * 2);
                System.arraycopy(data, i, out, size, c);
                size += c;
                i += c;
            } else if (c < 0x80) {
                out[size++] = (byte) c;
            } else if (c < 0xC0) {
                int pair = ((c << 8) | (data[i++] & 0xFF)) & 0x3FFF;
                int distance = pair >> 3;
                int n = (pair & 7) + MIN_MATCH;
                for (int k = 0; k < n; k++, size++) out[size] = out[size - distance];
            } else {
                out[size++] = ' ';
                out[size++] = (byte) (c ^ 0x80);
            }
        }
        return Arrays.copyOf(out, size);
    }

    private static boolean plain(int c) {
        return c == 0 || (c >= 0x09 && c <= 0x7F);
    }

    private static int hash(byte[] data, int at) {
        int h = ((data[at] & 0xFF) << 8) ^ ((data[at + 1] & 0xFF) << 4) ^ (data[at + 2] & 0xFF);
        return h & 0xFFF;
    }
}
