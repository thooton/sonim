package com.canvas.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One class, as the app names and colours it.
 *
 * Canvas names a course the way a registrar does, "THF 101: Acting
 * Introduction (2026 Fall C)", which is three facts on one line that is too
 * narrow for any of them. They are pulled apart once, here: the code goes on
 * the course's tile and beside every piece of its work, the title leads the
 * class page, and the term is said once, on the Classes screen.
 */
public final class Course {

    /**
     * How many course colours there are. The app holds the colours
     * themselves; core only hands out a stable index, so the same class is the
     * same colour on every screen and after every sync.
     */
    public static final int COLORS = 8;

    private static final Pattern CODED =
        Pattern.compile("^\\s*([A-Za-z]{2,5})\\s?(\\d{2,4}[A-Za-z]?)\\s*[:\\-–]\\s*(.+?)\\s*$");
    private static final Pattern TERM = Pattern.compile("^(.*?)\\s*\\(([^()]*)\\)\\s*$");

    public final String id;
    /** "Acting Introduction". */
    public final String title;
    /** "THF 101", or a shortened title when Canvas's name carries no code. */
    public final String code;
    /** "2026 Fall C", or null. */
    public final String term;
    /** Teachers' names as Canvas gives them, first teacher first. */
    public final List<String> teachers;
    public final int color;
    /** The overall percentage, or null when the teacher does not share it. */
    public final Double score;
    /** The overall letter, or null. */
    public final String grade;

    public Course(String id, String title, String code, String term, List<String> teachers, int color,
                  Double score, String grade) {
        this.id = id;
        this.title = title;
        this.code = code;
        this.term = term;
        this.teachers = teachers;
        this.color = color;
        this.score = score;
        this.grade = grade;
    }

    /** "THF 101" on its own line of a tile: the letters, then the number. */
    public String codeLetters() {
        int space = code.indexOf(' ');
        return space < 0 ? code : code.substring(0, space);
    }

    public String codeNumber() {
        int space = code.indexOf(' ');
        return space < 0 ? "" : code.substring(space + 1);
    }

    /** "M. Okafor": the first teacher, initial and surname. */
    public String teacher() {
        return teachers.isEmpty() ? null : shortName(teachers.get(0));
    }

    /** "Monica Okafor" to "M. Okafor"; a single name stays as it is. */
    public static String shortName(String name) {
        String trimmed = name == null ? "" : name.trim();
        int space = trimmed.lastIndexOf(' ');
        if (space <= 0) return trimmed;
        return trimmed.charAt(0) + ". " + trimmed.substring(space + 1);
    }

    /** Two letters for an avatar: "MO" for Monica Okafor. */
    public static String initials(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() == 0) return "?";
        String[] parts = trimmed.split("\\s+");
        String first = parts[0].substring(0, 1);
        String last = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase(java.util.Locale.US);
    }

    /**
     * Splits Canvas's name into code, title and term.
     *
     * Returns {code, title, term}; term may be null. A name without a code
     * ("Pitchfork Pantry Fall 2026") keeps its whole name as the title and
     * borrows Canvas's course_code, or failing that its own first word, as
     * the code, so every course has something short to show on a tile.
     */
    public static String[] split(String name, String courseCode) {
        String rest = name == null ? "" : name.trim();
        String term = null;
        Matcher bracket = TERM.matcher(rest);
        if (bracket.matches() && bracket.group(1).length() > 0) {
            rest = bracket.group(1).trim();
            term = bracket.group(2).trim();
        }
        Matcher coded = CODED.matcher(rest);
        if (coded.matches()) {
            return new String[] { coded.group(1).toUpperCase(java.util.Locale.US) + " " + coded.group(2),
                coded.group(3), term };
        }
        String code = courseCode == null ? "" : courseCode.trim();
        Matcher fromCode = CODED.matcher(code + ": x");
        if (fromCode.matches()) {
            code = fromCode.group(1).toUpperCase(java.util.Locale.US) + " " + fromCode.group(2);
        } else if (code.length() == 0 || code.length() > 8) {
            String first = rest.split("\\s+")[0];
            code = first.length() > 8 ? first.substring(0, 8) : first;
        }
        return new String[] { code, rest.length() == 0 ? "Course" : rest, term };
    }

    /**
     * A Canvas course record, with its colour slot. Null when it has no
     * usable id, which a sync reports as an unreadable course.
     */
    static Course from(Map<String, Object> record, int color) {
        Object id = record.get("id");
        if (!(id instanceof Number)) return null;
        String identifier = Long.toString(((Number) id).longValue());
        String[] parts = split(Json.text(record, "name"), Json.text(record, "course_code"));
        String term = parts[2];
        if (term == null) term = Json.text(Json.child(record, "term"), "name");
        List<String> teachers = new ArrayList<>();
        for (Object entry : Json.children(record, "teachers")) {
            if (!(entry instanceof Map)) continue;
            String name = Json.text(Json.object(entry), "display_name");
            if (name != null && name.trim().length() > 0) teachers.add(name.trim());
        }
        Double score = null;
        String grade = null;
        // The overall grade is the teacher's to share. Canvas hides it from a
        // student with hide_final_grades, and then this app does too.
        if (!Json.flag(record, "hide_final_grades")) {
            for (Object entry : Json.children(record, "enrollments")) {
                if (!(entry instanceof Map)) continue;
                Map<String, Object> enrollment = Json.object(entry);
                if (!"student".equals(Json.text(enrollment, "type"))) continue;
                score = Json.number(enrollment, "computed_current_score");
                grade = Json.text(enrollment, "computed_current_grade");
            }
        }
        return new Course(identifier, parts[1], parts[0], term, teachers, color, score, grade);
    }
}
