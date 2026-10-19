package com.print.xp3.ipp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** What an IPP printer says about itself in answer to Get-Printer-Attributes. */
public final class Capabilities {

    public String name = "";
    public String info = "";
    public String makeAndModel = "";
    public String location = "";
    public List<String> formats = Collections.emptyList();
    public boolean color;
    public List<String> colorModes = Collections.emptyList();
    public List<String> sides = Collections.emptyList();
    public List<String> media = Collections.emptyList();
    public String mediaDefault = "";
    public int copiesMax = 1;
    public boolean pageRanges;
    public List<Integer> pwgResolutions = Collections.emptyList();
    public List<String> pwgTypes = Collections.emptyList();
    public String pwgSheetBack = "normal";
    public List<String> urf = Collections.emptyList();
    public List<Integer> pclmResolutions = Collections.emptyList();
    public int pclmStrip = 16;
    public String pclmBackSide = "normal";
    public List<Integer> operations = Collections.emptyList();
    public boolean ipp2 = true;
    /** 3 idle, 4 processing, 5 stopped; 0 when not said. */
    public int state;
    public List<String> reasons = Collections.emptyList();
    /** The supplies (ink, toner), in the printer's order. */
    public List<Marker> markers = Collections.emptyList();

    /** One supply and how much of it is left. */
    public static final class Marker {
        public final String name;
        /** "#RRGGBB" ("#00FFFF#FF00FF" for a cartridge of several), or "". */
        public final String color;
        /** 0 to 100; -1 when the printer can't tell. */
        public final int level;
        /** The level at or under which the printer calls it low; 15 when it doesn't say. */
        public final int low;
        public final String type;

        public Marker(String name, String color, int level, int low, String type) {
            this.name = name;
            this.color = color;
            this.level = level;
            this.low = low;
            this.type = type;
        }

        public boolean isLow() {
            return level >= 0 && level <= Math.max(low, 15);
        }
    }

    public static Capabilities from(Ipp.Message answer) {
        Ipp.Group printer = answer.group(Ipp.PRINTER);
        Capabilities out = new Capabilities();
        if (printer == null) return out;
        out.name = string(printer, "printer-name");
        out.info = string(printer, "printer-info");
        out.makeAndModel = string(printer, "printer-make-and-model");
        out.location = string(printer, "printer-location");
        out.formats = lower(strings(printer, "document-format-supported"));
        out.colorModes = strings(printer, "print-color-mode-supported");
        Ipp.Attribute color = printer.get("color-supported");
        out.color = color != null ? color.flag() : out.colorModes.contains("color");
        out.sides = strings(printer, "sides-supported");
        out.media = strings(printer, "media-supported");
        out.mediaDefault = string(printer, "media-default");
        Ipp.Attribute copies = printer.get("copies-supported");
        if (copies != null && !copies.values.isEmpty() && copies.values.get(0) instanceof int[]) {
            out.copiesMax = Math.max(1, ((int[]) copies.values.get(0))[1]);
        }
        Ipp.Attribute ranges = printer.get("page-ranges-supported");
        out.pageRanges = ranges != null && ranges.flag();
        out.pwgResolutions = dpis(printer.get("pwg-raster-document-resolution-supported"));
        out.pwgTypes = strings(printer, "pwg-raster-document-type-supported");
        String back = string(printer, "pwg-raster-document-sheet-back");
        if (!back.isEmpty()) out.pwgSheetBack = back;
        out.urf = strings(printer, "urf-supported");
        out.pclmResolutions = dpis(printer.get("pclm-source-resolution-supported"));
        Ipp.Attribute strip = printer.get("pclm-strip-height-preferred");
        if (strip != null) out.pclmStrip = Math.max(1, Math.min(256, strip.integer(16)));
        String pclmBack = string(printer, "pclm-raster-back-side");
        if (!pclmBack.isEmpty()) out.pclmBackSide = pclmBack;
        Ipp.Attribute operations = printer.get("operations-supported");
        if (operations != null) {
            List<Integer> ops = new ArrayList<Integer>();
            for (Object value : operations.values) if (value instanceof Integer) ops.add((Integer) value);
            out.operations = ops;
        }
        List<String> versions = strings(printer, "ipp-versions-supported");
        out.ipp2 = versions.isEmpty() || versions.contains("2.0") || versions.contains("2.1") || versions.contains("2.2");
        Ipp.Attribute state = printer.get("printer-state");
        out.state = state == null ? 0 : state.integer(0);
        out.reasons = strings(printer, "printer-state-reasons");
        out.markers = markers(printer);
        return out;
    }

