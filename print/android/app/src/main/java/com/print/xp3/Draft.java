package com.print.xp3;

import com.print.xp3.raster.Frame;
import java.io.File;

/** A job being set up: the file, the printer and the options, shared by Preview, Print Settings and Pages. */
final class Draft {

    final File file;
    final String title;
    int pages;
    Printer printer;
    Options options;
    /** The page showing in the preview, from 0. */
    int page;

    Draft(File file, int pages, Printer printer, Options options) {
        this.file = file;
        this.title = Library.title(file);
        this.pages = pages;
        this.printer = printer;
        this.options = options;
    }

    boolean allPages() {
        return options.from <= 1 && options.to >= pages;
    }

    /** "2 copies · B&W · 2-sided", leaving out what is as usual. */
    String summary() {
        StringBuilder out = new StringBuilder();
        out.append(options.copies).append(options.copies == 1 ? " copy" : " copies");
        out.append(" · ").append(options.color ? "Color" : "B&W");
        if (options.duplex != Frame.ONE_SIDED) out.append(" · 2-sided");
        if (!allPages()) out.append(" · ").append(pagesShown());
        return out.toString();
    }

    /** "All (1–6)", "2–4", "Page 3". */
    String pagesShown() {
        if (allPages()) return pages == 1 ? "All (1)" : "All (1–" + pages + ")";
        if (options.from == options.to) return "Page " + options.from;
        return "Pages " + options.from + "–" + options.to;
    }

    /** Fits the options to a newly chosen printer. */
    void use(Printer chosen) {
        printer = chosen;
        Choices.fit(options, chosen);
    }
}
