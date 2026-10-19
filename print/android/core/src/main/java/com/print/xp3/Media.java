package com.print.xp3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paper, by its PWG name ("na_letter_8.5x11in", "iso_a4_210x297mm"), which
 * carries its own size: the last part is width x height in inches or mm.
 */
public final class Media {

    private Media() {}

    public static final String LETTER = "na_letter_8.5x11in";
    public static final String A4 = "iso_a4_210x297mm";
    public static final String LEGAL = "na_legal_8.5x14in";

    /** What is offered when the printer doesn't say (raw and LPD printers). */
    public static final String[] COMMON = {LETTER, A4, LEGAL, "iso_a5_148x210mm", "na_executive_7.25x10.5in"};

    private static final Pattern SIZE = Pattern.compile("_([0-9.]+)x([0-9.]+)(in|mm)$");

    /** Width and height in points, or null when the name carries no size. */
    public static double[] points(String media) {
        if (media == null) return null;
        Matcher m = SIZE.matcher(media);
        if (!m.find()) return null;
        try {
            double unit = m.group(3).equals("in") ? 72.0 : 72.0 / 25.4;
            double w = Double.parseDouble(m.group(1)) * unit;
            double h = Double.parseDouble(m.group(2)) * unit;
            if (w <= 0 || h <= 0) return null;
            return new double[] {Math.min(w, h), Math.max(w, h)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "Letter", "A4", "4 x 6 in". */
    public static String shown(String media) {
        if (media == null) return "";
        String lower = media.toLowerCase(Locale.ROOT);
        if (lower.startsWith("na_letter_")) return "Letter";
        if (lower.startsWith("na_legal_")) return "Legal";
        if (lower.startsWith("na_executive_")) return "Executive";
        if (lower.startsWith("na_ledger_")) return "Ledger";
        if (lower.startsWith("na_govt-letter_")) return "Government letter";
        if (lower.startsWith("na_invoice_")) return "Statement";
        if (lower.startsWith("na_number-10_")) return "Envelope #10";
        if (lower.startsWith("iso_dl_")) return "Envelope DL";
        Matcher iso = Pattern.compile("^(iso|jis)_([abc][0-9]+)_").matcher(lower);
        if (iso.find()) return (iso.group(1).equals("jis") ? "JIS " : "") + iso.group(2).toUpperCase(Locale.ROOT);
        Matcher m = SIZE.matcher(lower);
        if (m.find()) return trim(m.group(1)) + " x " + trim(m.group(2)) + " " + m.group(3);
        return media;
    }

    /** "8.5 × 11 in", "210 × 297 mm": the size the name carries, or "". */
    public static String dimensions(String media) {
        if (media == null) return "";
        Matcher m = SIZE.matcher(media.toLowerCase(Locale.ROOT));
        if (!m.find()) return "";
        return trim(m.group(1)) + " \u00d7 " + trim(m.group(2)) + " " + m.group(3);
    }

    private static String trim(String number) {
        return number.endsWith(".0") ? number.substring(0, number.length() - 2) : number;
    }

    /** The paper a printer with nothing to say probably has in it, by where the phone is set. */
    public static String forCountry(String country) {
        String c = country == null ? "" : country.toUpperCase(Locale.ROOT);
        return c.equals("US") || c.equals("CA") || c.equals("MX") || c.equals("PH") || c.equals("CL")
            || c.equals("CO") || c.equals("VE") || c.equals("PR") || c.equals("GT") ? LETTER : A4;
    }

    /**
     * What to offer from a printer's list: sheets from about A6 to A3, no
     * envelopes, borderless or tray-specific variants, each size once.
     */
    public static List<String> offered(List<String> supported) {
        List<String> out = new ArrayList<String>();
        List<String> seen = new ArrayList<String>();
        for (String media : supported) {
            double[] size = points(media);
            if (size == null) continue;
            String lower = media.toLowerCase(Locale.ROOT);
            if (lower.contains("env") || lower.startsWith("custom_") || lower.contains("_dl_")
                || lower.contains("number-10") || lower.contains("monarch") || lower.contains("photo")) continue;
            if (size[0] < 295 || size[1] > 1200) continue;
            String shown = shown(media);
            if (seen.contains(shown)) continue;
            seen.add(shown);
            out.add(media);
        }
        return out;
    }
}
