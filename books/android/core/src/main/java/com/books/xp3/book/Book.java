package com.books.xp3.book;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A whole book, read in and ready to lay out: its sections in reading order
 * and its table of contents.
 *
 * A place in the book is one number, an offset counted through the sections'
 * text. It does not depend on the font or the text size, so it is what
 * reading progress is saved as, and it survives any change of settings.
 */
public final class Book {

    /** A section is cut once it passes this many characters... */
    static final int SECTION_TARGET = 14000;
    /** ...at a pause in the text if one comes before this many... */
    static final int SECTION_MAX = 22000;
    /** ...and never shorter than this. */
    static final int SECTION_MIN = 8000;

    public final String title;
    public final String author;
    public final List<Section> sections;
    public final List<TocEntry> contents;
    public final long length;

    private Book(String title, String author, List<Section> sections, List<TocEntry> contents) {
        this.title = title;
        this.author = author;
        this.sections = Collections.unmodifiableList(sections);
        this.contents = Collections.unmodifiableList(contents);
        long total = 0;
        for (Section section : sections) total += section.length();
        this.length = total;
    }

    /** The section that holds `offset`; offsets past the end land in the last one. */
    public int sectionAt(long offset) {
        int low = 0;
        int high = sections.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (sections.get(middle).start <= offset) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    /** The contents entry `offset` falls under, or -1 if it comes before them all. */
    public int contentsAt(long offset) {
        int found = -1;
        for (int i = 0; i < contents.size(); i++) {
            if (contents.get(i).offset <= offset) found = i;
            else break;
        }
        return found;
    }

    /** Where the contents entry after the one at `offset` starts, or the end of the book. */
    public long nextContentsOffset(long offset) {
        for (TocEntry entry : contents) {
            if (entry.offset > offset) return entry.offset;
        }
        return length;
    }

    // ------------------------------------------------------------------
    // Assembly
    // ------------------------------------------------------------------

    /** A contents line as the book gives it: a chapter, and an anchor in it. */
    static final class Link {
        final String title;
        final int depth;
        final int chapter;
        final String anchor;

        Link(String title, int depth, int chapter, String anchor) {
            this.title = title;
            this.depth = depth;
            this.chapter = chapter;
            this.anchor = anchor;
        }
    }

    /**
     * Cuts the chapters into sections and resolves the contents. With no
     * usable contents, each chapter's first heading stands in for them.
     */
    static Book assemble(String title, String author, List<Chapter> chapters, List<Link> links) {
        List<Section> sections = new ArrayList<Section>();
        long at = 0;
        for (int c = 0; c < chapters.size(); c++) {
            for (Section section : split(chapters.get(c), c, at)) {
                sections.add(section);
                at += section.length();
            }
        }
        if (sections.isEmpty()) {
            List<Paragraph> paragraphs = new ArrayList<Paragraph>();
            String empty = "This book has no text that can be shown.";
            paragraphs.add(new Paragraph(0, empty.length(), Paragraph.BODY, 0, true, true, false));
            sections.add(new Section(empty, paragraphs, new ArrayList<Run>(), 0, 0, 0));
        }

        List<TocEntry> contents = new ArrayList<TocEntry>();
        if (links != null) {
            for (Link link : links) {
                if (link.chapter < 0 || link.chapter >= chapters.size()) continue;
                String name = link.title == null ? "" : Chapter.clip(link.title.replaceAll("\\s+", " "), 80);
                if (name.length() == 0) continue;
                int offset = 0;
                if (link.anchor != null) {
                    Integer found = chapters.get(link.chapter).anchors.get(link.anchor);
                    if (found != null) offset = found.intValue();
                }
                contents.add(new TocEntry(name, link.depth, bookOffset(sections, link.chapter, offset)));
            }
        }
        // Some books' contents are a single "Start"; their headings do better.
        if (contents.size() <= 1) {
            List<TocEntry> headings = new ArrayList<TocEntry>();
            for (int c = 0; c < chapters.size(); c++) {
                String heading = chapters.get(c).title != null ? chapters.get(c).title : chapters.get(c).firstHeading();
                if (heading == null || chapters.get(c).isEmpty()) continue;
                headings.add(new TocEntry(heading, 0, bookOffset(sections, c, 0)));
            }
            if (contents.isEmpty() || headings.size() >= 3) contents = headings;
        }
        // Reading order: contents that point backwards (an EPUB's notes listed
        // before the text) are kept, but sorted so "where am I" is answerable.
        sortStable(contents);
        return new Book(title, author, sections, contents);
    }

    /** Where `offset` in chapter `chapter` is in the book. */
    static long bookOffset(List<Section> sections, int chapter, int offset) {
        Section best = null;
        for (Section section : sections) {
            if (section.chapter < chapter) continue;
            if (section.chapter > chapter) {
                // An empty chapter points at whatever follows it.
                if (best == null) return section.start;
                break;
            }
            if (best == null || section.chapterStart <= offset) best = section;
            else break;
        }
        if (best == null) {
            Section last = sections.get(sections.size() - 1);
            return last.start + last.length();
        }
        return best.start + Math.max(0, Math.min(offset - best.chapterStart, best.length()));
    }

    private static void sortStable(List<TocEntry> entries) {
        // Insertion sort: short lists, and stable without a Comparator class.
        for (int i = 1; i < entries.size(); i++) {
            TocEntry entry = entries.get(i);
            int j = i - 1;
            while (j >= 0 && entries.get(j).offset > entry.offset) {
                entries.set(j + 1, entries.get(j));
                j--;
            }
            entries.set(j + 1, entry);
        }
    }

    /** Cuts one chapter into sections. */
    static List<Section> split(Chapter chapter, int index, long bookStart) {
        List<Section> out = new ArrayList<Section>();
        List<Paragraph> paragraphs = chapter.paragraphs;
        if (paragraphs.isEmpty()) return out;
        String text = chapter.text.toString();
        int first = 0;
        long at = bookStart;
        while (first < paragraphs.size()) {
            int from = paragraphs.get(first).start;
            int cut = paragraphs.size();
            int lastEnd = paragraphs.get(paragraphs.size() - 1).end;
            if (lastEnd - from > SECTION_MAX) cut = cutPoint(paragraphs, first, from);
            int to = paragraphs.get(cut - 1).end;

            List<Paragraph> mine = new ArrayList<Paragraph>(cut - first);
            for (int i = first; i < cut; i++) mine.add(paragraphs.get(i).shift(from));
            List<Run> runs = new ArrayList<Run>();
            for (Run run : chapter.runs) {
                if (run.end <= from || run.start >= to) continue;
                runs.add(new Run(Math.max(run.start, from) - from, Math.min(run.end, to) - from, run.style));
            }
            Section section = new Section(text.substring(from, to), mine, runs, index, at, from);
            out.add(section);
            at += section.length();
            first = cut;
        }
        return out;
    }

    /**
     * The paragraph to start the next section at: the first one after a
     * pause (a heading, scene break or blank line) between SECTION_MIN and
     * SECTION_MAX characters in, else the first past SECTION_TARGET.
     */
    private static int cutPoint(List<Paragraph> paragraphs, int first, int from) {
        int fallback = -1;
        for (int i = first + 1; i < paragraphs.size(); i++) {
            Paragraph p = paragraphs.get(i);
            int size = p.start - from;
            if (size > SECTION_MAX) break;
            boolean pause = p.kind == Paragraph.HEADING || p.kind == Paragraph.BREAK || p.spaced;
            if (pause && size >= SECTION_MIN) return i;
            if (fallback < 0 && size >= SECTION_TARGET) fallback = i;
        }
        if (fallback > first) return fallback;
        // A paragraph so long it runs from before the target to past the
        // limit: it stays whole, and the cut comes after it.
        for (int i = first + 1; i < paragraphs.size(); i++) {
            if (paragraphs.get(i).start - from > SECTION_MAX) return i;
        }
        return paragraphs.size();
    }
}
