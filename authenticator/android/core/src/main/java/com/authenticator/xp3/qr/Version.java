package com.authenticator.xp3.qr;

/**
 * What the QR specification (ISO/IEC 18004) fixes for each of the 40
 * versions: the size, where the alignment patterns sit, and how the
 * codewords are split into Reed-Solomon blocks at each error correction
 * level. The tables were generated from the python-qrcode library's.
 */
final class Version {

    /** Error correction levels in the order of BLOCKS' columns. */
    static final int L = 0, M = 1, Q = 2, H = 3;

    /**
     * For each version and level (L, M, Q, H): groups of (blocks, codewords
     * per block, data codewords per block).
     */
    private static final int[][][] BLOCKS = {
        {{1, 26, 19}, {1, 26, 16}, {1, 26, 13}, {1, 26, 9}}, // 1
        {{1, 44, 34}, {1, 44, 28}, {1, 44, 22}, {1, 44, 16}}, // 2
        {{1, 70, 55}, {1, 70, 44}, {2, 35, 17}, {2, 35, 13}}, // 3
        {{1, 100, 80}, {2, 50, 32}, {2, 50, 24}, {4, 25, 9}}, // 4
        {{1, 134, 108}, {2, 67, 43}, {2, 33, 15, 2, 34, 16}, {2, 33, 11, 2, 34, 12}}, // 5
        {{2, 86, 68}, {4, 43, 27}, {4, 43, 19}, {4, 43, 15}}, // 6
        {{2, 98, 78}, {4, 49, 31}, {2, 32, 14, 4, 33, 15}, {4, 39, 13, 1, 40, 14}}, // 7
        {{2, 121, 97}, {2, 60, 38, 2, 61, 39}, {4, 40, 18, 2, 41, 19}, {4, 40, 14, 2, 41, 15}}, // 8
        {{2, 146, 116}, {3, 58, 36, 2, 59, 37}, {4, 36, 16, 4, 37, 17}, {4, 36, 12, 4, 37, 13}}, // 9
        {{2, 86, 68, 2, 87, 69}, {4, 69, 43, 1, 70, 44}, {6, 43, 19, 2, 44, 20}, {6, 43, 15, 2, 44, 16}}, // 10
        {{4, 101, 81}, {1, 80, 50, 4, 81, 51}, {4, 50, 22, 4, 51, 23}, {3, 36, 12, 8, 37, 13}}, // 11
        {{2, 116, 92, 2, 117, 93}, {6, 58, 36, 2, 59, 37}, {4, 46, 20, 6, 47, 21}, {7, 42, 14, 4, 43, 15}}, // 12
        {{4, 133, 107}, {8, 59, 37, 1, 60, 38}, {8, 44, 20, 4, 45, 21}, {12, 33, 11, 4, 34, 12}}, // 13
        {{3, 145, 115, 1, 146, 116}, {4, 64, 40, 5, 65, 41}, {11, 36, 16, 5, 37, 17}, {11, 36, 12, 5, 37, 13}}, // 14
        {{5, 109, 87, 1, 110, 88}, {5, 65, 41, 5, 66, 42}, {5, 54, 24, 7, 55, 25}, {11, 36, 12, 7, 37, 13}}, // 15
        {{5, 122, 98, 1, 123, 99}, {7, 73, 45, 3, 74, 46}, {15, 43, 19, 2, 44, 20}, {3, 45, 15, 13, 46, 16}}, // 16
        {{1, 135, 107, 5, 136, 108}, {10, 74, 46, 1, 75, 47}, {1, 50, 22, 15, 51, 23}, {2, 42, 14, 17, 43, 15}}, // 17
        {{5, 150, 120, 1, 151, 121}, {9, 69, 43, 4, 70, 44}, {17, 50, 22, 1, 51, 23}, {2, 42, 14, 19, 43, 15}}, // 18
        {{3, 141, 113, 4, 142, 114}, {3, 70, 44, 11, 71, 45}, {17, 47, 21, 4, 48, 22}, {9, 39, 13, 16, 40, 14}}, // 19
        {{3, 135, 107, 5, 136, 108}, {3, 67, 41, 13, 68, 42}, {15, 54, 24, 5, 55, 25}, {15, 43, 15, 10, 44, 16}}, // 20
        {{4, 144, 116, 4, 145, 117}, {17, 68, 42}, {17, 50, 22, 6, 51, 23}, {19, 46, 16, 6, 47, 17}}, // 21
        {{2, 139, 111, 7, 140, 112}, {17, 74, 46}, {7, 54, 24, 16, 55, 25}, {34, 37, 13}}, // 22
        {{4, 151, 121, 5, 152, 122}, {4, 75, 47, 14, 76, 48}, {11, 54, 24, 14, 55, 25}, {16, 45, 15, 14, 46, 16}}, // 23
        {{6, 147, 117, 4, 148, 118}, {6, 73, 45, 14, 74, 46}, {11, 54, 24, 16, 55, 25}, {30, 46, 16, 2, 47, 17}}, // 24
        {{8, 132, 106, 4, 133, 107}, {8, 75, 47, 13, 76, 48}, {7, 54, 24, 22, 55, 25}, {22, 45, 15, 13, 46, 16}}, // 25
        {{10, 142, 114, 2, 143, 115}, {19, 74, 46, 4, 75, 47}, {28, 50, 22, 6, 51, 23}, {33, 46, 16, 4, 47, 17}}, // 26
        {{8, 152, 122, 4, 153, 123}, {22, 73, 45, 3, 74, 46}, {8, 53, 23, 26, 54, 24}, {12, 45, 15, 28, 46, 16}}, // 27
        {{3, 147, 117, 10, 148, 118}, {3, 73, 45, 23, 74, 46}, {4, 54, 24, 31, 55, 25}, {11, 45, 15, 31, 46, 16}}, // 28
        {{7, 146, 116, 7, 147, 117}, {21, 73, 45, 7, 74, 46}, {1, 53, 23, 37, 54, 24}, {19, 45, 15, 26, 46, 16}}, // 29
        {{5, 145, 115, 10, 146, 116}, {19, 75, 47, 10, 76, 48}, {15, 54, 24, 25, 55, 25}, {23, 45, 15, 25, 46, 16}}, // 30
        {{13, 145, 115, 3, 146, 116}, {2, 74, 46, 29, 75, 47}, {42, 54, 24, 1, 55, 25}, {23, 45, 15, 28, 46, 16}}, // 31
        {{17, 145, 115}, {10, 74, 46, 23, 75, 47}, {10, 54, 24, 35, 55, 25}, {19, 45, 15, 35, 46, 16}}, // 32
        {{17, 145, 115, 1, 146, 116}, {14, 74, 46, 21, 75, 47}, {29, 54, 24, 19, 55, 25}, {11, 45, 15, 46, 46, 16}}, // 33
        {{13, 145, 115, 6, 146, 116}, {14, 74, 46, 23, 75, 47}, {44, 54, 24, 7, 55, 25}, {59, 46, 16, 1, 47, 17}}, // 34
        {{12, 151, 121, 7, 152, 122}, {12, 75, 47, 26, 76, 48}, {39, 54, 24, 14, 55, 25}, {22, 45, 15, 41, 46, 16}}, // 35
        {{6, 151, 121, 14, 152, 122}, {6, 75, 47, 34, 76, 48}, {46, 54, 24, 10, 55, 25}, {2, 45, 15, 64, 46, 16}}, // 36
        {{17, 152, 122, 4, 153, 123}, {29, 74, 46, 14, 75, 47}, {49, 54, 24, 10, 55, 25}, {24, 45, 15, 46, 46, 16}}, // 37
        {{4, 152, 122, 18, 153, 123}, {13, 74, 46, 32, 75, 47}, {48, 54, 24, 14, 55, 25}, {42, 45, 15, 32, 46, 16}}, // 38
        {{20, 147, 117, 4, 148, 118}, {40, 75, 47, 7, 76, 48}, {43, 54, 24, 22, 55, 25}, {10, 45, 15, 67, 46, 16}}, // 39
        {{19, 148, 118, 6, 149, 119}, {18, 75, 47, 31, 76, 48}, {34, 54, 24, 34, 55, 25}, {20, 45, 15, 61, 46, 16}}, // 40
    };

