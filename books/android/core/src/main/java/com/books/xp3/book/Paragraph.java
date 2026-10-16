package com.books.xp3.book;

/**
 * One paragraph of a section's text: where it is, and what kind it is.
 *
 * The text itself lives in the section, paragraphs joined by '\n', so a
 * paragraph is a range in it (`end` excludes the newline).
 */
public final class Paragraph {

    public static final int BODY = 0;
    public static final int HEADING = 1;
    public static final int QUOTE = 2;
    public static final int ITEM = 3;
    /** A scene break: an `<hr>`, or a line of asterisks in plain text. */
    public static final int BREAK = 4;
    /** Preformatted text: verse, code, a letter's address block. */
    public static final int PRE = 5;

    public final int start;
    public final int end;
    public final int kind;
    /** For a heading, 1 to 6 as in `<h1>`..`<h6>`; else 0. */
    public final int level;
    public final boolean centered;
    /**
     * Whether this paragraph opens a new stretch of text: the first of a
     * chapter, or the first after a heading, a scene break, an empty
     * paragraph or a line break. Books set those flush left, without a
     * first-line indent.
     */
    public final boolean opening;
    /**
     * Whether a blank line comes before it: the book left an empty paragraph
     * or a pair of line breaks there, which reads as a pause in the text.
     */
    public final boolean spaced;

    public Paragraph(int start, int end, int kind, int level, boolean centered, boolean opening, boolean spaced) {
        this.start = start;
        this.end = end;
        this.kind = kind;
        this.level = level;
        this.centered = centered;
        this.opening = opening;
        this.spaced = spaced;
    }

    Paragraph shift(int by) {
        return new Paragraph(start - by, end - by, kind, level, centered, opening, spaced);
    }

    @Override
    public String toString() {
        return "Paragraph(" + start + ".." + end + ", kind " + kind + ")";
    }
}
