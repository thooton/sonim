package com.canvas.xp3.data;

import com.canvas.xp3.net.Canvas;
import com.xp3.common.Json;
import com.canvas.xp3.net.Submissions;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One pass over Canvas, turned into the list the three tabs are drawn from.
 *
 * A course that cannot be read is reported rather than thrown. Enrolments go
 * wrong in ordinary ways, one course at a time: a section is concluded, a
 * teacher restricts a term, an assignment group is locked. If any one of those
 * emptied the whole screen, the app would be at its least useful exactly when
 * something is overdue in one of the courses that still works. So the work that
 * was readable is returned, and the names that were not come back beside it for
 * the list to say so in a line at the top.
 */
public final class Sync {

    /** A ceiling, so that a term with a thousand quiz questions cannot exhaust a phone. */
    private static final int MAX_WORK = 2000;

    private final Canvas canvas;

    public Sync(Canvas canvas) {
        this.canvas = canvas;
    }

    public static final class Result {

        public final List<Assignment> work;
        /** Courses that could not be read, by name, for the banner. */
        public final List<String> unavailable;
        public final long fetchedAt;

        public Result(List<Assignment> work, List<String> unavailable, long fetchedAt) {
            this.work = work;
            this.unavailable = unavailable;
            this.fetchedAt = fetchedAt;
        }

        public boolean complete() {
            return unavailable.isEmpty();
        }
    }

    public Result run() throws IOException, InterruptedException {
        List<Assignment> work = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (Object entry : canvas.courses(true)) {
            Map<String, Object> course = Json.object(entry);
            String name = courseName(course);
            String id;
            try {
                id = Canvas.id(course, "id");
            } catch (RuntimeException e) {
                unavailable.add(name);
                continue;
            }
            try {
                for (Object item : canvas.assignments(id)) {
                    if (work.size() >= MAX_WORK) break;
                    Assignment mapped = map(id, name, Json.object(item));
                    if (mapped != null) work.add(mapped);
                }
            } catch (IOException e) {
                unavailable.add(name);
            } catch (RuntimeException e) {
                unavailable.add(name);
            }
        }
        return new Result(work, unavailable, System.currentTimeMillis());
    }

    /** The instructions for one assignment, fetched when its page is opened. */
    public String details(Assignment item) throws IOException, InterruptedException {
        Map<String, Object> full = canvas.assignment(item.courseId, item.assignmentId);
        String body = Html.toMarkdown(Json.text(full, "description"));
        String locked = Json.text(full, "lock_explanation");
        if (body.trim().length() == 0) {
            body = locked != null ? Html.toMarkdown(locked) : "No instructions were posted.";
        } else if (locked != null) {
            body = Html.toMarkdown(locked) + "\n\n" + body;
        }
        return body;
    }

    static String courseName(Map<String, Object> course) {
        String name = Json.text(course, "name");
        if (name != null && name.trim().length() > 0) return name.trim();
        String code = Json.text(course, "course_code");
        return code != null && code.trim().length() > 0 ? code.trim() : "Course";
    }

    /**
     * One Canvas assignment record as a piece of work, or null when it is not
     * one: an unpublished assignment is a draft the teacher is still writing
     * and has no business appearing on a student's list.
     */
    static Assignment map(String courseId, String courseName, Map<String, Object> record) {
        if (record.containsKey("published") && !Json.flag(record, "published")) return null;
        String id;
        try {
            id = Canvas.id(record, "id");
        } catch (RuntimeException e) {
            return null;
        }
        String title = Json.text(record, "name");
        if (title == null || title.trim().length() == 0) title = "Untitled";

        Map<String, Object> submission = Json.child(record, "submission");
        // The personal due date wins where Canvas returns one. A present null
        // means this student has no override, not that the work has no date.
        Long due = null;
        if (submission.containsKey("cached_due_date")) due = time(Json.text(submission, "cached_due_date"));
        if (due == null) due = time(Json.text(record, "due_at"));

        boolean missing = Submissions.missing(submission);
        boolean done = !missing && Submissions.turnedIn(submission);
        boolean excused = Json.flag(submission, "excused");
        Double points = Json.number(record, "points_possible");
        Double score = Json.number(submission, "score");

        return new Assignment(courseId, id, title.trim(), courseName, due,
            points == null ? 0 : points.doubleValue(), missing, done,
            time(Json.text(submission, "submitted_at")), score, excused);
    }

    /**
     * A Canvas timestamp in milliseconds, or null.
     *
     * Canvas sends UTC with a Z, which is what Instant reads, but a Canvas
     * instance behind a proxy or an older version can send a numeric offset
     * instead, and a date that fails to parse silently would move a piece of
     * work into "No due date" where nobody would look for it.
     */
    static Long time(String value) {
        if (value == null || value.trim().length() == 0) return null;
        try {
            return Long.valueOf(Instant.parse(value.trim()).toEpochMilli());
        } catch (RuntimeException first) {
            try {
                return Long.valueOf(OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli());
            } catch (RuntimeException second) {
                return null;
            }
        }
    }
}
