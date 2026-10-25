package com.authenticator.xp3.qr;

import java.util.ArrayList;
import java.util.List;

/**
 * From the three finder patterns to the grid of modules: the module size and
 * the symbol's dimension from the distances between them, the bottom right
 * alignment pattern (from version 2) to correct for perspective, then a
 * projective transform that samples the centre of every module. This follows
 * ZXing's Detector, with the transform solved directly.
 */
final class Detector {

    private final BitMatrix image;

    Detector(BitMatrix image) {
        this.image = image;
    }

    /** The grids the patterns could be, most likely first: more than one when the dimension is in doubt. */
    List<BitMatrix> grids(Finder.Pattern[] patterns) {
        List<BitMatrix> grids = new ArrayList<>();
        Finder.Pattern bottomLeft = patterns[0];
        Finder.Pattern topLeft = patterns[1];
        Finder.Pattern topRight = patterns[2];
        float moduleSize = (moduleSize(topLeft, topRight) + moduleSize(topLeft, bottomLeft)) / 2f;
        if (!(moduleSize >= 1f)) return grids;
        int across = Math.round(distance(topLeft.x, topLeft.y, topRight.x, topRight.y) / moduleSize);
        int down = Math.round(distance(topLeft.x, topLeft.y, bottomLeft.x, bottomLeft.y) / moduleSize);
        int dimension = (across + down) / 2 + 7;
        int[] tries;
        switch (dimension & 3) {
            case 0: tries = new int[] {dimension + 1, dimension - 3}; break;
            case 2: tries = new int[] {dimension - 1, dimension + 3}; break;
            case 3: tries = new int[] {dimension - 2, dimension + 2}; break;
            default: tries = new int[] {dimension, dimension + 4, dimension - 4}; break;
        }
        for (int size : tries) {
            Version version = Version.forDimension(size);
            if (version == null) continue;
            BitMatrix grid = sample(version, moduleSize, topLeft, topRight, bottomLeft);
            if (grid != null) grids.add(grid);
        }
        return grids;
    }

    private BitMatrix sample(Version version, float moduleSize, Finder.Pattern topLeft, Finder.Pattern topRight,
                             Finder.Pattern bottomLeft) {
        int dimension = version.dimension();
        float far = dimension - 3.5f;
        float[] alignment = null;
        if (version.alignment().length > 0) {
            float bottomRightX = topRight.x - topLeft.x + bottomLeft.x;
            float bottomRightY = topRight.y - topLeft.y + bottomLeft.y;
            float correction = 1f - 3f / (dimension - 7);
            int estimateX = (int) (topLeft.x + correction * (bottomRightX - topLeft.x));
            int estimateY = (int) (topLeft.y + correction * (bottomRightY - topLeft.y));
            for (int allowance = 4; allowance <= 16 && alignment == null; allowance <<= 1) {
                alignment = alignmentIn(moduleSize, estimateX, estimateY, allowance);
            }
        }
        double[] from;
        double[] to;
        if (alignment != null) {
            float near = far - 3f;
            from = new double[] {3.5, 3.5, far, 3.5, near, near, 3.5, far};
            to = new double[] {topLeft.x, topLeft.y, topRight.x, topRight.y, alignment[0], alignment[1], bottomLeft.x, bottomLeft.y};
        } else {
            from = new double[] {3.5, 3.5, far, 3.5, far, far, 3.5, far};
            to = new double[] {topLeft.x, topLeft.y, topRight.x, topRight.y,
                topRight.x - topLeft.x + bottomLeft.x, topRight.y - topLeft.y + bottomLeft.y, bottomLeft.x, bottomLeft.y};
        }
        double[] h = homography(from, to);
        if (h == null) return null;
        BitMatrix grid = new BitMatrix(dimension, dimension);
        int outside = 0;
        for (int y = 0; y < dimension; y++) {
            for (int x = 0; x < dimension; x++) {
                double u = x + 0.5;
                double v = y + 0.5;
                double w = h[6] * u + h[7] * v + 1;
                int px = (int) Math.floor((h[0] * u + h[1] * v + h[2]) / w);
                int py = (int) Math.floor((h[3] * u + h[4] * v + h[5]) / w);
                if (px < 0 || py < 0 || px >= image.width || py >= image.height) {
                    // A module just past the edge of the picture is taken from
                    // the edge; many of them means the guess was wrong.
                    if (++outside > dimension * 2) return null;
                    px = Math.max(0, Math.min(image.width - 1, px));
                    py = Math.max(0, Math.min(image.height - 1, py));
                }
                if (image.get(px, py)) grid.set(x, y, true);
            }
        }
        return grid;
    }

