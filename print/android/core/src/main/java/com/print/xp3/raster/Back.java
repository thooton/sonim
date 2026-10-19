package com.print.xp3.raster;

/**
 * How a back side's image is turned before it is sent, so that it comes
 * out the right way up. Printers that feed the sheet over for the back say
 * which they need (`pwg-raster-document-sheet-back` and its kin), and the
 * answer depends on which edge the pages are bound on.
 */
public enum Back {
    NONE(1, 1),
    ROTATE(-1, -1),
    FLIP_VERTICAL(1, -1),
    MIRROR(-1, 1);

    /** What a PWG page header says was done: 1, or -1 for reversed. */
    public final int crossFeed;
    public final int feed;

    Back(int crossFeed, int feed) {
        this.crossFeed = crossFeed;
        this.feed = feed;
    }

    /** As CUPS reads them: `sheetBack` is normal, flipped, rotated or manual-tumble. */
    public static Back of(String sheetBack, int duplex) {
        if (duplex == Frame.ONE_SIDED) return NONE;
        boolean shortEdge = duplex == Frame.SHORT_EDGE;
        if ("flipped".equals(sheetBack)) return shortEdge ? MIRROR : FLIP_VERTICAL;
        if ("rotated".equals(sheetBack)) return shortEdge ? NONE : ROTATE;
        if ("manual-tumble".equals(sheetBack)) return shortEdge ? ROTATE : NONE;
        return NONE;
    }
}
