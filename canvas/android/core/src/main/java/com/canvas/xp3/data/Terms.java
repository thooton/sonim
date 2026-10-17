package com.canvas.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which classes belong to this semester.
 *
 * Canvas keeps old enrolments "active" for years, and leaves term dates
 * empty, so the only reliable sign of a class's semester is its term's
 * name: "2026 Fall C", "2026 Spring B", "2026 Fall DYN". A class is current
 * when its term names this year and a season whose window holds today.
 * Organisations, training and advising shells ("2025 ORG", "2025 TRN")
 * name no season and are never current.
 *
 * If no class at all is named that way (another school's naming), the
 * term's or course's own end date decides instead, and failing that every
 * class is kept rather than none.
 */
public final class Terms {

    private Terms() {}

    private static final Pattern SEASON =
        Pattern.compile("^\\s*(\\d{4})\\s+(Spring|Summer|Fall|Winter)\\b", Pattern.CASE_INSENSITIVE);

    /** The course records of this semester, in the order given. */
    public static List<Map<String, Object>> current(List<Map<String, Object>> records, long now) {
        List<Map<String, Object>> named = new ArrayList<>();
        boolean anySeason = false;
        for (Map<String, Object> record : records) {
            String term = termName(record);
            Matcher match = term == null ? null : SEASON.matcher(term);
            if (match == null || !match.find()) continue;
            anySeason = true;
            if (inSeason(Integer.parseInt(match.group(1)), match.group(2), now)) named.add(record);
        }
        if (anySeason) return named;
        List<Map<String, Object>> dated = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Long end = Sync.time(Json.text(Json.child(record, "term"), "end_at"));
            if (end == null) end = Sync.time(Json.text(record, "end_at"));
            if (end == null || end.longValue() > now) dated.add(record);
        }
        return dated;
    }

    static String termName(Map<String, Object> record) {
        String term = Json.text(Json.child(record, "term"), "name");
        if (term != null) return term;
        // Some courses carry the term only in their own name's brackets.
        String[] parts = Course.split(Json.text(record, "name"), Json.text(record, "course_code"));
        return parts[2];
    }

    /**
     * Whether `now` falls in a season's window. The windows overlap by a
     * couple of weeks, so a class is not dropped the day grades come out,
     * and the next term's classes appear the week they open.
     */
    static boolean inSeason(int year, String season, long now) {
        Calendar today = Calendar.getInstance(Locale.US);
        today.setTimeInMillis(now);
        if (today.get(Calendar.YEAR) != year && !"winter".equalsIgnoreCase(season)) return false;
        int day = today.get(Calendar.DAY_OF_YEAR);
        String s = season.toLowerCase(Locale.US);
        if (s.equals("spring")) return day <= 140;                  // Jan 1 to about May 20
        if (s.equals("summer")) return day >= 121 && day <= 235;    // May to about Aug 23
        if (s.equals("fall")) return day >= 213;                    // Aug 1 to Dec 31
        // Winter sessions run across the new year: December and January.
        int month = today.get(Calendar.MONTH);
        int y = today.get(Calendar.YEAR);
        return (month == Calendar.DECEMBER && y == year) || (month == Calendar.JANUARY && y == year + 1)
            || (month == Calendar.JANUARY && y == year);
    }

    /**
     * A saved sync with only this semester's classes and their work, for a
     * cache written before this filter existed or before the season turned.
     */
    public static Sync.Result current(Sync.Result saved, long now) {
        boolean anySeason = false;
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Course course : saved.courses) {
            Matcher match = course.term == null ? null : SEASON.matcher(course.term);
            if (match == null || !match.find()) continue;
            anySeason = true;
            if (inSeason(Integer.parseInt(match.group(1)), match.group(2), now)) keep.add(course.id);
        }
        if (!anySeason) return saved;
        List<Course> courses = new ArrayList<>();
        for (Course course : saved.courses) if (keep.contains(course.id)) courses.add(course);
        List<Assignment> work = new ArrayList<>();
        for (Assignment item : saved.work) if (keep.contains(item.courseId)) work.add(item);
        return new Sync.Result(work, courses, saved.unavailable, saved.fetchedAt, saved.overrides, saved.checked);
    }
}
