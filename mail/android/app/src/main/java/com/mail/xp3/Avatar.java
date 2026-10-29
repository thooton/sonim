package com.mail.xp3;

/**
 * What a person's monogram shows, as Thunderbird's avatar circle does: the
 * first letter of their name, or of their address when there is no name.
 */
final class Avatar {

    private Avatar() {}

    /** "Desert Financial" → "D", "jane@example.com" → "J", nothing → "?". */
    static String letter(String name) {
        String clean = name == null ? "" : name.replaceAll("[\"'(),.<>]", " ").trim();
        int at = clean.indexOf('@');
        if (at > 0 && clean.indexOf(' ') < 0) clean = clean.substring(0, at);
        for (String word : clean.split("\\s+")) {
            if (word.length() == 0) continue;
            int point = word.codePointAt(0);
            if (Character.isLetterOrDigit(point)) return new String(Character.toChars(Character.toUpperCase(point)));
        }
        return "?";
    }
}