    /** The rows and columns alignment patterns are centred on, for each version. */
    private static final int[][] ALIGNMENT = {
        {},
        {6, 18},
        {6, 22},
        {6, 26},
        {6, 30},
        {6, 34},
        {6, 22, 38},
        {6, 24, 42},
        {6, 26, 46},
        {6, 28, 50},
        {6, 30, 54},
        {6, 32, 58},
        {6, 34, 62},
        {6, 26, 46, 66},
        {6, 26, 48, 70},
        {6, 26, 50, 74},
        {6, 30, 54, 78},
        {6, 30, 56, 82},
        {6, 30, 58, 86},
        {6, 34, 62, 90},
        {6, 28, 50, 72, 94},
        {6, 26, 50, 74, 98},
        {6, 30, 54, 78, 102},
        {6, 28, 54, 80, 106},
        {6, 32, 58, 84, 110},
        {6, 30, 58, 86, 114},
        {6, 34, 62, 90, 118},
        {6, 26, 50, 74, 98, 122},
        {6, 30, 54, 78, 102, 126},
        {6, 26, 52, 78, 104, 130},
        {6, 30, 56, 82, 108, 134},
        {6, 34, 60, 86, 112, 138},
        {6, 30, 58, 86, 114, 142},
        {6, 34, 62, 90, 118, 146},
        {6, 30, 54, 78, 102, 126, 150},
        {6, 24, 50, 76, 102, 128, 154},
        {6, 28, 54, 80, 106, 132, 158},
        {6, 32, 58, 84, 110, 136, 162},
        {6, 26, 54, 82, 110, 138, 166},
        {6, 30, 58, 86, 114, 142, 170}
    };

