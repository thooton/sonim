package com.source.xp3.data;

import java.util.ArrayList;
import java.util.List;

/**
 * What a drawn cover shows, since Soulseek and the book source send no
 * artwork: up to two initials from a name, and which of the cover tones to
 * draw them on, the same each time for the same name.
 */
public final class Monogram {

    private Monogram() {}

    /**
     * "Slow Orbit" is "SO", "01 - the cure" is "TC", "Untitled" is "U".
     * Leading track numbers and anything that is not a letter or digit are
     * passed over, and a name with no letters at all falls back to its digits.
     */
    public static String initials(String name) {
        List<String> words = new ArrayList<String>();
        List<String> numbers = new ArrayList<String>();
        if (name != null) {
            StringBuilder word = new StringBuilder();
            for (int i = 0; i <= name.length(); i++) {
                int c = i < name.length() ? name.codePointAt(i) : ' ';
                if (c > 0xFFFF) i++;
                if (Character.isLetterOrDigit(c)) {
                    word.appendCodePoint(c);
                    continue;
                }
                if (word.length() == 0) continue;
                String done = word.toString();
                word.setLength(0);
                boolean digits = true;
                for (int j = 0; j < done.length(); j++) digits &= Character.isDigit(done.charAt(j));
                (digits ? numbers : words).add(done);
            }
        }
        List<String> from = words.isEmpty() ? numbers : words;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < from.size() && i < 2; i++) out.appendCodePoint(from.get(i).codePointAt(0));
        return out.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /** Which of `tones` cover tones `name` is drawn in. */
    public static int tone(String name, int tones) {
        int hash = name == null ? 0 : name.hashCode();
        return ((hash % tones) + tones) % tones;
    }
}
