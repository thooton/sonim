package com.serve.xp3.net;

/** The one byte range a player asks for when it seeks. */
public final class Ranges {

    private Ranges() {}

    /**
     * A "Range: bytes=…" header against a file of this length: {start, end}
     * inclusive; an empty array to ignore it and send the whole file (a unit
     * other than bytes, several ranges, nonsense); null when it is a byte
     * range that misses the file (416).
     */
    public static long[] parse(String header, long length) {
        String value = header.trim();
        if (!value.startsWith("bytes=")) return new long[0];
        value = value.substring(6).trim();
        if (value.indexOf(',') >= 0) return new long[0];
        int dash = value.indexOf('-');
        if (dash < 0) return new long[0];
        String from = value.substring(0, dash).trim();
        String to = value.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (from.length() == 0) {
                if (to.length() == 0) return new long[0];
                long suffix = Long.parseLong(to);
                if (suffix <= 0 || length == 0) return null;
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(from);
                long last = to.length() == 0 ? Long.MAX_VALUE : Long.parseLong(to);
                if (start < 0 || last < start) return new long[0];
                if (start >= length) return null;
                end = Math.min(last, length - 1);
            }
            return new long[] {start, end};
        } catch (NumberFormatException e) {
            return new long[0];
        }
    }
}
