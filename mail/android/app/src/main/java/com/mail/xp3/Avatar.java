package com.mail.xp3;

/**
 * What a person's monogram shows, as Thunderbird's avatar circle does: the
 * first letter of their name, or of their address when there is no name.
 */
final class Avatar {

    private Avatar() {}

    /** "Desert Financial" → "D", "jane@example.com" → "J", nothing → "?". */
    static String letter(String name) {
        String all = initials(name);
        return all.substring(0, Character.charCount(all.codePointAt(0)));
    }

    /** "Desert Financial" → "DF", "jane@example.com" → "J". */
    static String initials(String name) {
        String clean = name == null ? "" : name.replaceAll("[\"'(),.<>]", " ").trim();
        int at = clean.indexOf('@');
        if (at > 0 && clean.indexOf(' ') < 0) clean = clean.substring(0, at);
        StringBuilder out = new StringBuilder();
        for (String word : clean.split("\\s+")) {
            if (word.length() == 0) continue;
            int point = word.codePointAt(0);
            if (!Character.isLetterOrDigit(point)) continue;
            out.appendCodePoint(Character.toUpperCase(point));
            if (out.codePointCount(0, out.length()) >= 2) break;
        }
        return out.length() == 0 ? "?" : out.toString();
    }
}
