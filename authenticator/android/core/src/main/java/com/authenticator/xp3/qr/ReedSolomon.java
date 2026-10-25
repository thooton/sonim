package com.authenticator.xp3.qr;

/**
 * Reed-Solomon error correction over GF(256) as QR codes use it: the field's
 * polynomial is x^8 + x^4 + x^3 + x^2 + 1 and the generator's roots start at
 * alpha^0. Errors are found with Berlekamp-Massey and a Chien search, and
 * mended with Forney's formula.
 */
final class ReedSolomon {

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private ReedSolomon() {}

    static int multiply(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    static int divide(int a, int b) {
        if (b == 0) throw new ArithmeticException();
        if (a == 0) return 0;
        return EXP[LOG[a] + 255 - LOG[b]];
    }

    /** alpha^power, for any power. */
    static int power(int power) {
        return EXP[((power % 255) + 255) % 255];
    }

    /**
     * Mends `codewords` (data then `ecCount` check bytes, each 0 to 255) in
     * place; false when there are more errors than the code can mend.
     */
    static boolean correct(int[] codewords, int ecCount) {
        int n = codewords.length;
        int[] syndromes = syndromes(codewords, ecCount);
        boolean clean = true;
        for (int s : syndromes) if (s != 0) clean = false;
        if (clean) return true;

        // Berlekamp-Massey: the error locator, lowest power first.
        int[] locator = new int[ecCount + 1];
        int[] previous = new int[ecCount + 1];
        locator[0] = 1;
        previous[0] = 1;
        int errors = 0;
        int shift = 1;
        int lastDiscrepancy = 1;
        for (int step = 0; step < ecCount; step++) {
            int discrepancy = syndromes[step];
            for (int i = 1; i <= errors; i++) discrepancy ^= multiply(locator[i], syndromes[step - i]);
            if (discrepancy == 0) {
                shift++;
                continue;
            }
            int factor = divide(discrepancy, lastDiscrepancy);
            if (2 * errors <= step) {
                int[] saved = locator.clone();
                for (int i = 0; i + shift <= ecCount; i++) locator[i + shift] ^= multiply(factor, previous[i]);
                errors = step + 1 - errors;
                previous = saved;
                lastDiscrepancy = discrepancy;
                shift = 1;
            } else {
                for (int i = 0; i + shift <= ecCount; i++) locator[i + shift] ^= multiply(factor, previous[i]);
                shift++;
            }
        }
        if (2 * errors > ecCount) return false;

        // The evaluator: syndromes times locator, mod x^ecCount.
        int[] evaluator = new int[ecCount];
        for (int i = 0; i < ecCount; i++) {
            int sum = 0;
            for (int j = 0; j <= i && j <= errors; j++) sum ^= multiply(locator[j], syndromes[i - j]);
            evaluator[i] = sum;
        }

        int found = 0;
        for (int k = 0; k < n; k++) {
            int exponent = n - 1 - k;
            int inverse = power(-exponent);
            if (evaluate(locator, errors, inverse) != 0) continue;
            found++;
            // Forney: the magnitude is X * evaluator(1/X) / locator'(1/X).
            int numerator = evaluate(evaluator, ecCount - 1, inverse);
            int denominator = 0;
            for (int i = 1; i <= errors; i += 2) denominator ^= multiply(locator[i], power(-exponent * (i - 1)));
            if (denominator == 0) return false;
            codewords[k] ^= multiply(power(exponent), divide(numerator, denominator));
        }
        if (found != errors) return false;
        for (int s : syndromes(codewords, ecCount)) if (s != 0) return false;
        return true;
    }

    private static int[] syndromes(int[] codewords, int ecCount) {
        int[] syndromes = new int[ecCount];
        for (int i = 0; i < ecCount; i++) {
            int root = power(i);
            int value = 0;
            for (int c : codewords) value = multiply(value, root) ^ c;
            syndromes[i] = value;
        }
        return syndromes;
    }

    /** A polynomial, lowest power first, up to `degree`, at x. */
    private static int evaluate(int[] polynomial, int degree, int x) {
        int value = 0;
        for (int i = Math.min(degree, polynomial.length - 1); i >= 0; i--) value = multiply(value, x) ^ polynomial[i];
        return value;
    }

    /** The check bytes for `data`, for the tests. */
    static int[] encode(int[] data, int ecCount) {
        int[] generator = {1};
        for (int i = 0; i < ecCount; i++) {
            int[] next = new int[generator.length + 1];
            for (int j = 0; j < generator.length; j++) {
                next[j] ^= generator[j];
                next[j + 1] ^= multiply(generator[j], power(i));
            }
            generator = next;
        }
        int[] remainder = new int[ecCount];
        for (int d : data) {
            int factor = d ^ remainder[0];
            System.arraycopy(remainder, 1, remainder, 0, ecCount - 1);
            remainder[ecCount - 1] = 0;
            for (int j = 0; j < ecCount; j++) remainder[j] ^= multiply(generator[j + 1], factor);
        }
        return remainder;
    }
}