    final int number;

    private Version(int number) {
        this.number = number;
    }

    static Version of(int number) {
        if (number < 1 || number > 40) return null;
        return new Version(number);
    }

    /** The version a symbol `dimension` modules across is, or null. */
    static Version forDimension(int dimension) {
        if (dimension % 4 != 1) return null;
        return of((dimension - 17) / 4);
    }

    int dimension() {
        return 17 + 4 * number;
    }

    int[] alignment() {
        return ALIGNMENT[number - 1];
    }

    int[] blocks(int level) {
        return BLOCKS[number - 1][level];
    }

    int totalCodewords() {
        int[] groups = blocks(L);
        int total = 0;
        for (int i = 0; i < groups.length; i += 3) total += groups[i] * groups[i + 1];
        return total;
    }

    /** The modules that are not data: finders, timing, alignment, format and version areas. */
    BitMatrix functionPattern() {
        int dimension = dimension();
        BitMatrix pattern = new BitMatrix(dimension, dimension);
        pattern.setRegion(0, 0, 9, 9);
        pattern.setRegion(dimension - 8, 0, 8, 9);
        pattern.setRegion(0, dimension - 8, 9, 8);
        int[] centres = alignment();
        int max = centres.length;
        for (int x = 0; x < max; x++) {
            for (int y = 0; y < max; y++) {
                if ((x == 0 && (y == 0 || y == max - 1)) || (x == max - 1 && y == 0)) continue;
                pattern.setRegion(centres[x] - 2, centres[y] - 2, 5, 5);
            }
        }
        pattern.setRegion(6, 9, 1, dimension - 17);
        pattern.setRegion(9, 6, dimension - 17, 1);
        if (number > 6) {
            pattern.setRegion(dimension - 11, 0, 3, 6);
            pattern.setRegion(0, dimension - 11, 6, 3);
        }
        return pattern;
    }

    /** The 18-bit version information block for versions 7 and up: BCH(18, 6). */
    static int versionBits(int number) {
        int value = number << 12;
        int remainder = value;
        for (int bit = 17; bit >= 12; bit--) {
            if ((remainder & (1 << bit)) != 0) remainder ^= 0x1F25 << (bit - 12);
        }
        return value | remainder;
    }

    /** The version whose information block is nearest `bits`, within 3 bit errors, or 0. */
    static int decodeVersionBits(int bits) {
        int best = 0;
        int bestDistance = 4;
        for (int number = 7; number <= 40; number++) {
            int distance = Integer.bitCount(bits ^ versionBits(number));
            if (distance < bestDistance) {
                best = number;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The 15-bit format information for 5 data bits (level and mask): BCH(15, 5), masked. */
    static int formatBits(int data) {
        int value = data << 10;
        int remainder = value;
        for (int bit = 14; bit >= 10; bit--) {
            if ((remainder & (1 << bit)) != 0) remainder ^= 0x537 << (bit - 10);
        }
        return (value | remainder) ^ 0x5412;
    }

    /** The 5 data bits nearest either copy of the format information, within 3 bit errors, or -1. */
    static int decodeFormatBits(int first, int second) {
        int best = -1;
        int bestDistance = 4;
        for (int data = 0; data < 32; data++) {
            int expected = formatBits(data);
            int distance = Math.min(Integer.bitCount(first ^ expected), Integer.bitCount(second ^ expected));
            if (distance < bestDistance) {
                best = data;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The level index (L, M, Q, H) the format's two level bits stand for. */
    static int levelOf(int bits) {
        switch (bits) {
            case 1: return L;
            case 0: return M;
            case 3: return Q;
            default: return H;
        }
    }
}
