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

    /**
     * How good `match` (from {@link #match}) is, for ordering results: 0 at
     * the start of the name, 1 at the start of a later word, 2 inside one.
     */
    public static int rank(String name, int[] match) {
        if (match[0] == 0) return 0;
        return Character.isLetterOrDigit(name.charAt(match[0] - 1)) ? 2 : 1;
    }

    /**
     * Names as a person orders them: case aside, and runs of digits as
     * numbers, so "Scan_2" comes before "Scan_10".
     */
    public static int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i);
            char y = b.charAt(j);
            if (isDigit(x) && isDigit(y)) {
                int endA = i;
                int endB = j;
                while (endA < a.length() && isDigit(a.charAt(endA))) endA++;
                while (endB < b.length() && isDigit(b.charAt(endB))) endB++;
                // Leading zeros aside, the longer run is the larger number.
                int startA = i;
                int startB = j;
                while (startA < endA - 1 && a.charAt(startA) == '0') startA++;
                while (startB < endB - 1 && b.charAt(startB) == '0') startB++;
                int length = (endA - startA) - (endB - startB);
                if (length != 0) return length;
                for (int k = 0; k < endA - startA; k++) {
                    int digit = a.charAt(startA + k) - b.charAt(startB + k);
                    if (digit != 0) return digit;
                }
                i = endA;
                j = endB;
                continue;
            }
            int letter = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
            if (letter != 0) return letter;
            i++;
            j++;
        }
        int rest = (a.length() - i) - (b.length() - j);
        return rest != 0 ? rest : a.compareToIgnoreCase(b) != 0 ? a.compareToIgnoreCase(b) : a.compareTo(b);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
