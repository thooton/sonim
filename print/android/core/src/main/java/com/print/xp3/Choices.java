package com.print.xp3;

import com.print.xp3.ipp.Capabilities;
import com.print.xp3.raster.Frame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a printer offers for each setting, so that the settings list only
 * shows what it can do (a black-and-white laser has no Color row) and its
 * dialogs only the values it takes.
 */
public final class Choices {

    private Choices() {}

    /**
     * Whether it prints in colour: what IPP says, or for a printer reached
     * another way, whether it reads PostScript (PCL here is black and white).
     */
    public static boolean color(Printer printer) {
        if (printer == null) return true;
        synchronized (printer) {
            if (printer.ipp != null) return printer.ipp.color;
            if (printer.ippPort > 0) return true;
            if (printer.pjl != null) return printer.pjl.speaks("POSTSCRIPT") || printer.pjl.speaks("PDF");
            return printer.pdl.contains("application/postscript") || printer.pdl.contains("application/pdf");
        }
    }

    /** The sides it can print: always one-sided, then long and short edge when it can. */
    public static List<Integer> sides(Printer printer) {
        List<Integer> out = new ArrayList<>();
        out.add(Frame.ONE_SIDED);
        boolean two;
        if (printer == null) {
            two = true;
        } else {
            synchronized (printer) {
                Capabilities caps = printer.ipp;
                if (caps != null) two = caps.twoSided() || caps.urfDuplex();
                else two = printer.ippPort > 0 || printer.pjl != null || printer.rawPort > 0;
            }
        }
        if (two) {
            out.add(Frame.LONG_EDGE);
            out.add(Frame.SHORT_EDGE);
        }
        return out;
    }

    /** The paper sizes to offer: the printer's sheets, or the common ones when it doesn't say. */
    public static List<String> media(Printer printer) {
        if (printer != null) {
            synchronized (printer) {
                if (printer.ipp != null) {
                    List<String> offered = Media.offered(printer.ipp.media);
                    if (!offered.isEmpty()) return offered;
                }
            }
        }
        return new ArrayList<>(Arrays.asList(Media.COMMON));
    }

    /** `options`, changed only where the printer can't do what they ask, then returned. */
    public static Options fit(Options options, Printer printer) {
        if (!color(printer)) options.color = false;
        if (!sides(printer).contains(options.duplex)) options.duplex = Frame.ONE_SIDED;
        List<String> media = media(printer);
        if (!media.contains(options.media)) {
            String size = Media.shown(options.media);
            String match = null;
            for (String each : media) if (Media.shown(each).equals(size)) match = each;
            if (match == null && printer != null && printer.ipp != null && media.contains(printer.ipp.mediaDefault)) {
                match = printer.ipp.mediaDefault;
            }
            if (match == null) match = media.isEmpty() ? Media.LETTER : media.get(0);
            options.media = match;
        }
        return options;
    }

    /** "Color" or "Black & white". */
    public static String colorName(boolean color) {
        return color ? "Color" : "Black & white";
    }

    /** "Off", "Long edge", "Short edge". */
    public static String sidesName(int duplex) {
        return duplex == Frame.LONG_EDGE ? "Long edge" : duplex == Frame.SHORT_EDGE ? "Short edge" : "Off";
    }

    /** How many sheets the job takes. */
    public static int sheets(Options options) {
        int pages = Math.max(1, options.to - options.from + 1);
        int per = options.duplex == Frame.ONE_SIDED ? pages : (pages + 1) / 2;
        return per * Math.max(1, options.copies);
    }
}
