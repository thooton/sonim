package com.print.xp3;

import com.print.xp3.ipp.Capabilities;
import com.print.xp3.raster.Back;
import com.print.xp3.raster.Frame;
import com.print.xp3.send.Pjl;
import com.xp3.common.Check;
import java.util.Arrays;
import java.util.List;

final class PlanTests {

    private PlanTests() {}

    static Capabilities caps(String... formats) {
        Capabilities caps = new Capabilities();
        caps.formats = Arrays.asList(formats);
        caps.copiesMax = 99;
        caps.pageRanges = true;
        caps.color = true;
        caps.colorModes = Arrays.asList("monochrome", "color");
        caps.sides = Arrays.asList("one-sided", "two-sided-long-edge", "two-sided-short-edge");
        return caps;
    }

    static Printer ipp(Capabilities caps) {
        Printer printer = new Printer("10.0.0.5");
        printer.ippPort = 631;
        printer.ippPath = "/ipp/print";
        printer.ipp = caps;
        return printer;
    }

    static String kinds(List<Route> routes) {
        StringBuilder out = new StringBuilder();
        for (Route route : routes) out.append(out.length() == 0 ? "" : " ").append(route.transport).append('/').append(route.kind);
        return out.toString();
    }

    static void run(final Check check) {
        check.run("plan", () -> {
            Options options = new Options();
            options.to = 5;
            Printer everywhere = ipp(caps("application/pdf", "image/pwg-raster", "image/urf", "image/jpeg"));
            everywhere.ipp.pwgTypes = Arrays.asList("sgray_8", "srgb_8");
            everywhere.ipp.urf = Arrays.asList("W8", "SRGB24", "RS300");
            check.same("pdf, then the rasters", "IPP/PDF IPP/PWG IPP/URF", kinds(Plan.routes(everywhere, options, 5)));

            options.copies = 3;
            options.duplex = Frame.LONG_EDGE;
            everywhere.ipp.pwgSheetBack = "rotated";
            List<Route> routes = Plan.routes(everywhere, options, 5);
            check.same("printer copies the pdf", 3, routes.get(0).printerCopies);
            check.same("rasters repeat the pages", 3, routes.get(1).repeat);
            check.same("back turned", Back.ROTATE, routes.get(1).back);
            check.ok("colour", routes.get(1).color);
            options.color = false;
            check.ok("grey when asked", !Plan.routes(everywhere, options, 5).get(1).color);

            options.from = 2;
            everywhere.ipp.pageRanges = false;
            check.same("no page ranges: no pdf", "IPP/PWG IPP/URF", kinds(Plan.routes(everywhere, options, 5)));
            options.images = true;
            options.from = 1;
            everywhere.ipp.pageRanges = true;
            check.same("images asked for", "IPP/PWG IPP/URF", kinds(Plan.routes(everywhere, options, 5)));

            Printer oneSided = ipp(caps("image/pwg-raster"));
            oneSided.ipp.sides = Arrays.asList("one-sided");
            oneSided.ipp.pwgResolutions = Arrays.asList(600, 1200);
            Route only = Plan.routes(oneSided, options, 5).get(0);
            check.same("can't do two sides", Frame.ONE_SIDED, only.duplex);
            check.same("nearest above 300", 600, only.dpi);
            check.same("grey without types", false, only.color);

            Printer pclm = ipp(caps("application/pclm"));
            pclm.ipp.pclmStrip = 32;
            Route strips = Plan.routes(pclm, new Options(), 1).get(0);
            check.same("pclm", Route.Kind.PCLM, strips.kind);
            check.same("strip", 32, strips.pclmStrip);

            Printer guess = ipp(caps("application/octet-stream"));
            guess.ipp.makeAndModel = "Lexmark MS610 PS3";
            check.same("old: postscript by its name", "IPP/POSTSCRIPT", kinds(Plan.routes(guess, new Options(), 1)));

            Printer raw = new Printer("10.0.0.7");
            raw.rawPort = 9100;
            check.same("raw, nothing known: pcl", "RAW/PCL", kinds(Plan.routes(raw, new Options(), 1)));
            raw.pjl = Pjl.parse("@PJL INFO CONFIG\r\nLANGUAGES [2 ENUMERATED]\r\n\tPCL\r\n\tPDF\r\n\f");
            raw.lpdQueue = "lp";
            check.same("raw pdf by pjl", "RAW/PDF RAW/PCL LPD/PDF LPD/PCL", kinds(Plan.routes(raw, new Options(), 1)));
            check.ok("in pjl", Plan.routes(raw, new Options(), 1).get(0).pjl);

            Printer both = ipp(caps("application/pdf"));
            both.rawPort = 9100;
            check.same("ipp first", "IPP/PDF RAW/PCL", kinds(Plan.routes(both, new Options(), 1)));

            check.same("300 offered", 300, Plan.resolution(Arrays.asList(150, 300, 600)));
            check.same("only lower", 200, Plan.resolution(Arrays.asList(150, 200)));
        });

        check.run("sequence", () -> {
            Options options = new Options();
            options.from = 2;
            options.to = 4;
            Route route = new Route();
            route.repeat = 2;
            route.duplex = Frame.LONG_EDGE;
            check.same("blank between odd copies", Arrays.asList(1, 2, 3, -1, 1, 2, 3), Job.sequence(options, route));
            route.duplex = Frame.ONE_SIDED;
            check.same("one-sided", Arrays.asList(1, 2, 3, 1, 2, 3), Job.sequence(options, route));
        });

        check.run("media and reasons", () -> {
            double[] letter = Media.points(Media.LETTER);
            check.close("letter wide", 612, letter[0], 0.01);
            check.close("a4 tall", 841.89, Media.points(Media.A4)[1], 0.01);
            check.same("no size", null, Media.points("roll_max"));
            check.same("shown", "A4", Media.shown(Media.A4));
            check.same("shown odd", "4 x 6 in", Media.shown("na_index-4x6_4x6in"));
            check.same("country", Media.LETTER, Media.forCountry("us"));
            check.same("elsewhere", Media.A4, Media.forCountry("DE"));
            check.same("offered", Arrays.asList(Media.LETTER, Media.A4), Media.offered(Arrays.asList(
                Media.LETTER, "na_letter_8.5x11in", "na_number-10_4.125x9.5in", Media.A4, "na_index-4x6_4x6in")));
            check.same("paper", "Out of paper", Reasons.printer(Arrays.asList("none", "media-empty-error")));
            check.same("warnings pass", "", Reasons.printer(Arrays.asList("toner-low-warning")));
            check.same("unknown error worded", "Fuser over temp", Reasons.printer(Arrays.asList("fuser-over-temp-error")));
            check.same("job", "The printer couldn't read the document", Reasons.job(Arrays.asList("document-format-error")));
        });
    }
}