    /**
     * The projective transform taking the four `from` points to the four `to`
     * points, as {a, b, c, d, e, f, g, h} with x = (a u + b v + c) / (g u + h v + 1)
     * and y = (d u + e v + f) / (g u + h v + 1); null when they are degenerate.
     */
    static double[] homography(double[] from, double[] to) {
        double[][] m = new double[8][9];
        for (int k = 0; k < 4; k++) {
            double u = from[2 * k];
            double v = from[2 * k + 1];
            double x = to[2 * k];
            double y = to[2 * k + 1];
            m[2 * k] = new double[] {u, v, 1, 0, 0, 0, -u * x, -v * x, x};
            m[2 * k + 1] = new double[] {0, 0, 0, u, v, 1, -u * y, -v * y, y};
        }
        for (int column = 0; column < 8; column++) {
            int pivot = column;
            for (int row = column + 1; row < 8; row++) {
                if (Math.abs(m[row][column]) > Math.abs(m[pivot][column])) pivot = row;
            }
            if (Math.abs(m[pivot][column]) < 1e-9) return null;
            double[] swap = m[column];
            m[column] = m[pivot];
            m[pivot] = swap;
            for (int row = 0; row < 8; row++) {
                if (row == column) continue;
                double factor = m[row][column] / m[column][column];
                if (factor == 0) continue;
                for (int k = column; k < 9; k++) m[row][k] -= factor * m[column][k];
            }
        }
        double[] h = new double[8];
        for (int k = 0; k < 8; k++) h[k] = m[k][8] / m[k][k];
        return h;
    }

    private float moduleSize(Finder.Pattern pattern, Finder.Pattern other) {
        float one = runBothWays((int) pattern.x, (int) pattern.y, (int) other.x, (int) other.y);
        float two = runBothWays((int) other.x, (int) other.y, (int) pattern.x, (int) pattern.y);
        if (Float.isNaN(one)) return two / 7f;
        if (Float.isNaN(two)) return one / 7f;
        return (one + two) / 14f;
    }

    /** The width of a finder pattern measured through its centre toward and away from another point. */
    private float runBothWays(int fromX, int fromY, int toX, int toY) {
        float result = run(fromX, fromY, toX, toY);
        float scale = 1f;
        int otherX = fromX - (toX - fromX);
        if (otherX < 0) {
            scale = fromX / (float) (fromX - otherX);
            otherX = 0;
        } else if (otherX >= image.width) {
            scale = (image.width - 1 - fromX) / (float) (otherX - fromX);
            otherX = image.width - 1;
        }
        int otherY = (int) (fromY - (toY - fromY) * scale);
        scale = 1f;
        if (otherY < 0) {
            scale = fromY / (float) (fromY - otherY);
            otherY = 0;
        } else if (otherY >= image.height) {
            scale = (image.height - 1 - fromY) / (float) (otherY - fromY);
            otherY = image.height - 1;
        }
        otherX = (int) (fromX + (otherX - fromX) * scale);
        result += run(fromX, fromY, otherX, otherY);
        return result - 1f;
    }

    /** How far from (fromX, fromY) the line toward (toX, toY) passes dark, light, then dark again. */
    private float run(int fromX, int fromY, int toX, int toY) {
        boolean steep = Math.abs(toY - fromY) > Math.abs(toX - fromX);
        if (steep) {
            int swap = fromX; fromX = fromY; fromY = swap;
            swap = toX; toX = toY; toY = swap;
        }
        int dx = Math.abs(toX - fromX);
        int dy = Math.abs(toY - fromY);
        int error = -dx / 2;
        int xStep = fromX < toX ? 1 : -1;
        int yStep = fromY < toY ? 1 : -1;
        int state = 0;
        int xLimit = toX + xStep;
        for (int x = fromX, y = fromY; x != xLimit; x += xStep) {
            int realX = steep ? y : x;
            int realY = steep ? x : y;
            if (realX < 0 || realY < 0 || realX >= image.width || realY >= image.height) break;
            if ((state == 1) == image.get(realX, realY)) {
                if (state == 2) return distance(x, y, fromX, fromY);
                state++;
            }
            error += dy;
            if (error > 0) {
                if (y == toY) break;
                y += yStep;
                error -= dx;
            }
        }
        if (state == 2) return distance(toX + xStep, toY, fromX, fromY);
        return Float.NaN;
    }

    private static float distance(float ax, float ay, float bx, float by) {
        float x = ax - bx;
        float y = ay - by;
        return (float) Math.sqrt(x * x + y * y);
    }

