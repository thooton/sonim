package com.canvas.xp3.data;

import java.math.BigDecimal;

/**
 * One piece of work.
 *
 * Which list an assignment appears in is derived rather than stored: work is
 * missing because it is past its date and not turned in, not because something
 * wrote "missing" on it. A stored bucket would need re-filing every time the
 * clock crossed midnight, and would disagree with the date printed beside it
 * the first time that failed.
 *
 * Everything here except the details is decided once, when a sync turns a
 * Canvas record into one of these. The details are the exception because
 * Canvas does not send them with a list: a course's assignments arrive without
 * their instructions, and fetching every one of them to draw a list of titles
 * would be a request per row. So they arrive when a page is opened, and are
 * kept once they have.
 */
public final class Assignment {

    public static final int ASSIGNED = 0;
    public static final int MISSING = 1;
    public static final int DONE = 2;

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

    /**
     * Null until the detail page has fetched it.
     *
     * Volatile because it is the one field written off the main thread: the
     * fetch happens on a worker and the page that draws it is redrawn from the
     * main one, so without this the redraw is entitled to keep seeing null.
     */
    private volatile String details;

    public Assignment(String courseId, String assignmentId, String title, String course, Long due,
                      double points, boolean flaggedMissing, boolean done, Long turnedIn, Double score,
                      boolean excused) {
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
    }

    public int state(long now) {
        if (done) return DONE;
        if (flaggedMissing) return MISSING;
        return due != null && due.longValue() < now ? MISSING : ASSIGNED;
    }

    /** True while the work is turned in ahead of a date that has not arrived. */
    public boolean early(long now) {
        return done && due != null && due.longValue() > now;
    }

    public String details() {
        return details;
    }

    public boolean hasDetails() {
        return details != null;
    }

    public void setDetails(String value) {
        details = value;
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
     * A number as a person would write it: 9 rather than 9.0, and 8.5 rather
     * than 8.500000000000001. Points and scores in Canvas are floating point,
     * and a row 240 pixels wide has no space for the difference.
     */
    public static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
