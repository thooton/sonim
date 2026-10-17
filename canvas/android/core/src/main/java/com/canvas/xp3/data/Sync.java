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
        /** Every active class, in the order the Classes screen lists them. */
        public final List<Course> courses;
        /** Courses that could not be read, by name, for the banner. */
        public final List<String> unavailable;
        public final long fetchedAt;

        public Result(List<Assignment> work, List<String> unavailable, long fetchedAt) {
            this(work, new ArrayList<Course>(), unavailable, fetchedAt);
        }

        /**
         * The student's planner check marks: assignment id to the override
         * that records it, and which of them are ticked. The override id is
         * kept so unticking updates that record instead of adding another.
         */
        public final java.util.Map<String, String> overrides;
        public final java.util.Set<String> checked;

        public Result(List<Assignment> work, List<Course> courses, List<String> unavailable, long fetchedAt) {
            this(work, courses, unavailable, fetchedAt, new java.util.HashMap<String, String>(),
                new java.util.HashSet<String>());
        }

        public Result(List<Assignment> work, List<Course> courses, List<String> unavailable, long fetchedAt,
                      java.util.Map<String, String> overrides, java.util.Set<String> checked) {
            this.work = work;
            this.courses = courses;
            this.unavailable = unavailable;
            this.fetchedAt = fetchedAt;
            this.overrides = overrides;
            this.checked = checked;
        }

        public Course course(String id) {
            for (Course course : courses) {
                if (course.id.equals(id)) return course;
            }
            return null;
        }

        public boolean complete() {
            return unavailable.isEmpty();
        }
    }

    public Result run() throws IOException, InterruptedException {
        List<Assignment> work = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object entry : canvas.courses(true)) records.add(Json.object(entry));
        List<Course> courses = courses(records);
        for (Map<String, Object> course : records) {
            String name = courseName(course);
            String id;
            try {
                id = Canvas.id(course, "id");
            } catch (RuntimeException e) {
                unavailable.add(name);
                continue;
            }
            Course known = null;
            for (Course candidate : courses) {
                if (candidate.id.equals(id)) known = candidate;
            }
            if (known != null) name = known.title;
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
        java.util.Map<String, String> overrides = new java.util.HashMap<>();
        java.util.Set<String> checked = new java.util.HashSet<>();
        try {
            for (Object entry : canvas.plannerOverrides()) {
                Map<String, Object> override = Json.object(entry);
                if (!"assignment".equals(Json.text(override, "plannable_type"))) continue;
                String assignment = optionalId(override, "plannable_id");
                String id = optionalId(override, "id");
                if (assignment == null || id == null) continue;
                overrides.put(assignment, id);
                if (Json.flag(override, "marked_complete")) checked.add(assignment);
            }
        } catch (IOException e) {
            // Check marks are a convenience; the work itself is what this
            // sync is for, and it is complete without them.
        }
        return new Result(work, courses, unavailable, System.currentTimeMillis(), overrides, checked);
    }

    /**
     * The classes, each with a colour that stays put: colours are dealt out
     * in the order of the course codes, so a sync that returns the courses in
     * another order, or a refresh mid-term, does not repaint anything.
     */
    static List<Course> courses(List<Map<String, Object>> records) {
        List<Map<String, Object>> sorted = new ArrayList<>(records);
        java.util.Collections.sort(sorted, (left, right) -> {
            String a = Course.split(Json.text(left, "name"), Json.text(left, "course_code"))[0];
            String b = Course.split(Json.text(right, "name"), Json.text(right, "course_code"))[0];
            return a.compareToIgnoreCase(b);
        });
        List<Course> courses = new ArrayList<>();
        for (Map<String, Object> record : sorted) {
            Course course = Course.from(record, courses.size() % Course.COLORS);
            if (course != null) courses.add(course);
        }
        return courses;
    }

    /** Everything about one assignment that a list leaves out, fetched when its page is opened. */
    public Detail details(Assignment item) throws IOException, InterruptedException {
        Map<String, Object> full = canvas.assignment(item.courseId, item.assignmentId);
        Map<String, Object> submission;
        try {
            submission = canvas.submission(item.courseId, item.assignmentId);
        } catch (IOException e) {
            // The instructions are still worth showing without the student's
            // own work under them; the page says the rest could not be read.
            submission = null;
        }
        return Detail.from(full, submission, System.currentTimeMillis());
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

        List<String> kinds = new ArrayList<>();
        for (Object kind : Json.children(record, "submission_types")) {
            if (kind instanceof String) kinds.add((String) kind);
        }
        String quiz = record.get("quiz_id") instanceof Number ? optionalId(record, "quiz_id") : null;
        String topic = null;
        Map<String, Object> discussion = Json.child(record, "discussion_topic");
        if (discussion.get("id") instanceof Number) topic = optionalId(discussion, "id");
        // Returned means graded with a score the student can see. Canvas
        // withholds a score that has not been posted, so its presence is
        // the test, and "graded" alone (a teacher mid-way through) is not.
        boolean returned = score != null && "graded".equals(Json.text(submission, "workflow_state"));
        Double allowed = Json.number(record, "allowed_attempts");
        Double attempt = Json.number(submission, "attempt");

        return new Assignment(courseId, id, title.trim(), courseName, due,
            points == null ? 0 : points.doubleValue(), missing, done,
            time(Json.text(submission, "submitted_at")), score, excused,
            kinds, quiz, topic, returned, time(Json.text(submission, "graded_at")),
            Json.text(submission, "grade"),
            allowed == null || allowed.doubleValue() <= 0 ? -1 : (int) allowed.doubleValue(),
            attempt == null ? 0 : (int) attempt.doubleValue(),
            Json.flag(record, "locked_for_user"));
    }

    private static String optionalId(Map<String, Object> record, String key) {
        try {
            return Canvas.id(record, key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A Canvas timestamp in milliseconds, or null.
     *
     * Canvas sends UTC with a Z, which is what Instant reads, but a Canvas
     * instance behind a proxy or an older version can send a numeric offset
     * instead, and a date that fails to parse silently would move a piece of
     * work into "No due date" where nobody would look for it.
     */
    public static Long time(String value) {
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
