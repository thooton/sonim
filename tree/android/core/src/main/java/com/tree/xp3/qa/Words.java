package com.tree.xp3.qa;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** How the app words what the command center sends, kept here so it is tested. */
public final class Words {

    private Words() {}

    /** "list" is "List": an app is named for its directory. */
    public static String name(String dir) {
        if (dir == null || dir.length() == 0) return "?";
        return dir.substring(0, 1).toUpperCase(Locale.US) + dir.substring(1);
    }

    /** sonim's apps are the phone's, tablet's the tablet's. */
    public static String tag(String repo) {
        if ("sonim".equals(repo)) return "phone";
        if ("tablet".equals(repo)) return "tablet";
        return repo == null ? "" : repo;
    }

    public static String kind(String kind) {
        if ("bug".equals(kind)) return "Bug";
        if ("problem".equals(kind)) return "Problem";
        if ("inconsistency".equals(kind)) return "Inconsistency";
        if ("feature".equals(kind)) return "Feature";
        return name(kind);
    }

    public static String status(String status) {
        if ("open".equals(status)) return "Open";
        if ("fixed".equals(status)) return "Fixed";
        if ("passed".equals(status)) return "Left for you";
        if ("declined".equals(status)) return "Declined";
        if ("grown".equals(status)) return "Grown";
        if ("reverted".equals(status)) return "Reverted";
        return name(status);
    }

    /** An app's place in a run. */
    public static String progress(String status) {
        if ("waiting".equals(status)) return "Waiting";
        if ("running".equals(status)) return "Running";
        if ("done".equals(status)) return "Done";
        if ("skipped".equals(status)) return "Skipped";
        return name(status);
    }

    public static String verdict(String verdict) {
        if ("up".equals(verdict)) return "👍";
        if ("down".equals(verdict)) return "👎";
        return "";
    }

    /** "Grows · 2 open · 1 left for you · 1 grown", or "Nothing open". */
    public static String app(Qa.App app) {
        List<String> parts = new ArrayList<>();
        if (app.grow) parts.add("Grows");
        if (!app.spec) parts.add("No spec yet");
        if (app.open > 0) parts.add(app.open + " open");
        if (app.passed > 0) parts.add(app.passed + " left for you");
        if (app.grown > 0) parts.add(app.grown + " grown");
        if (app.open == 0 && app.passed == 0 && app.grown == 0 && app.spec) parts.add("Nothing open");
        return join(parts);
    }

    /** 0 is "12 AM", 15 is "3 PM". */
    public static String hour(int hour) {
        int h = ((hour % 24) + 24) % 24;
        int shown = h % 12 == 0 ? 12 : h % 12;
        return shown + (h < 12 ? " AM" : " PM");
    }

    /** "Daily at 1 PM", or "Daily run off". */
    public static String schedule(Qa.Settings settings) {
        return settings.enabled ? "Daily at " + hour(settings.hour) : "Daily run off";
    }

    /**
     * A moment: its time today ("3:41 AM"), "Yesterday 3:41 AM", a day this
     * year ("Oct 27"), or one in another year ("Oct 27, 2025"). "" for none.
     */
    public static String when(long at, long now, TimeZone zone) {
        if (at <= 0) return "";
        Calendar then = calendar(at, zone);
        Calendar today = calendar(now, zone);
        Calendar yesterday = calendar(now, zone);
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, today)) return format("h:mm a", at, zone);
        if (sameDay(then, yesterday)) return "Yesterday " + format("h:mm a", at, zone);
        if (then.get(Calendar.YEAR) == today.get(Calendar.YEAR)) return format("MMM d", at, zone);
        return format("MMM d, yyyy", at, zone);
    }

    /**
     * How a run stands, for the home screen's header: "Running · 4 of 12",
     * "Running · refactoring", "Done 3:41 AM", "Cancelled Yesterday 2:10 AM".
     */
    public static String run(Qa.Run run, long now, TimeZone zone) {
        if (run == null) return "No runs yet";
        if (run.isRunning()) {
            if ("refactor".equals(run.stage)) return "Running · refactoring";
            if (run.apps.isEmpty()) return "Running";
            return "Running · " + run.finished() + " of " + run.apps.size();
        }
        long at = run.ended > 0 ? run.ended : run.created;
        String when = when(at, now, zone);
        String what = "cancelled".equals(run.status) ? "Cancelled" : "done".equals(run.status) ? "Done" : name(run.status);
        return when.length() == 0 ? what : what + " " + when;
    }

    /** "Scheduled", or "Run by you". */
    public static String trigger(String trigger) {
        if ("schedule".equals(trigger)) return "Scheduled";
        if ("manual".equals(trigger)) return "Run by you";
        return name(trigger);
    }

    public static String role(String role) {
        if ("spec".equals(role)) return "Spec";
        if ("explore".equals(role)) return "Explore";
        if ("fix".equals(role)) return "Fix";
        if ("refactor".equals(role)) return "Refactor";
        return name(role);
    }

    /** A screenshot's name is a plain file name, never a path. */
    public static boolean safeName(String name) {
        if (name == null || name.length() == 0 || name.length() > 128 || name.startsWith(".")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(" · ");
            out.append(part);
        }
        return out.toString();
    }

    private static Calendar calendar(long at, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone, Locale.US);
        calendar.setTimeInMillis(at);
        return calendar;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String format(String pattern, long at, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(zone);
        return format.format(new Date(at));
    }
}
