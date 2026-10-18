package com.tablet.xp3.data;

import java.util.Locale;
import java.util.TimeZone;

/**
 * One document on the tablet, as the command center lists it
 * (GET /remarkable) and the Library shows it.
 *
 * `folder` is where it is, in words ("School / Math", or "My files" at the
 * top), which Library fills in from the folders around it.
 */
public final class Doc {

    public static final String NOTEBOOK = "notebook";
    public static final String PDF = "pdf";
    public static final String EPUB = "epub";

    public final String id;
    public String name;
    /** notebook, pdf or epub, as the command center says. */
    public final String type;
    /** The tablet's count; 0 when it doesn't know (an EPUB never opened). */
    public final int pages;
    public final long size;
    /** Epoch milliseconds, or 0 when unknown. */
    public final long modified;
    /** The folder's id, "" at the top. */
    public String parent;
    public String folder = Library.TOP;

    public Doc(String id, String name, String type, int pages, long size, long modified, String parent) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.pages = pages;
        this.size = size;
        this.modified = modified;
        this.parent = parent;
    }

    /** "Notebook", "PDF" or "ePub", as the screens say it. */
    public String kind() {
        if (PDF.equals(type)) return "PDF";
        if (EPUB.equals(type)) return "ePub";
        return "Notebook";
    }

    /** The line under the selected cover: "Notebook · 12 pages · edited 2h ago". */
    public String meta(long now, TimeZone zone) {
        StringBuilder out = new StringBuilder(kind());
        if (pages > 0) out.append(" · ").append(pagesText());
        if (modified > 0) out.append(" · edited ").append(When.ago(modified, now, zone));
        return out.toString();
    }

    public String pagesText() {
        return pages + (pages == 1 ? " page" : " pages");
    }

    /** "1.9 MB", "640 KB". */
    public String sizeText() {
        if (size >= 1000000) return String.format(Locale.US, "%.1f MB", size / 1e6);
        if (size >= 1000) return Math.round(size / 1e3) + " KB";
        return size + " bytes";
    }
}
