package com.print.xp3;

import java.text.Normalizer;

/**
 * Searching names with the number keys, as a phone's contact search does:
 * each key stands for its letters (2 for a, b and c) and for itself, 0 for
 * a space. "532" finds "Lease agreement".
 */
public final class T9 {

    private T9() {}

    private static final String KEYS = "22233344455566677778889999";

    /** The key that types `c`, or 0 for anything else. */
    static char key(char c) {
        char lower = Character.toLowerCase(c);
        if (lower >= 'a' && lower <= 'z') return KEYS.charAt(lower - 'a');
        if (c >= '0' && c <= '9') return c;
        if (c == ' ') return '0';
        // An accented letter counts as its plain one.
        String plain = Normalizer.normalize(String.valueOf(lower), Normalizer.Form.NFD);
        if (!plain.isEmpty() && plain.charAt(0) >= 'a' && plain.charAt(0) <= 'z') return KEYS.charAt(plain.charAt(0) - 'a');
        return '1';
    }

    /**
     * Where `digits` match in `name`: start and end, or null. A match at
     * the start of a word is preferred to one inside a word.
     */
    public static int[] match(String name, String digits) {
        if (digits.isEmpty()) return null;
        StringBuilder keys = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) keys.append(key(name.charAt(i)));
        String all = keys.toString();
        int inside = -1;
        int at = all.indexOf(digits);
        while (at >= 0) {
            if (at == 0 || !Character.isLetterOrDigit(name.charAt(at - 1))) return new int[] {at, at + digits.length()};
            if (inside < 0) inside = at;
            at = all.indexOf(digits, at + 1);
        }
        return inside < 0 ? null : new int[] {inside, inside + digits.length()};
    }
}
