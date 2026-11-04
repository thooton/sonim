package com.musica.xp3;

import java.text.Collator;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Titles and artists from file names, and the A to Z order of songs. */
public final class Names {

    private Names() {}

    private static final Pattern TRACK = Pattern.compile("^(?:\\d{1,3}\\s*[-.)]\\s+|0\\d\\s+)(?=\\S)");

    /**
     * A file name (without its extension) as {title, artist}: "Artist -
     * Title" is split, a leading track number dropped. The artist is "" when
     * the name doesn't give one.
     */
    public static String[] split(String base) {
        String name = base.replace('_', ' ').trim();
        String stripped = TRACK.matcher(name).replaceFirst("");
        if (!stripped.isEmpty()) name = stripped;
        int dash = name.indexOf(" - ");
        if (dash < 0) dash = name.indexOf(" – ");
        if (dash > 0 && dash + 3 < name.length()) {
            String artist = name.substring(0, dash).trim();
            String title = name.substring(dash + 3).trim();
            if (!artist.isEmpty() && !title.isEmpty()) return new String[] {title, artist};
        }
        return new String[] {name, ""};
    }

    /** A name without its last extension. */
    public static String base(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /** The extension in lower case, without the dot; "" if none. */
    public static String extension(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static final Collator SPANISH = Collator.getInstance(new Locale("es"));
    static {
        SPANISH.setStrength(Collator.PRIMARY);
    }

    /**
     * Compares titles as a Spanish speaker would sort them: accents and case
     * don't count, "¡" and "¿" and a leading "El"/"La"/"The" are ignored.
     */
    public static int compare(String a, String b) {
        int c;
        synchronized (SPANISH) {
            c = SPANISH.compare(sortKey(a), sortKey(b));
        }
        return c != 0 ? c : a.compareTo(b);
    }

    static String sortKey(String title) {
        String t = title.trim();
        while (!t.isEmpty() && "¡¿\"'([«".indexOf(t.charAt(0)) >= 0) t = t.substring(1).trim();
        String lower = t.toLowerCase(Locale.ROOT);
        for (String article : new String[] {"the ", "el ", "la ", "los ", "las "}) {
            if (lower.startsWith(article) && t.length() > article.length()) {
                return t.substring(article.length()).trim();
            }
        }
        return t;
    }

    /** Lower case without accents, for matching a file to its lyrics. */
    public static String fold(String s) {
        String d = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char ch = d.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) out.append(ch);
        }
        return out.toString().toLowerCase(Locale.ROOT).trim();
    }
}
