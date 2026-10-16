package com.books.xp3.book;

/** A stretch of a section's text set in italic, bold or both. */
public final class Run {

    public static final int ITALIC = 1;
    public static final int BOLD = 2;

    public final int start;
    public final int end;
    /** ITALIC, BOLD or both; never 0. */
    public final int style;

    public Run(int start, int end, int style) {
        this.start = start;
        this.end = end;
        this.style = style;
    }

    Run shift(int by) {
        return new Run(start - by, end - by, style);
    }

    @Override
    public String toString() {
        return "Run(" + start + ".." + end + ", style " + style + ")";
    }
}