    /** The alignment pattern's centre near (x, y), within `factor` modules; or null. */
    private float[] alignmentIn(float moduleSize, int x, int y, int factor) {
        int allowance = (int) (factor * moduleSize);
        int left = Math.max(0, x - allowance);
        int right = Math.min(image.width - 1, x + allowance);
        if (right - left < moduleSize * 3) return null;
        int top = Math.max(0, y - allowance);
        int bottom = Math.min(image.height - 1, y + allowance);
        if (bottom - top < moduleSize * 3) return null;
        return new Alignment(image, left, top, right - left, bottom - top, moduleSize).find();
    }

    /**
     * Finds an alignment pattern, a dark module ringed by light inside a dark
     * ring, by its light, dark, light runs of one module each, scanning from
     * the middle of the area outward. As ZXing's AlignmentPatternFinder.
     */
    static final class Alignment {
        private final BitMatrix image;
        private final int startX;
        private final int startY;
        private final int width;
        private final int height;
        private final float moduleSize;
        private final List<float[]> centres = new ArrayList<>();
        private final int[] counts = new int[3];

        Alignment(BitMatrix image, int startX, int startY, int width, int height, float moduleSize) {
            this.image = image;
            this.startX = startX;
            this.startY = startY;
            this.width = width;
            this.height = height;
            this.moduleSize = moduleSize;
        }

        float[] find() {
            int maxJ = startX + width;
            int middleI = startY + height / 2;
            int[] state = new int[3];
            for (int k = 0; k < height; k++) {
                int i = middleI + ((k & 1) == 0 ? (k + 1) / 2 : -((k + 1) / 2));
                if (i < 0 || i >= image.height) continue;
                state[0] = state[1] = state[2] = 0;
                int j = startX;
                while (j < maxJ && !image.get(j, i)) j++;
                int current = 0;
                while (j < maxJ) {
                    if (image.get(j, i)) {
                        if (current == 1) {
                            state[1]++;
                        } else if (current == 2) {
                            if (crossed(state)) {
                                float[] found = candidate(state, i, j);
                                if (found != null) return found;
                            }
                            state[0] = state[2];
                            state[1] = 1;
                            state[2] = 0;
                            current = 1;
                        } else {
                            state[++current]++;
                        }
                    } else {
                        if (current == 1) current++;
                        state[current]++;
                    }
                    j++;
                }
                if (crossed(state)) {
                    float[] found = candidate(state, i, maxJ);
                    if (found != null) return found;
                }
            }
            return centres.isEmpty() ? null : new float[] {centres.get(0)[0], centres.get(0)[1]};
        }

        private boolean crossed(int[] state) {
            float variance = moduleSize / 2f;
            for (int k = 0; k < 3; k++) {
                if (Math.abs(moduleSize - state[k]) >= variance) return false;
            }
            return true;
        }

        private static float centreFromEnd(int[] state, int end) {
            return (end - state[2]) - state[1] / 2f;
        }

        private float[] candidate(int[] state, int i, int j) {
            int total = state[0] + state[1] + state[2];
            float centreJ = centreFromEnd(state, j);
            float centreI = vertical(i, (int) centreJ, 2 * state[1], total);
            if (Float.isNaN(centreI)) return null;
            float size = total / 3f;
            for (float[] centre : centres) {
                if (Math.abs(centreI - centre[1]) <= size && Math.abs(centreJ - centre[0]) <= size) {
                    float difference = Math.abs(size - centre[2]);
                    if (difference <= 1f || difference <= centre[2]) {
                        return new float[] {(centre[0] + centreJ) / 2f, (centre[1] + centreI) / 2f};
                    }
                }
            }
            centres.add(new float[] {centreJ, centreI, size});
            return null;
        }

        private float vertical(int startI, int centreJ, int maxCount, int originalTotal) {
            int maxI = image.height;
            counts[0] = counts[1] = counts[2] = 0;
            int i = startI;
            while (i >= 0 && image.get(centreJ, i) && counts[1] <= maxCount) { counts[1]++; i--; }
            if (i < 0 || counts[1] > maxCount) return Float.NaN;
            while (i >= 0 && !image.get(centreJ, i) && counts[0] <= maxCount) { counts[0]++; i--; }
            if (counts[0] > maxCount) return Float.NaN;
            i = startI + 1;
            while (i < maxI && image.get(centreJ, i) && counts[1] <= maxCount) { counts[1]++; i++; }
            if (i == maxI || counts[1] > maxCount) return Float.NaN;
            while (i < maxI && !image.get(centreJ, i) && counts[2] <= maxCount) { counts[2]++; i++; }
            if (counts[2] > maxCount) return Float.NaN;
            int total = counts[0] + counts[1] + counts[2];
            if (5 * Math.abs(total - originalTotal) >= 2 * originalTotal) return Float.NaN;
            return crossed(counts) ? centreFromEnd(counts, i) : Float.NaN;
        }
    }
}
