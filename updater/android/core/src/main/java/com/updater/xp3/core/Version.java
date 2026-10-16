package com.updater.xp3.core;

/**
 * Orders version names such as "1.0.2" and "1.1.0".
 *
 * The parts are compared one by one, numbers as numbers, so 1.10.0 is newer
 * than 1.9.0. A missing part counts as 0, so "1.0" and "1.0.0" are the same
 * version. A word part ("1.0.0-beta") is older than a number in its place, so
 * a release outranks its own pre-release. Nothing here throws: a name that is
 * null or empty is the oldest version there is.
 */
public final class Version {

    private Version() {}

    /** Negative, zero or positive as `a` is older than, the same as, or newer than `b`. */
    public static int compare(String a, String b) {
        String[] x = parts(a);
        String[] y = parts(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int c = comparePart(i < x.length ? x[i] : "0", i < y.length ? y[i] : "0");
            if (c != 0) return c < 0 ? -1 : 1;
        }
        return 0;
    }

    private static String[] parts(String version) {
        String text = version == null ? "" : version.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        if (text.isEmpty()) return new String[0];
        return text.split("[.\\-+_ ]+");
    }

    private static int comparePart(String p, String q) {
        boolean pNumber = numeric(p);
        boolean qNumber = numeric(q);
        if (pNumber && qNumber) {
            // As digit strings, so no part is ever too long to compare.
            String a = stripZeros(p);
            String b = stripZeros(q);
            if (a.length() != b.length()) return a.length() - b.length();
            return a.compareTo(b);
        }
        if (pNumber) return 1;
        if (qNumber) return -1;
        return p.compareToIgnoreCase(q);
    }

    private static boolean numeric(String part) {
        if (part.isEmpty()) return false;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static String stripZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') i++;
        return digits.substring(i);
    }
}
