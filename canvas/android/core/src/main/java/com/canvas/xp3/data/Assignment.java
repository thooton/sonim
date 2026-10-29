package com.canvas.xp3.data;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * One piece of work.
 *
 * Which list an assignment appears in is derived rather than stored: work is
 * missing because it is past its date and not turned in, not because something
 * wrote "missing" on it. A stored bucket would need re-filing every time the
 * clock crossed midnight, and would disagree with the date printed beside it
 * the first time that failed.
 *
 * Everything here is decided once, when a sync turns a Canvas record into one
 * of these. What Canvas does not send with a list (the instructions, the
 * teacher's files, the student's own submission and its comments) is a
 * Detail, fetched when the assignment is opened and kept beside the list.
 */
public final class Assignment {

    public static final int ASSIGNED = 0;
    public static final int MISSING = 1;
    public static final int DONE = 2;

    /** How the work is handed in, as Canvas names it in submission_types. */
    public static final String TEXT = "online_text_entry";
    public static final String UPLOAD = "online_upload";
    public static final String URL = "online_url";
    public static final String QUIZ = "online_quiz";
    public static final String DISCUSSION = "discussion_topic";
    public static final String ON_PAPER = "on_paper";
    public static final String NONE = "none";

    /** "142:9013", the course and assignment ids that identify this to Canvas. */
    public final String id;
    public final String courseId;
    public final String assignmentId;
    public final String title;
    public final String course;
    /** Milliseconds, or null when the teacher set no date. */
    public final Long due;
    /** What the work is graded out of; 0 when it is not graded. */
    public final double points;
    /**
     * Work Canvas itself calls missing, which is the only way something with no
     * due date can be late, and the only thing that outranks a score: a zero
     * entered against work nobody handed in is a graded record, and filing that
     * under Done would be the app telling somebody they had done a thing they
     * had not.
     */
    public final boolean flaggedMissing;
    public final boolean done;
    public final Long turnedIn;
    /** The score, or null when it is ungraded, hidden or excused. */
    public final Double score;
    public final boolean excused;

    /** Canvas's submission types, in its order. Never null. */
    public final List<String> kinds;
    public final String quizId;
    public final String topicId;
    /**
     * Graded and released to the student. A grade the teacher has entered
     * but not posted never reaches a student through the API, so a score
     * here is one they are allowed to see.
     */
    public final boolean returned;
    public final Long gradedAt;
    /** The grade as the teacher set it ("A-", "complete", "9"), or null. */
    public final String grade;
    /** Attempts allowed, or -1 for as many as the student likes. */
    public final int attemptsAllowed;
    /** Attempts used so far. */
    public final int attempts;
    public final boolean locked;

    public Assignment(String courseId, String assignmentId, String title, String course, Long due,
                      double points, boolean flaggedMissing, boolean done, Long turnedIn, Double score,
                      boolean excused) {
        this(courseId, assignmentId, title, course, due, points, flaggedMissing, done, turnedIn, score, excused,
            Collections.<String>emptyList(), null, null, score != null, null, null, -1, turnedIn == null ? 0 : 1,
            false);
    }

    public Assignment(String courseId, String assignmentId, String title, String course, Long due,
                      double points, boolean flaggedMissing, boolean done, Long turnedIn, Double score,
                      boolean excused, List<String> kinds, String quizId, String topicId, boolean returned,
                      Long gradedAt, String grade, int attemptsAllowed, int attempts, boolean locked) {
        this.courseId = courseId;
        this.assignmentId = assignmentId;
        this.id = courseId + ":" + assignmentId;
        this.title = title;
        this.course = course;
        this.due = due;
        this.points = points;
        this.flaggedMissing = flaggedMissing;
        this.done = done;
        this.turnedIn = turnedIn;
        this.score = score;
        this.excused = excused;
        this.kinds = kinds == null ? Collections.<String>emptyList() : kinds;
        this.quizId = quizId;
        this.topicId = topicId;
        this.returned = returned;
        this.gradedAt = gradedAt;
        this.grade = grade;
        this.attemptsAllowed = attemptsAllowed;
        this.attempts = attempts;
        this.locked = locked;
    }

    public int state(long now) {
        if (done) return DONE;
        if (flaggedMissing) return MISSING;
        return due != null && due.longValue() < now ? MISSING : ASSIGNED;
    }

    public boolean accepts(String kind) {
        return kinds.contains(kind);
    }

    /** Whether anything can be added from the phone: typed, uploaded or linked. */
    public boolean takesWork() {
        return accepts(TEXT) || accepts(UPLOAD) || accepts(URL);
    }

    /**
     * Work with nothing to turn in (on paper, in class, or no submission at
     * all), which Canvas lets a student tick off in the planner instead.
     */
    public boolean nothingToSend() {
        // Canvas always names at least "none"; an empty list is a record
        // cached by an older version, which did not keep the types.
        if (kinds.isEmpty()) return false;
        for (String kind : kinds) {
            if (!ON_PAPER.equals(kind) && !NONE.equals(kind)) return false;
        }
        return true;
    }

    /** Whether another attempt may be turned in. */
    public boolean attemptsLeft() {
        return attemptsAllowed < 0 || attempts < attemptsAllowed;
    }

    /** "9/10", "Excused", "Turned in", or null when there is nothing to say. */
    public String scoreLabel() {
        if (excused) return "Excused";
        if (score == null) return done ? "Turned in" : null;
        return points > 0
            ? number(score.doubleValue()) + "/" + number(points)
            : number(score.doubleValue());
    }

    /**
     * The grade as a chip shows it: "18/20" on points, the letter or word
     * otherwise, so a pass/fail assignment does not read as a number.
     */
    public String gradeChip() {
        if (excused) return "Excused";
        if (!returned) return null;
        if (grade != null && score != null && !isNumber(grade)) return grade;
        return scoreLabel();
    }

    private static boolean isNumber(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * A number as a person would write it: 9 rather than 9.0, and 8.5 rather
     * than 8.500000000000001. Points and scores in Canvas are floating point,
     * and a row 240 pixels wide has no space for the difference.
     */
    public static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
