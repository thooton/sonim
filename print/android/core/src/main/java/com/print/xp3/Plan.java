package com.print.xp3;

import com.print.xp3.ipp.Capabilities;
import com.print.xp3.raster.Back;
import com.print.xp3.raster.Frame;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chooses how to send a job to a printer: every way that should work, best
 * first, so that when a printer refuses one the next can be tried.
 *
 * A PDF the printer reads itself is best (sharpest, smallest, fastest);
 * then the raster formats made for driverless printing (PWG, Apple, PCLm);
 * then the page description languages, PostScript and PCL, built from page
 * images; IPP before the raw port before LPD.
 */
public final class Plan {

    private Plan() {}

    public static List<Route> routes(Printer printer, Options options, int pageCount) {
        List<Route> out = new ArrayList<Route>();
        boolean all = options.allPages(pageCount);
        Capabilities caps = printer.ipp;
        if (printer.ippPort > 0 && caps != null) ipp(out, caps, options, all, printer);
        if (printer.rawPort > 0) raw(out, Route.Transport.RAW, printer, options, all);
        if (!printer.lpdQueue.isEmpty()) raw(out, Route.Transport.LPD, printer, options, all);
        return out;
    }

    private static void ipp(List<Route> out, Capabilities caps, Options options, boolean all, Printer printer) {
        int before = out.size();
        ipp(out, caps, options, all, printer, caps.twoSided() || caps.urfDuplex() ? options.duplex : Frame.ONE_SIDED);
        for (int i = before; i < out.size(); i++) {
            Route route = out.get(i);
            route.duplex = caps.twoSided() || route.kind == Route.Kind.URF && caps.urfDuplex() ? options.duplex : Frame.ONE_SIDED;
            if (route.duplex == Frame.ONE_SIDED) route.back = Back.NONE;
        }
    }

    private static void ipp(List<Route> out, Capabilities caps, Options options, boolean all, Printer printer, int duplex) {
        boolean copies = caps.copiesMax >= options.copies;
        if (!options.images && caps.takes("application/pdf") && (all || caps.pageRanges)) {
            Route route = route(Route.Transport.IPP, Route.Kind.PDF, options);
            route.color = options.color && caps.color;
            if (copies) route.printerCopies = options.copies;
            else route.jobs = options.copies;
            route.pageRanges = !all;
            out.add(route);
        }
        if (caps.takes("image/pwg-raster") || !caps.pwgTypes.isEmpty()) {
            Route route = raster(Route.Kind.PWG, options, caps.pwgResolutions);
            boolean grey = caps.pwgTypes.isEmpty() || caps.pwgTypes.contains("sgray_8");
            boolean rgb = caps.pwgTypes.contains("srgb_8");
            route.color = rgb && (!grey || options.color && caps.color);
            route.back = Back.of(caps.pwgSheetBack, duplex);
            out.add(route);
        }
        if (caps.takes("image/urf") || !caps.urf.isEmpty()) {
            Route route = raster(Route.Kind.URF, options, caps.urfResolutions());
            boolean grey = caps.urf.isEmpty() || caps.urf.contains("W8");
            boolean rgb = caps.urf.contains("SRGB24");
            route.color = rgb && (!grey || options.color && caps.color);
            route.back = Back.of(caps.urfSheetBack(), duplex);
            out.add(route);
        }
        if (caps.takes("application/pclm")) {
            Route route = raster(Route.Kind.PCLM, options, caps.pclmResolutions);
            route.color = options.color && caps.color;
            route.pclmStrip = caps.pclmStrip;
            route.back = Back.of(caps.pclmBackSide, duplex);
            out.add(route);
        }
        if (caps.takes("application/postscript")) {
            Route route = raster(Route.Kind.POSTSCRIPT, options, null);
            route.color = options.color && caps.color;
            route.printerCopies = options.copies;
            route.repeat = 1;
            out.add(route);
        }
        if (caps.takes("application/vnd.hp-pcl")) {
            Route route = raster(Route.Kind.PCL, options, null);
            route.printerCopies = options.copies;
            route.repeat = 1;
            out.add(route);
        }
        if (out.isEmpty() && (caps.formats.isEmpty() || caps.takes("application/octet-stream"))) {
            // An older printer that guesses the language itself: what a
            // PostScript printer says it is, otherwise PCL.
            boolean postscript = mentionsPostScript(printer);
            Route route = raster(postscript ? Route.Kind.POSTSCRIPT : Route.Kind.PCL, options, null);
            route.mime = "application/octet-stream";
            route.color = postscript && options.color && caps.color;
            route.printerCopies = options.copies;
            route.repeat = 1;
            out.add(route);
        }
    }

    private static boolean mentionsPostScript(Printer printer) {
        String all = (printer.model + " " + printer.name + " "
            + (printer.ipp == null ? "" : printer.ipp.makeAndModel)).toLowerCase(Locale.ROOT);
        return all.contains("postscript") || all.matches(".*\\bps[0-9]?\\b.*");
    }

    private static void raw(List<Route> out, Route.Transport transport, Printer printer, Options options, boolean all) {
        boolean pjl = printer.pjl != null;
        boolean pdf = pjl ? printer.pjl.speaks("PDF") : printer.pdl.contains("application/pdf");
        boolean postscript = pjl ? printer.pjl.speaks("POSTSCRIPT") || printer.pjl.speaks("PS")
            : printer.pdl.contains("application/postscript") || mentionsPostScript(printer);
        boolean pcl = pjl ? printer.pjl.speaks("PCL") : printer.pdl.contains("application/vnd.hp-pcl");
        if (!pdf && !postscript && !pcl) pcl = true;
        if (pdf && all && !options.images) {
            Route route = route(transport, Route.Kind.PDF, options);
            route.pjl = pjl;
            // Copies and sides travel in PJL; without PJL, send it again.
            if (pjl) {
                route.printerCopies = options.copies;
            } else {
                route.jobs = options.copies;
            }
            out.add(route);
        }
        if (postscript) {
            Route route = raster(Route.Kind.POSTSCRIPT, options, null);
            route.transport = transport;
            route.color = options.color;
            route.printerCopies = options.copies;
            route.repeat = 1;
            route.pjl = pjl;
            out.add(route);
        }
        if (pcl) {
            Route route = raster(Route.Kind.PCL, options, null);
            route.transport = transport;
            route.printerCopies = options.copies;
            route.repeat = 1;
            route.pjl = pjl;
            out.add(route);
        }
    }

    private static Route route(Route.Transport transport, Route.Kind kind, Options options) {
        Route route = new Route();
        route.transport = transport;
        route.kind = kind;
        route.mime = kind.mime;
        route.duplex = options.duplex;
        return route;
    }

    /** A raster route, its copies made by sending the pages again (printers differ on copying page images). */
    private static Route raster(Route.Kind kind, Options options, List<Integer> resolutions) {
        Route route = route(Route.Transport.IPP, kind, options);
        route.dpi = resolution(resolutions);
        route.repeat = options.copies;
        return route;
    }

    /** 300 dpi when offered (sharp enough, and quick to make on a phone), else the nearest above it. */
    static int resolution(List<Integer> offered) {
        if (offered == null || offered.isEmpty()) return 300;
        if (offered.contains(300)) return 300;
        int best = -1;
        for (int dpi : offered) if (dpi > 300 && (best < 0 || dpi < best)) best = dpi;
        if (best > 0) return best;
        for (int dpi : offered) best = Math.max(best, dpi);
        return best;
    }
}
