package com.authenticator.xp3.qr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds the three finder patterns, the nested squares in a QR code's
 * corners: a run of dark, light, dark, light, dark in the ratio 1:1:3:1:1
 * across the middle in every direction. Rows are scanned for that ratio, and
 * each hit is checked down its column, along its row again and across its
 * diagonal before it counts. This follows ZXing's FinderPatternFinder.
 */
final class Finder {

    /** The most modules a code can have across, which bounds how far apart rows may be scanned. */
    private static final int MAX_MODULES = 97;
    private static final int MIN_SKIP = 3;
    /** A centre seen this many times is trusted. */
    private static final int QUORUM = 2;

    static final class Pattern {
        final float x;
        final float y;
        final float moduleSize;
        final int count;

        Pattern(float x, float y, float moduleSize, int count) {
            this.x = x;
            this.y = y;
            this.moduleSize = moduleSize;
            this.count = count;
        }

        boolean near(float size, float atY, float atX) {
            if (Math.abs(atY - y) <= size && Math.abs(atX - x) <= size) {
                float difference = Math.abs(size - moduleSize);
                return difference <= 1f || difference <= moduleSize;
            }
            return false;
        }

        Pattern combine(float atY, float atX, float size) {
            int combined = count + 1;
            return new Pattern((count * x + atX) / combined, (count * y + atY) / combined,
                (count * moduleSize + size) / combined, combined);
        }
    }

    private final BitMatrix image;
    private final List<Pattern> centres = new ArrayList<>();
    private final int[] counts = new int[5];
    private boolean skipped;

    Finder(BitMatrix image) {
        this.image = image;
    }

    /** The three patterns as bottom left, top left, top right; or null. */
    Pattern[] find(boolean hard) {
        int maxI = image.height;
        int maxJ = image.width;
        int skip = (3 * maxI) / (4 * MAX_MODULES);
        if (skip < MIN_SKIP || hard) skip = MIN_SKIP;
        boolean done = false;
        int[] state = new int[5];
        for (int i = skip - 1; i < maxI && !done; i += skip) {
            clear(state);
            int current = 0;
            for (int j = 0; j < maxJ; j++) {
                if (image.get(j, i)) {
                    if ((current & 1) == 1) current++;
                    state[current]++;
                } else if ((current & 1) == 0) {
                    if (current == 4) {
                        if (crossed(state)) {
                            if (candidate(state, i, j)) {
                                skip = 2;
                                if (skipped) {
                                    done = confirmed();
                                } else {
                                    int rowSkip = rowSkip();
                                    if (rowSkip > state[2]) {
                                        i += rowSkip - state[2] - skip;
                                        j = maxJ - 1;
                                    }
                                }
                            } else {
                                shift(state);
                                current = 3;
                                continue;
                            }
                            current = 0;
                            clear(state);
                        } else {
                            shift(state);
                            current = 3;
                        }
                    } else {
                        state[++current]++;
                    }
                } else {
                    state[current]++;
                }
            }
            if (crossed(state) && candidate(state, i, maxJ)) {
                skip = state[0];
                if (skipped) done = confirmed();
            }
        }
        Pattern[] best = best();
        if (best == null) return null;
        return order(best);
    }

    private static void clear(int[] state) {
        for (int k = 0; k < state.length; k++) state[k] = 0;
    }

    private static void shift(int[] state) {
        state[0] = state[2];
        state[1] = state[3];
        state[2] = state[4];
        state[3] = 1;
        state[4] = 0;
    }

    /** Whether five runs are near enough 1:1:3:1:1. */
    static boolean crossed(int[] state) {
        int total = 0;
        for (int k = 0; k < 5; k++) {
            if (state[k] == 0) return false;
            total += state[k];
        }
        if (total < 7) return false;
        float module = total / 7f;
        float variance = module / 2f;
        return Math.abs(module - state[0]) < variance
            && Math.abs(module - state[1]) < variance
            && Math.abs(3f * module - state[2]) < 3f * variance
            && Math.abs(module - state[3]) < variance
            && Math.abs(module - state[4]) < variance;
    }