    /** The name to show: the printer's own words for itself, best first. */
    public String shown() {
        if (!info.isEmpty()) return info;
        if (!name.isEmpty()) return name;
        return makeAndModel;
    }

    public boolean takes(String format) {
        return formats.contains(format);
    }

    public boolean twoSided() {
        return sides.contains("two-sided-long-edge") || sides.contains("two-sided-short-edge");
    }

    /** The resolutions in an `urf-supported` "RS300-600" entry. */
    public List<Integer> urfResolutions() {
        List<Integer> out = new ArrayList<Integer>();
        for (String entry : urf) {
            if (!entry.startsWith("RS")) continue;
            for (String part : entry.substring(2).split("-")) {
                try {
                    out.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    // Not a number: skip it.
                }
            }
        }
        return out;
    }

    /** How an AirPrint printer wants back sides drawn, from its "DMn" entry. */
    public String urfSheetBack() {
        for (String entry : urf) {
            if (entry.equals("DM2")) return "flipped";
            if (entry.equals("DM3")) return "rotated";
            if (entry.equals("DM4")) return "manual-tumble";
        }
        return "normal";
    }

    public boolean urfDuplex() {
        for (String entry : urf) if (entry.startsWith("DM")) return true;
        return false;
    }

    private static List<Marker> markers(Ipp.Group printer) {
        Ipp.Attribute levels = printer.get("marker-levels");
        if (levels == null) return Collections.emptyList();
        List<String> names = strings(printer, "marker-names");
        List<String> colors = strings(printer, "marker-colors");
        List<String> types = strings(printer, "marker-types");
        Ipp.Attribute lows = printer.get("marker-low-levels");
        List<Marker> out = new ArrayList<Marker>();
        for (int i = 0; i < levels.values.size(); i++) {
            Object level = levels.values.get(i);
            int value = level instanceof Integer ? (Integer) level : -1;
            int low = lows != null && i < lows.values.size() && lows.values.get(i) instanceof Integer
                ? (Integer) lows.values.get(i) : 15;
            out.add(new Marker(i < names.size() ? names.get(i) : "", i < colors.size() ? colors.get(i) : "",
                value < 0 || value > 100 ? -1 : value, low, i < types.size() ? types.get(i) : ""));
        }
        return out;
    }

    private static String string(Ipp.Group group, String name) {
        Ipp.Attribute attribute = group.get(name);
        return attribute == null ? "" : attribute.string().trim();
    }

    private static List<String> strings(Ipp.Group group, String name) {
        Ipp.Attribute attribute = group.get(name);
        return attribute == null ? Collections.<String>emptyList() : attribute.strings();
    }

    private static List<String> lower(List<String> values) {
        List<String> out = new ArrayList<String>();
        for (String value : values) out.add(value.toLowerCase(Locale.ROOT));
        return out;
    }

    private static List<Integer> dpis(Ipp.Attribute attribute) {
        List<Integer> out = new ArrayList<Integer>();
        if (attribute == null) return out;
        for (Object value : attribute.values) {
            if (!(value instanceof int[])) continue;
            int[] resolution = (int[]) value;
            int dpi = resolution[2] == 4 ? Math.round(resolution[0] * 2.54f) : resolution[0];
            if (!out.contains(dpi)) out.add(dpi);
        }
        return out;
    }
}

