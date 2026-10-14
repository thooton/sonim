package com.delta.xp3.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Files sent to the agent with a message.
 *
 * The files themselves go to the command center first, from the Files tab or
 * the Session tab's "Attach file"; the message only names them, in a block at
 * its end that tells the agent to fetch each with `delta pull`. Like the setup
 * block, it is fenced in a tag so the transcript can turn it back into a
 * short line for the phone's screen.
 */
public final class Attached {

    static final String OPEN = "<delta-files>";
    static final String CLOSE = "</delta-files>";
    private static final String ITEM = "- ";

    private Attached() {}

    /** What was typed, then a block naming `names`; just what was typed when there are none. */
    public static String wrap(String typed, List<String> names) {
        String text = typed == null ? "" : typed.trim();
        if (names == null || names.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text);
        if (text.length() > 0) out.append("\n\n");
        out.append(OPEN).append('\n');
        out.append(names.size() == 1 ? "The user sent you this file" : "The user sent you these files");
        out.append(" from their phone. ").append(names.size() == 1 ? "It is" : "They are");
        out.append(" on the command center; fetch ").append(names.size() == 1 ? "it" : "each one");
        out.append(" with `delta pull <name>`:\n");
        for (String name : names) out.append(ITEM).append(name).append('\n');
        out.append(CLOSE);
        return out.toString();
    }

    /** The names in a message's block, in order; none when it has no block. */
    public static List<String> names(String text) {
        List<String> names = new ArrayList<String>();
        int open = text == null ? -1 : text.lastIndexOf(OPEN);
        if (open < 0) return names;
        int close = text.indexOf(CLOSE, open);
        if (close < 0) return names;
        for (String line : text.substring(open + OPEN.length(), close).split("\n")) {
            if (line.startsWith(ITEM) && line.length() > ITEM.length()) names.add(line.substring(ITEM.length()));
        }
        return names;
    }

    /**
     * A message as the phone shows it: the block, which must end the
     * message, becomes one "Attached: a.pdf, b.mp3" line.
     */
    public static String show(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        int open = trimmed.lastIndexOf(OPEN);
        if (open < 0 || !trimmed.endsWith(CLOSE)) return text;
        List<String> names = names(trimmed);
        String typed = trimmed.substring(0, open).trim();
        if (names.isEmpty()) return typed;
        return line(typed, names);
    }

    /** What was typed, then the "Attached:" line for `names`. */
    public static String line(String typed, List<String> names) {
        String text = typed == null ? "" : typed.trim();
        if (names.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text);
        if (text.length() > 0) out.append("\n\n");
        out.append("Attached: ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(names.get(i));
        }
        return out.toString();
    }
}
