package com.authenticator.xp3.qr;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads the text out of a grid of modules: the format information (error
 * correction level and mask), the version information from version 7, the
 * codewords in their zigzag order, the Reed-Solomon blocks they interleave,
 * and the segments of the bit stream (numeric, alphanumeric, bytes, kanji,
 * with ECI for the character set). As ZXing's BitMatrixParser, DataBlock and
 * DecodedBitStreamParser.
 */
final class Decoder {

    private static final String ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    private Decoder() {}

    /** The text in `grid`, or null when it can't be read. */
    static String decode(BitMatrix grid) {
        int dimension = grid.width;
        if (dimension != grid.height || dimension < 21 || dimension % 4 != 1) return null;
        int format = Version.decodeFormatBits(formatCopy(grid, true), formatCopy(grid, false));
        if (format < 0) return null;
        int level = Version.levelOf(format >> 3);
        int mask = format & 7;
        Version version = version(grid);
        if (version == null || version.dimension() != dimension) return null;

        BitMatrix bits = new BitMatrix(dimension, dimension);
        for (int y = 0; y < dimension; y++) {
            for (int x = 0; x < dimension; x++) {
                bits.set(x, y, grid.get(x, y) ^ masked(mask, y, x));
            }
        }
        int[] raw = codewords(bits, version);
        if (raw == null) return null;
        byte[] data = blocks(raw, version, level);
        if (data == null) return null;
        return text(data, version);
    }

    private static int copyBit(BitMatrix grid, int x, int y, int bits) {
        return grid.get(x, y) ? (bits << 1) | 1 : bits << 1;
    }

    private static int formatCopy(BitMatrix grid, boolean first) {
        int bits = 0;
        int dimension = grid.width;
        if (first) {
            for (int i = 0; i < 6; i++) bits = copyBit(grid, i, 8, bits);
            bits = copyBit(grid, 7, 8, bits);
            bits = copyBit(grid, 8, 8, bits);
            bits = copyBit(grid, 8, 7, bits);
            for (int j = 5; j >= 0; j--) bits = copyBit(grid, 8, j, bits);
        } else {
            for (int j = dimension - 1; j >= dimension - 7; j--) bits = copyBit(grid, 8, j, bits);
            for (int i = dimension - 8; i < dimension; i++) bits = copyBit(grid, i, 8, bits);
        }
        return bits;
    }

    private static Version version(BitMatrix grid) {
        int dimension = grid.width;
        int provisional = (dimension - 17) / 4;
        if (provisional <= 6) return Version.of(provisional);
        int least = dimension - 11;
        int bits = 0;
        for (int j = 5; j >= 0; j--) {
            for (int i = dimension - 9; i >= least; i--) bits = copyBit(grid, i, j, bits);
        }
        int number = Version.decodeVersionBits(bits);
        if (number == 0) {
            bits = 0;
            for (int i = 5; i >= 0; i--) {
                for (int j = dimension - 9; j >= least; j--) bits = copyBit(grid, i, j, bits);
            }
            number = Version.decodeVersionBits(bits);
        }
        Version version = Version.of(number);
        // Damaged beyond reading: the dimension still says which it is.
        if (version == null || version.dimension() != dimension) version = Version.of(provisional);
        return version;
    }

    /** Whether data mask `mask` flips the module at row i, column j. */
    static boolean masked(int mask, int i, int j) {
        switch (mask) {
            case 0: return ((i + j) & 1) == 0;
            case 1: return (i & 1) == 0;
            case 2: return j % 3 == 0;
            case 3: return (i + j) % 3 == 0;
            case 4: return (((i / 2) + (j / 3)) & 1) == 0;
            case 5: return (i * j) % 2 + (i * j) % 3 == 0;
            case 6: return (((i * j) % 2 + (i * j) % 3) & 1) == 0;
            default: return (((i + j) % 2 + (i * j) % 3) & 1) == 0;
        }
    }

    private static int[] codewords(BitMatrix bits, Version version) {
        BitMatrix function = version.functionPattern();
        int dimension = bits.width;
        int[] result = new int[version.totalCodewords()];
        int offset = 0;
        int current = 0;
        int read = 0;
        boolean up = true;
        for (int j = dimension - 1; j > 0; j -= 2) {
            if (j == 6) j--;
            for (int count = 0; count < dimension; count++) {
                int i = up ? dimension - 1 - count : count;
                for (int column = 0; column < 2; column++) {
                    if (function.get(j - column, i)) continue;
                    read++;
                    current <<= 1;
                    if (bits.get(j - column, i)) current |= 1;
                    if (read == 8) {
                        if (offset == result.length) return null;
                        result[offset++] = current;
                        read = 0;
                        current = 0;
                    }
                }
            }
            up = !up;
        }
        return offset == result.length ? result : null;
    }

