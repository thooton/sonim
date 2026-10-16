package com.books.xp3.book;

import java.util.List;

/**
 * A piece of a chapter small enough to lay out at once.
 *
 * Laying text out is the slow part of paging on the phone, and a long chapter
 * (or a plain text file with no headings at all) can run to hundreds of
 * thousands of characters. So chapters are cut, between paragraphs and where
 * the text pauses if it can, into sections of a few dozen pages each.
 */
public final class Section {

    /** The paragraphs' text, joined by '\n'. */
    public final String text;
    public final List<Paragraph> paragraphs;
    public final List<Run> runs;
    /** Which chapter (spine document) this is part of. */
    public final int chapter;
    /** Where it starts in the book, counted in characters of section text. */
    public final long start;
    /** Where it starts in its chapter's text. */
    final int chapterStart;

    Section(String text, List<Paragraph> paragraphs, List<Run> runs, int chapter, long start, int chapterStart) {
        this.text = text;
        this.paragraphs = paragraphs;
        this.runs = runs;
        this.chapter = chapter;
        this.start = start;
        this.chapterStart = chapterStart;
    }

    public int length() {
        return text.length();
    }

    /** Whether this is the first section of its chapter. */
    public boolean opensChapter() {
        return chapterStart == 0;
    }
}
