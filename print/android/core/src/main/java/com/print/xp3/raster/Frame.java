package com.print.xp3.raster;

/**
 * The page images of one job: every page the same size, resolution and
 * colour. Rows are 8-bit grey (0 black, 255 white) or 8-bit RGB, top to
 * bottom, `width` pixels each.
 */
public final class Frame {

    public static final int ONE_SIDED = 0;
    public static final int LONG_EDGE = 1;
    public static final int SHORT_EDGE = 2;

    public final int width;
    public final int height;
    public final int dpi;
    public final boolean color;
    /** The paper, in points (1/72 inch). */
    public final double widthPoints;
    public final double heightPoints;
    /** The paper's PWG name ("na_letter_8.5x11in"), or "". */
    public final String media;
    public final int duplex;
    /** Pages in the document as sent. */
    public final int pages;
    /** Copies each page header asks for (1 when copies are made another way). */
    public final int copies;

    public Frame(double widthPoints, double heightPoints, int dpi, boolean color, String media,
                 int duplex, int pages, int copies) {
        this.widthPoints = widthPoints;
        this.heightPoints = heightPoints;
        this.dpi = dpi;
        this.color = color;
        this.media = media == null ? "" : media;
        this.duplex = duplex;
        this.pages = pages;
        this.copies = Math.max(1, copies);
        this.width = (int) Math.round(widthPoints * dpi / 72.0);
        this.height = (int) Math.round(heightPoints * dpi / 72.0);
    }

    public int channels() {
        return color ? 3 : 1;
    }

    public int bytesPerLine() {
        return width * channels();
    }

    /** Whether the page at `index` (counting from 0 in the document as sent) is a sheet's back. */
    public boolean back(int index) {
        return duplex != ONE_SIDED && index % 2 == 1;
    }
}
