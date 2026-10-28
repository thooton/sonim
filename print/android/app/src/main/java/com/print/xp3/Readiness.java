package com.print.xp3;

import android.os.Handler;
import android.os.Looper;
import com.print.xp3.find.Sweep;
import com.print.xp3.ipp.Capabilities;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** How a printer is: asked afresh, then put in a few words for the banner and the printer list. */
final class Readiness {

    static final int CHECKING = 0;
    static final int READY = 1;
    static final int BUSY = 2;
    /** Working, but something wants seeing to soon (low ink): shown in the warning colour, printing goes on. */
    static final int WARNING = 3;
    static final int PROBLEM = 4;
    static final int UNREACHABLE = 5;

    final int kind;
    final String text;

    Readiness(int kind, String text) {
        this.kind = kind;
        this.text = text;
    }

    interface Checked {
        void checked(Readiness readiness);
    }

    private static final ExecutorService WORKERS = com.xp3.common.ui.Ui.workers("check", 3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Asks the printer how it is now, off the main thread; answers on it. */
    static void check(final Printer printer, final Checked answer) {
        WORKERS.execute(() -> {
            Readiness out = checkNow(printer);
            MAIN.post(() -> answer.checked(out));
        });
    }

    static Readiness checkNow(Printer printer) {
        boolean ipp;
        int raw;
        synchronized (printer) {
            ipp = printer.ippPort > 0;
            raw = printer.rawPort > 0 ? printer.rawPort : 0;
        }
        if (ipp) {
            try {
                Probe.ipp(printer);
                return of(printer);
            } catch (IOException e) {
                if (raw == 0) return new Readiness(UNREACHABLE, "Can't reach the printer");
            }
        }
        if (raw > 0) {
            if (!Sweep.open(printer.address, raw, 3000)) return new Readiness(UNREACHABLE, "Can't reach the printer");
            Probe.pjl(printer);
            return new Readiness(READY, "Ready");
        }
        if (!printer.lpdQueue.isEmpty()) {
            return Sweep.open(printer.address, 515, 3000) ? new Readiness(READY, "Ready")
                : new Readiness(UNREACHABLE, "Can't reach the printer");
        }
        return new Readiness(UNREACHABLE, "Can't reach the printer");
    }

    /** From what the printer last said about itself. */
    static Readiness of(Printer printer) {
        Capabilities caps;
        synchronized (printer) {
            caps = printer.ipp;
        }
        if (caps == null) return new Readiness(READY, "Ready");
        String problem = Reasons.printer(caps.reasons);
        if (!problem.isEmpty()) return new Readiness(PROBLEM, problem);
        String low = low(caps);
        if (!low.isEmpty()) return new Readiness(WARNING, low);
        if (caps.state == 4) return new Readiness(BUSY, "Printing");
        if (caps.state == 5) return new Readiness(PROBLEM, "Stopped");
        return new Readiness(READY, "Ready");
    }

    /** "Cyan ink low", "Toner low", or "". */
    static String low(Capabilities caps) {
        for (Capabilities.Marker marker : caps.markers) {
            if (!marker.isLow()) continue;
            return supply(marker) + " low";
        }
        for (String reason : caps.reasons) {
            String r = reason.toLowerCase(Locale.ROOT);
            if (r.startsWith("toner-low")) return "Toner low";
            if (r.startsWith("marker-supply-low")) return "Ink low";
        }
        return "";
    }

    /** "Cyan ink", "Black toner": what a supply is called on screen. */
    static String supply(Capabilities.Marker marker) {
        String name = colorName(marker);
        boolean toner = marker.type.contains("toner");
        if (name.isEmpty()) return toner ? "Toner" : "Ink";
        return name + (toner ? " toner" : " ink");
    }

    /** "Cyan", "Black", from the marker's name or its colour. */
    static String colorName(Capabilities.Marker marker) {
        String name = marker.name.toLowerCase(Locale.ROOT);
        String[] known = {"cyan", "magenta", "yellow", "black", "photo black", "grey", "gray"};
        for (String each : known) {
            if (name.contains(each)) return Character.toUpperCase(each.charAt(0)) + each.substring(1);
        }
        switch (marker.color.toUpperCase(Locale.ROOT)) {
            case "#00FFFF":
                return "Cyan";
            case "#FF00FF":
                return "Magenta";
            case "#FFFF00":
                return "Yellow";
            case "#000000":
                return "Black";
            default:
                return marker.name.isEmpty() ? "" : marker.name;
        }
    }

    /** Whether any of the printer's announced or reported formats say colour isn't possible. */
    static boolean blackAndWhiteOnly(Printer printer) {
        synchronized (printer) {
            return printer.ipp != null && !printer.ipp.color;
        }
    }

    /** "Ready · Office · .1.24": state, location, the end of the address. */
    static String line(Printer printer, Readiness readiness) {
        StringBuilder out = new StringBuilder(readiness == null ? "" : readiness.text);
        if (readiness != null && readiness.kind == READY && blackAndWhiteOnly(printer)) {
            out.setLength(0);
            out.append("Black & white only");
        }
        String where = printer.shownLocation();
        if (!where.isEmpty()) out.append(out.length() > 0 ? " · " : "").append(where);
        out.append(out.length() > 0 ? " · " : "").append(printer.shortAddress());
        return out.toString();
    }

    static int color(Readiness readiness) {
        if (readiness == null) return Look.MUTE;
        switch (readiness.kind) {
            case WARNING:
                return Look.WARN_TEXT;
            case PROBLEM:
            case UNREACHABLE:
                return Look.ERR;
            default:
                return Look.MUTE;
        }
    }

    /** Which printer picture fits it: pale, white, dark, or dark with ink tanks. */
    static int picture(Printer printer) {
        String all = (printer.shown() + " " + printer.shownModel()).toLowerCase(Locale.ROOT);
        if (all.contains("epson")) return all.contains("et-") || all.contains("ecotank") ? R.drawable.printer_tanks : R.drawable.printer_dark;
        if (all.contains("canon")) return R.drawable.printer_dark;
        if (all.contains("brother") || all.contains("lexmark") || all.contains("kyocera")) return R.drawable.printer_pale;
        return R.drawable.printer_light;
    }
}