    /** The data codewords, de-interleaved from their blocks and corrected; null when a block can't be. */
    private static byte[] blocks(int[] raw, Version version, int level) {
        int[] groups = version.blocks(level);
        int total = 0;
        for (int g = 0; g < groups.length; g += 3) total += groups[g];
        int ec = groups[1] - groups[2];
        int[][] blocks = new int[total][];
        int[] dataCounts = new int[total];
        int b = 0;
        for (int g = 0; g < groups.length; g += 3) {
            for (int k = 0; k < groups[g]; k++) {
                blocks[b] = new int[groups[g + 1]];
                dataCounts[b] = groups[g + 2];
                b++;
            }
        }
        int shorter = blocks[0].length;
        int longerStart = total;
        for (int k = 0; k < total; k++) {
            if (blocks[k].length != shorter) {
                longerStart = k;
                break;
            }
        }
        int shorterData = shorter - ec;
        int offset = 0;
        for (int i = 0; i < shorterData; i++) {
            for (int k = 0; k < total; k++) blocks[k][i] = raw[offset++];
        }
        for (int k = longerStart; k < total; k++) blocks[k][shorterData] = raw[offset++];
        int longest = blocks[total - 1].length;
        for (int i = shorterData; i < longest; i++) {
            for (int k = 0; k < total; k++) {
                int at = k < longerStart ? i : i + 1;
                if (at < blocks[k].length) blocks[k][at] = raw[offset++];
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int k = 0; k < total; k++) {
            if (!ReedSolomon.correct(blocks[k], ec)) return null;
            for (int i = 0; i < dataCounts[k]; i++) out.write(blocks[k][i]);
        }
        return out.toByteArray();
    }

    /** Reads bits most significant first. */
    private static final class Bits {
        private final byte[] bytes;
        private int position;

        Bits(byte[] bytes) {
            this.bytes = bytes;
        }

        int available() {
            return bytes.length * 8 - position;
        }

        int read(int count) {
            if (count > available()) throw new IllegalStateException();
            int value = 0;
            for (int k = 0; k < count; k++) {
                int bit = (bytes[position >> 3] >> (7 - (position & 7))) & 1;
                value = (value << 1) | bit;
                position++;
            }
            return value;
        }
    }

    private static String text(byte[] data, Version version) {
        Bits bits = new Bits(data);
        StringBuilder out = new StringBuilder();
        Charset charset = null;
        int size = version.number <= 9 ? 0 : version.number <= 26 ? 1 : 2;
        try {
            while (bits.available() >= 4) {
                int mode = bits.read(4);
                if (mode == 0) break;
                switch (mode) {
                    case 1: { // Numeric
                        int count = bits.read(new int[] {10, 12, 14}[size]);
                        while (count >= 3) {
                            int three = bits.read(10);
                            if (three >= 1000) return null;
                            out.append(String.format(java.util.Locale.US, "%03d", three));
                            count -= 3;
                        }
                        if (count == 2) {
                            int two = bits.read(7);
                            if (two >= 100) return null;
                            out.append(String.format(java.util.Locale.US, "%02d", two));
                        } else if (count == 1) {
                            int one = bits.read(4);
                            if (one >= 10) return null;
                            out.append(one);
                        }
                        break;
                    }
                    case 2: { // Alphanumeric
                        int count = bits.read(new int[] {9, 11, 13}[size]);
                        while (count > 1) {
                            int pair = bits.read(11);
                            if (pair >= 45 * 45) return null;
                            out.append(ALPHANUMERIC.charAt(pair / 45)).append(ALPHANUMERIC.charAt(pair % 45));
                            count -= 2;
                        }
                        if (count == 1) {
                            int one = bits.read(6);
                            if (one >= 45) return null;
                            out.append(ALPHANUMERIC.charAt(one));
                        }
                        break;
                    }
                    case 4: { // Bytes
                        int count = bits.read(new int[] {8, 16, 16}[size]);
                        byte[] bytes = new byte[count];
                        for (int k = 0; k < count; k++) bytes[k] = (byte) bits.read(8);
                        out.append(bytes(bytes, charset));
                        break;
                    }
                    case 8: { // Kanji, two bytes of Shift JIS in 13 bits each
                        int count = bits.read(new int[] {8, 10, 12}[size]);
                        byte[] bytes = new byte[2 * count];
                        for (int k = 0; k < count; k++) {
                            int packed = bits.read(13);
                            int value = ((packed / 0xC0) << 8) | (packed % 0xC0);
                            value += value < 0x1F00 ? 0x8140 : 0xC140;
                            bytes[2 * k] = (byte) (value >> 8);
                            bytes[2 * k + 1] = (byte) value;
                        }
                        out.append(new String(bytes, Charset.forName("Shift_JIS")));
                        break;
                    }
                    case 7: { // ECI: the character set of the bytes that follow
                        int first = bits.read(8);
                        int value;
                        if ((first & 0x80) == 0) value = first & 0x7F;
                        else if ((first & 0xC0) == 0x80) value = ((first & 0x3F) << 8) | bits.read(8);
                        else if ((first & 0xE0) == 0xC0) value = ((first & 0x1F) << 16) | bits.read(16);
                        else return null;
                        charset = value == 26 ? StandardCharsets.UTF_8
                            : value == 1 || value == 3 ? StandardCharsets.ISO_8859_1 : null;
                        break;
                    }
                    case 3: // Structured append: which part of several this is
                        bits.read(16);
                        break;
                    case 5: // FNC1, first position
                        break;
                    case 9: // FNC1, second position
                        bits.read(8);
                        break;
                    default:
                        return null;
                }
            }
        } catch (IllegalStateException e) {
            return null;
        }
        return out.toString();
    }

    /** Bytes as the ECI said, else as UTF-8 when they are valid UTF-8, else as ISO-8859-1. */
    private static String bytes(byte[] bytes, Charset charset) {
        if (charset != null) return new String(bytes, charset);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }
}
