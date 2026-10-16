package com.books.xp3.book;

/** A line of the book's table of contents. */
public final class TocEntry {

    public final String title;
    /** 0 for a top-level entry, 1 for one under it, and so on. */
    public final int depth;
    /** Where it points, as an offset into the book. */
    public final long offset;

    public TocEntry(String title, int depth, long offset) {
        this.title = title;
        this.depth = depth;
        this.offset = offset;
    }

    @Override
    public String toString() {
        return "TocEntry(" + title + ", " + depth + ", " + offset + ")";
    }
}
