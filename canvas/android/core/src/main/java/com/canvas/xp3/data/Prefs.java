package com.canvas.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The student's choices on the Settings screen, apart from the Canvas login.
 *
 * Kept out of Config on purpose: Config holds a credential and is written
 * owner-only, and none of these are secret. Each value is one of a fixed set
 * of words, and anything else, from an older version or a damaged file,
 * reads as the default rather than as an error.
 */
public final class Prefs {

    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    public static final String PHONE = "phone";

    public static final String NORMAL = "default";
    public static final String LARGE = "large";

    public static final String OFF = "off";
    public static final String MORNING = "morning";
    public static final String EVENING = "evening";
    public static final String BOTH = "both";

    public static final String MONDAY = "monday";
    public static final String SUNDAY = "sunday";

    public static final String ALWAYS = "always";
    public static final String WIFI = "wifi";
    public static final String NEVER = "never";

    public final String theme;
    public final String textSize;
    public final String reminders;
    /** Null until chosen, which means the phone's region decides. */
    public final String weekStart;
    public final String downloads;
    /** Classes the student turned reminders off for. */
    public final Set<String> quiet;
    /** When Notifications was last opened, for the menu's "2 new". */
    public final long noticesSeen;

    public Prefs(String theme, String textSize, String reminders, String weekStart, String downloads,
                 Set<String> quiet, long noticesSeen) {
        this.theme = pick(theme, PHONE, LIGHT, DARK, PHONE);
        this.textSize = pick(textSize, NORMAL, NORMAL, LARGE);
        this.reminders = pick(reminders, EVENING, OFF, MORNING, EVENING, BOTH);
        this.weekStart = weekStart == null ? null : pick(weekStart, null, MONDAY, SUNDAY);
        this.downloads = pick(downloads, WIFI, ALWAYS, WIFI, NEVER);
        this.quiet = quiet == null ? new LinkedHashSet<String>() : quiet;
        this.noticesSeen = noticesSeen;
    }

    public static Prefs defaults() {
        return new Prefs(null, null, null, null, null, null, 0);
    }

    private static String pick(String value, String fallback, String... allowed) {
        for (String option : allowed) {
            if (option.equals(value)) return option;
        }
        return fallback;
    }

    public Prefs withTheme(String value) {
        return new Prefs(value, textSize, reminders, weekStart, downloads, quiet, noticesSeen);
    }

    public Prefs withTextSize(String value) {
        return new Prefs(theme, value, reminders, weekStart, downloads, quiet, noticesSeen);
    }

    public Prefs withReminders(String value) {
        return new Prefs(theme, textSize, value, weekStart, downloads, quiet, noticesSeen);
    }

    public Prefs withWeekStart(String value) {
        return new Prefs(theme, textSize, reminders, value, downloads, quiet, noticesSeen);
    }

    public Prefs withDownloads(String value) {
        return new Prefs(theme, textSize, reminders, weekStart, value, quiet, noticesSeen);
    }

    public Prefs withQuiet(String course, boolean value) {
        Set<String> next = new LinkedHashSet<>(quiet);
        if (value) next.add(course);
        else next.remove(course);
        return new Prefs(theme, textSize, reminders, weekStart, downloads, next, noticesSeen);
    }

    public Prefs withNoticesSeen(long value) {
        return new Prefs(theme, textSize, reminders, weekStart, downloads, quiet, value);
    }

    String toJson() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("theme", theme);
        out.put("textSize", textSize);
        out.put("reminders", reminders);
        out.put("weekStart", weekStart);
        out.put("downloads", downloads);
        out.put("quiet", new ArrayList<Object>(quiet));
        out.put("noticesSeen", Long.valueOf(noticesSeen));
        return Json.write(out);
    }

    static Prefs parse(String json) {
        try {
            Map<String, Object> map = Json.object(Json.parse(json));
            Set<String> quiet = new LinkedHashSet<>();
            for (Object entry : Json.children(map, "quiet")) {
                if (entry instanceof String) quiet.add((String) entry);
            }
            Double seen = Json.number(map, "noticesSeen");
            return new Prefs(Json.text(map, "theme"), Json.text(map, "textSize"), Json.text(map, "reminders"),
                Json.text(map, "weekStart"), Json.text(map, "downloads"), quiet,
                seen == null ? 0 : (long) seen.doubleValue());
        } catch (RuntimeException e) {
            return defaults();
        }
    }

    /** The reminder setting's words as Settings shows them, in the phone's 12/24-hour style. */
    public static String reminderLabel(String value, boolean twentyFourHour) {
        if (OFF.equals(value)) return "Off";
        if (MORNING.equals(value)) return "Morning of, " + (twentyFourHour ? "08:00" : "8:00 AM");
        if (BOTH.equals(value)) return "Both";
        return "Evening before, " + (twentyFourHour ? "18:00" : "6:00 PM");
    }

    public static List<String> reminderChoices() {
        List<String> out = new ArrayList<>();
        out.add(OFF);
        out.add(MORNING);
        out.add(EVENING);
        out.add(BOTH);
        return out;
    }
}
