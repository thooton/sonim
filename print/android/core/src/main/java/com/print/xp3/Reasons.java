package com.print.xp3;

import java.util.List;
import java.util.Locale;

/** A printer's and a job's state reasons in a few plain words. */
public final class Reasons {

    private Reasons() {}

    /** The most pressing problem among `reasons`, or "" when nothing is wrong. */
    public static String printer(List<String> reasons) {
        String fallback = "";
        for (String raw : reasons) {
            String reason = raw.toLowerCase(Locale.ROOT);
            if (reason.endsWith("-report") || reason.endsWith("-warning") || reason.equals("none")) continue;
            String base = reason.endsWith("-error") ? reason.substring(0, reason.length() - 6) : reason;
            String said = known(base);
            if (said != null) return said;
            if (fallback.isEmpty() && reason.endsWith("-error")) fallback = words(base);
        }
        return fallback;
    }

    private static String known(String reason) {
        switch (reason) {
            case "media-empty":
            case "media-needed":
                return "Out of paper";
            case "media-jam":
                return "Paper jam";
            case "toner-empty":
                return "Out of toner";
            case "marker-supply-empty":
                return "Out of ink";
            case "door-open":
            case "cover-open":
            case "interlock-open":
                return "A cover is open";
            case "input-tray-missing":
                return "A paper tray is out";
            case "output-area-full":
                return "The output tray is full";
            case "marker-waste-full":
                return "The waste ink tank is full";
            case "offline":
            case "shutdown":
            case "connecting-to-device":
                return "The printer is offline";
            case "paused":
            case "moving-to-paused":
                return "Paused at the printer";
            case "spool-area-full":
                return "The printer is full";
            default:
                return null;
        }
    }

    /** Why a job ended without printing. */
    public static String job(List<String> reasons) {
        for (String raw : reasons) {
            String reason = raw.toLowerCase(Locale.ROOT);
            if (reason.startsWith("document-format-error") || reason.startsWith("document-unprintable")) {
                return "The printer couldn't read the document";
            }
            if (reason.startsWith("unsupported-document-format") || reason.startsWith("unsupported-compression")) {
                return "The printer doesn't take this kind of document";
            }
            if (reason.startsWith("job-canceled-at-device")) return "Canceled at the printer";
            if (reason.startsWith("job-canceled-by-operator")) return "Canceled at the printer";
            if (reason.startsWith("aborted-by-system")) return "The printer gave up on it";
            if (reason.startsWith("job-data-insufficient")) return "The document didn't all arrive";
        }
        return "";
    }

    /** "fuser-over-temp" as "Fuser over temp". */
    static String words(String keyword) {
        String spaced = keyword.replace('-', ' ').replace('_', ' ').trim();
        if (spaced.isEmpty()) return "";
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