    /** The same, more loosely, for the diagonal, where pixels are stretched. */
    private static boolean crossedDiagonally(int[] state) {
        int total = 0;
        for (int k = 0; k < 5; k++) {
            if (state[k] == 0) return false;
            total += state[k];
        }
        if (total < 7) return false;
        float module = total / 7f;
        float variance = module / 1.333f;
        return Math.abs(module - state[0]) < variance
            && Math.abs(module - state[1]) < variance
            && Math.abs(3f * module - state[2]) < 3f * variance
            && Math.abs(module - state[3]) < variance
            && Math.abs(module - state[4]) < variance;
    }

    private static float centreFromEnd(int[] state, int end) {
        return (end - state[4] - state[3]) - state[2] / 2f;
    }

    private boolean candidate(int[] state, int i, int j) {
        int total = state[0] + state[1] + state[2] + state[3] + state[4];
        float centreJ = centreFromEnd(state, j);
        float centreI = vertical(i, (int) centreJ, state[2], total);
        if (Float.isNaN(centreI)) return false;
        centreJ = horizontal((int) centreJ, (int) centreI, state[2], total);
        if (Float.isNaN(centreJ) || !diagonal((int) centreI, (int) centreJ)) return false;
        float size = total / 7f;
        for (int k = 0; k < centres.size(); k++) {
            Pattern centre = centres.get(k);
            if (centre.near(size, centreI, centreJ)) {
                centres.set(k, centre.combine(centreI, centreJ, size));
                return true;
            }
        }
        centres.add(new Pattern(centreJ, centreI, size, 1));
        return true;
    }

    private float vertical(int startI, int centreJ, int maxCount, int originalTotal) {
        int maxI = image.height;
        clear(counts);
        int i = startI;
        while (i >= 0 && image.get(centreJ, i)) { counts[2]++; i--; }
        if (i < 0) return Float.NaN;
        while (i >= 0 && !image.get(centreJ, i) && counts[1] <= maxCount) { counts[1]++; i--; }
        if (i < 0 || counts[1] > maxCount) return Float.NaN;
        while (i >= 0 && image.get(centreJ, i) && counts[0] <= maxCount) { counts[0]++; i--; }
        if (counts[0] > maxCount) return Float.NaN;
        i = startI + 1;
        while (i < maxI && image.get(centreJ, i)) { counts[2]++; i++; }
        if (i == maxI) return Float.NaN;
        while (i < maxI && !image.get(centreJ, i) && counts[3] < maxCount) { counts[3]++; i++; }
        if (i == maxI || counts[3] >= maxCount) return Float.NaN;
        while (i < maxI && image.get(centreJ, i) && counts[4] < maxCount) { counts[4]++; i++; }
        if (counts[4] >= maxCount) return Float.NaN;
        int total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
        if (5 * Math.abs(total - originalTotal) >= 2 * originalTotal) return Float.NaN;
        return crossed(counts) ? centreFromEnd(counts, i) : Float.NaN;
    }

    private float horizontal(int startJ, int centreI, int maxCount, int originalTotal) {
        int maxJ = image.width;
        clear(counts);
        int j = startJ;
        while (j >= 0 && image.get(j, centreI)) { counts[2]++; j--; }
        if (j < 0) return Float.NaN;
        while (j >= 0 && !image.get(j, centreI) && counts[1] <= maxCount) { counts[1]++; j--; }
        if (j < 0 || counts[1] > maxCount) return Float.NaN;
        while (j >= 0 && image.get(j, centreI) && counts[0] <= maxCount) { counts[0]++; j--; }
        if (counts[0] > maxCount) return Float.NaN;
        j = startJ + 1;
        while (j < maxJ && image.get(j, centreI)) { counts[2]++; j++; }
        if (j == maxJ) return Float.NaN;
        while (j < maxJ && !image.get(j, centreI) && counts[3] < maxCount) { counts[3]++; j++; }
        if (j == maxJ || counts[3] >= maxCount) return Float.NaN;
        while (j < maxJ && image.get(j, centreI) && counts[4] < maxCount) { counts[4]++; j++; }
        if (counts[4] >= maxCount) return Float.NaN;
        int total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
        if (5 * Math.abs(total - originalTotal) >= originalTotal) return Float.NaN;
        return crossed(counts) ? centreFromEnd(counts, j) : Float.NaN;
    }

