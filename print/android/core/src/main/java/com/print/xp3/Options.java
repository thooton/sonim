package com.print.xp3;

import com.print.xp3.raster.Frame;

/** What the person asked for. */
public final class Options {

    public int copies = 1;
    /** Pages from and to, counting from 1; both inclusive. */
    public int from = 1;
    public int to = 1;
    public boolean color = true;
    public int duplex = Frame.ONE_SIDED;
    public String media = Media.LETTER;
    /** Send page images even to a printer that takes PDF. */
    public boolean images;

    public boolean allPages(int pageCount) {
        return from <= 1 && to >= pageCount;
    }
}
