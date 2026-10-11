package com.canvas.xp3.net;

/** Validates the remote service's Cookie header. Nothing is stored locally. */
public final class Cookies {

    private Cookies() {}

    public static String parse(String text) {
        String value = text.trim();
        if (value.regionMatches(true, 0, "Cookie:", 0, 7)) value = value.substring(7).trim();
        if (value.length() == 0) {
            throw new IllegalArgumentException("Expected one nonempty Cookie header line");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126) {
                throw new IllegalArgumentException("Expected one nonempty Cookie header line");
            }
        }
        String[] pairs = value.split(";", -1);
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int equals = pair.indexOf('=');
            if (equals < 1 || !pair.substring(0, equals).trim().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new IllegalArgumentException("Invalid cookie header format");
            }
        }
        return value;
    }
}