    private boolean diagonal(int centreI, int centreJ) {
        clear(counts);
        int i = 0;
        while (centreI >= i && centreJ >= i && image.get(centreJ - i, centreI - i)) { counts[2]++; i++; }
        if (counts[2] == 0) return false;
        while (centreI >= i && centreJ >= i && !image.get(centreJ - i, centreI - i)) { counts[1]++; i++; }
        if (counts[1] == 0) return false;
        while (centreI >= i && centreJ >= i && image.get(centreJ - i, centreI - i)) { counts[0]++; i++; }
        if (counts[0] == 0) return false;
        int maxI = image.height;
        int maxJ = image.width;
        i = 1;
        while (centreI + i < maxI && centreJ + i < maxJ && image.get(centreJ + i, centreI + i)) { counts[2]++; i++; }
        while (centreI + i < maxI && centreJ + i < maxJ && !image.get(centreJ + i, centreI + i)) { counts[3]++; i++; }
        if (counts[3] == 0) return false;
        while (centreI + i < maxI && centreJ + i < maxJ && image.get(centreJ + i, centreI + i)) { counts[4]++; i++; }
        if (counts[4] == 0) return false;
        return crossedDiagonally(counts);
    }

    /** Once two centres are sure, how many rows can be skipped before the third. */
    private int rowSkip() {
        if (centres.size() <= 1) return 0;
        Pattern first = null;
        for (Pattern centre : centres) {
            if (centre.count < QUORUM) continue;
            if (first == null) {
                first = centre;
            } else {
                skipped = true;
                return (int) (Math.abs(first.x - centre.x) - Math.abs(first.y - centre.y)) / 2;
            }
        }
        return 0;
    }

    /** Whether three centres are sure and agree on the module size. */
    private boolean confirmed() {
        int sure = 0;
        float total = 0;
        for (Pattern centre : centres) {
            if (centre.count >= QUORUM) {
                sure++;
                total += centre.moduleSize;
            }
        }
        if (sure < 3) return false;
        float average = total / centres.size();
        float deviation = 0;
        for (Pattern centre : centres) deviation += Math.abs(centre.moduleSize - average);
        return deviation <= 0.05f * total;
    }

    /** The three centres that best make a right isosceles triangle, of like module sizes. */
    private Pattern[] best() {
        List<Pattern> pool = new ArrayList<>();
        for (Pattern centre : centres) if (centre.count >= QUORUM) pool.add(centre);
        if (pool.size() < 3) pool = new ArrayList<>(centres);
        if (pool.size() < 3) return null;
        Collections.sort(pool, (a, b) -> Float.compare(a.moduleSize, b.moduleSize));
        double bestScore = Double.MAX_VALUE;
        Pattern[] best = null;
        for (int i = 0; i < pool.size() - 2; i++) {
            Pattern a = pool.get(i);
            for (int j = i + 1; j < pool.size() - 1; j++) {
                Pattern b = pool.get(j);
                double ab = squared(a, b);
                for (int k = j + 1; k < pool.size(); k++) {
                    Pattern c = pool.get(k);
                    if (c.moduleSize > a.moduleSize * 1.4f) break;
                    double[] sides = {ab, squared(b, c), squared(a, c)};
                    java.util.Arrays.sort(sides);
                    double score = Math.abs(sides[2] - 2 * sides[1]) + Math.abs(sides[2] - 2 * sides[0]);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Pattern[] {a, b, c};
                    }
                }
            }
        }
        return best;
    }

    private static double squared(Pattern a, Pattern b) {
        double x = a.x - b.x;
        double y = a.y - b.y;
        return x * x + y * y;
    }

    /** Bottom left, top left (opposite the longest side), top right. */
    static Pattern[] order(Pattern[] patterns) {
        double zeroOne = squared(patterns[0], patterns[1]);
        double oneTwo = squared(patterns[1], patterns[2]);
        double zeroTwo = squared(patterns[0], patterns[2]);
        Pattern a;
        Pattern b;
        Pattern c;
        if (oneTwo >= zeroOne && oneTwo >= zeroTwo) {
            b = patterns[0]; a = patterns[1]; c = patterns[2];
        } else if (zeroTwo >= oneTwo && zeroTwo >= zeroOne) {
            b = patterns[1]; a = patterns[0]; c = patterns[2];
        } else {
            b = patterns[2]; a = patterns[0]; c = patterns[1];
        }
        // A code seen from behind would have them the other way round.
        if ((c.x - b.x) * (a.y - b.y) - (c.y - b.y) * (a.x - b.x) < 0) {
            Pattern swap = a;
            a = c;
            c = swap;
        }
        return new Pattern[] {a, b, c};
    }
}
