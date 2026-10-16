package com.books.xp3.book;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One chapter's text as it is being read in: a document of an EPUB's spine,
 * or a stretch of a plain text file between two headings.
 *
 * Text arrives in pieces, as the markup gives it, and is written down as a
 * browser would lay it out: runs of white space become one space, white space
 * at the edges of a paragraph goes, and a paragraph with nothing visible in
 * it leaves only a gap (the next paragraph opens flush left).
 */
final class Chapter {

    final StringBuilder text = new StringBuilder();
    final List<Paragraph> paragraphs = new ArrayList<Paragraph>();
    final List<Run> runs = new ArrayList<Run>();
    /** Element ids and where in the text they were found. */
    final Map<String, Integer> anchors = new HashMap<String, Integer>();
    /** The title the table of contents gives it, if any. */
    String title;

    private boolean open;
    private int start;
    private int kind;
    private int level;
    private boolean centered;
    private boolean opening;
    private boolean nextOpening = true;
    private boolean spaced;
    private boolean nextSpaced;
    private boolean pendingSpace;
    private int lastStyle;
    /** Where the '\n' before the open paragraph is, or -1 if there is none. */
    private int separator = -1;

    /**
     * Adds text, collapsing white space. The paragraph's kind is taken from
     * the first text that opens it.
     */
    void append(String piece, int style, int kind, int level, boolean centered) {
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\f' || c == '\u200B') {
                if (open) pendingSpace = true;
                continue;
            }
            if (!open) openParagraph(kind, level, centered);
            if (pendingSpace) {
                // After a no-break space (a list item's bullet) one space is
                // enough. A space between differently styled words is plain.
                if (text.charAt(text.length() - 1) != '\u00A0') put(' ', lastStyle == style ? style : 0);
                pendingSpace = false;
            }
            put(c, style);
        }
    }

    /** Adds text as it is, a newline in it ending the paragraph (for `<pre>`). */
    void appendVerbatim(String piece, int style, int kind) {
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            if (c == '\r') continue;
            if (c == '\n') {
                if (open) end();
                continue;
            }
            if (!open) {
                if (c == ' ' || c == '\t') continue;
                openParagraph(kind, 0, false);
            }
            put(c == '\t' ? ' ' : c, style);
        }
    }

    private void openParagraph(int kind, int level, boolean centered) {
        if (text.length() > 0) {
            separator = text.length();
            text.append('\n');
        } else {
            separator = -1;
        }
        open = true;
        start = text.length();
        this.kind = kind;
        this.level = level;
        this.centered = centered;
        opening = nextOpening;
        // A blank after a heading or scene break adds nothing: they have space of their own.
        spaced = nextSpaced && !paragraphs.isEmpty()
            && paragraphs.get(paragraphs.size() - 1).kind != Paragraph.HEADING
            && paragraphs.get(paragraphs.size() - 1).kind != Paragraph.BREAK;
        nextOpening = false;
        nextSpaced = false;
        pendingSpace = false;
    }

    private void put(char c, int style) {
        lastStyle = style;
        int at = text.length();
        text.append(c);
        if (style == 0) return;
        int last = runs.size() - 1;
        if (last >= 0) {
            Run run = runs.get(last);
            if (run.style == style && run.end == at) {
                runs.set(last, new Run(run.start, at + 1, style));
                return;
            }
        }
        runs.add(new Run(at, at + 1, style));
    }

    /** Ends the open paragraph, if there is one. */
    void end() {
        if (!open) return;
        open = false;
        pendingSpace = false;
        if (!visible(start, text.length())) {
            // `<p>&nbsp;</p>` and the like: a spacer, not a paragraph.
            int cut = separator >= 0 ? separator : start;
            truncate(cut);
            nextOpening = true;
            nextSpaced = true;
            return;
        }
        paragraphs.add(new Paragraph(start, text.length(), kind, level, centered, opening, spaced));
        if (kind == Paragraph.HEADING || kind == Paragraph.BREAK) nextOpening = true;
    }

    /** An empty line between paragraphs: the next one opens flush left, after a space. */
    void gap() {
        end();
        nextOpening = true;
        nextSpaced = true;
    }

    /**
     * A line break: the paragraph ends, and the next line starts flush left
     * with no space before it, as in verse. A second break in a row is a gap.
     */
    void lineBreak() {
        if (!open) {
            gap();
            return;
        }
        end();
        nextOpening = true;
    }

    /** A scene break, unless one would open the chapter or follow another. */
    void sceneBreak() {
        end();
        if (paragraphs.isEmpty() || paragraphs.get(paragraphs.size() - 1).kind == Paragraph.BREAK) {
            nextOpening = true;
            return;
        }
        nextSpaced = false;
        openParagraph(Paragraph.BREAK, 0, true);
        put('*', 0);
        put(' ', 0);
        put('*', 0);
        put(' ', 0);
        put('*', 0);
        end();
    }

    /** Remembers where an element id falls: at the next text to be written. */
    void anchor(String id) {
        if (id == null || id.length() == 0 || anchors.containsKey(id)) return;
        int at = open ? text.length() : text.length() + (text.length() > 0 ? 1 : 0);
        anchors.put(id, Integer.valueOf(at));
    }

    /** The text of the first heading, which serves as a title when the book has no contents. */
    String firstHeading() {
        StringBuilder out = new StringBuilder();
        for (Paragraph p : paragraphs) {
            if (p.kind != Paragraph.HEADING) {
                if (out.length() > 0) break;
                continue;
            }
            if (out.length() > 0) {
                // "Chapter One" over "The Beginning": one title, two parts.
                out.append(": ");
            }
            out.append(text, p.start, p.end);
            if (out.length() > 60) break;
        }
        return out.length() == 0 ? null : clip(out.toString(), 80);
    }

    /** Whether it has a heading with exactly this text. */
    boolean hasHeading(String value) {
        for (Paragraph p : paragraphs) {
            if (p.kind == Paragraph.HEADING && p.end - p.start == value.length()
                && text.indexOf(value, p.start) == p.start) {
                return true;
            }
        }
        return false;
    }

    /** Whether it has a paragraph that is not a heading. */
    boolean hasBody() {
        for (Paragraph p : paragraphs) if (p.kind != Paragraph.HEADING) return true;
        return false;
    }

    boolean isEmpty() {
        return paragraphs.isEmpty();
    }

    private boolean visible(int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\u00A0' && c != '\u2002' && c != '\u2003' && c != '\u2009' && c != '\u00AD') {
                return true;
            }
        }
        return false;
    }

    private void truncate(int length) {
        text.setLength(length);
        for (int i = runs.size() - 1; i >= 0; i--) {
            Run run = runs.get(i);
            if (run.end <= length) break;
            if (run.start >= length) runs.remove(i);
            else runs.set(i, new Run(run.start, length, run.style));
        }
    }

    static String clip(String value, int max) {
        String trimmed = value.trim();
        if (trimmed.length() <= max) return trimmed;
        return trimmed.substring(0, max - 1).trim() + "\u2026";
    }
}
