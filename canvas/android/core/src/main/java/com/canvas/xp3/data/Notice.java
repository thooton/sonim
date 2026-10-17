package com.canvas.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One line on the Notifications screen.
 *
 * Canvas's activity stream says a great deal about each event; a line here
 * says what happened and to what, in the fewest words that do: "Work
 * returned: 10/10" over "Chapter 4 in Abbott". No line hurries anyone. A
 * reminder states the date the work is due, never how long is left.
 */
public final class Notice {

    public static final String RETURNED = "returned";
    public static final String POSTED = "posted";
    public static final String COMMENT = "comment";
    public static final String REMINDER = "reminder";
    public static final String MESSAGE = "message";

    public final String kind;
    public final String title;
    public final String detail;
    public final long at;
    public final String courseId;
    public final String assignmentId;
    public final String topicId;

    public Notice(String kind, String title, String detail, long at, String courseId, String assignmentId,
                  String topicId) {
        this.kind = kind;
        this.title = title;
        this.detail = detail;
        this.at = at;
        this.courseId = courseId;
        this.assignmentId = assignmentId;
        this.topicId = topicId;
    }

    /**
     * The activity stream as lines, newest first. `courses` names a class by
     * its short code; an event in a class the app does not know keeps
     * Canvas's own wording.
     */
    public static List<Notice> from(List<Object> stream, List<Course> courses) {
        List<Notice> out = new ArrayList<>();
        for (Object entry : stream) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> record = Json.object(entry);
            Long at = Sync.time(Json.text(record, "updated_at"));
            if (at == null) at = Sync.time(Json.text(record, "created_at"));
            if (at == null) continue;
            String type = String.valueOf(Json.text(record, "type"));
            String course = id(record, "course_id");
            String code = code(courses, course);
            String title = Json.text(record, "title");
            if ("Submission".equals(type)) {
                Map<String, Object> assignment = Json.child(record, "assignment");
                String name = Json.text(assignment, "name");
                if (name == null) name = title == null ? "Your work" : title;
                String assignmentId = id(assignment, "id");
                if (assignmentId == null) assignmentId = id(record, "assignment_id");
                List<Object> comments = Json.children(record, "submission_comments");
                Double score = Json.number(record, "score");
                String grade = Json.text(record, "grade");
                if (!comments.isEmpty()) {
                    Map<String, Object> last = Json.object(comments.get(comments.size() - 1));
                    String author = Json.text(last, "author_name");
                    out.add(new Notice(COMMENT, (author == null ? "Your teacher" : Course.shortName(author))
                        + " replied", "Private comment · " + name, at.longValue(), course, assignmentId, null));
                } else if (score != null || grade != null) {
                    Double points = Json.number(assignment, "points_possible");
                    String shown = grade != null && score != null && !numeric(grade) ? grade
                        : score == null ? grade
                        : points != null && points.doubleValue() > 0
                            ? Assignment.number(score.doubleValue()) + "/" + Assignment.number(points.doubleValue())
                            : Assignment.number(score.doubleValue());
                    out.add(new Notice(RETURNED, "Work returned: " + shown, name, at.longValue(), course,
                        assignmentId, null));
                }
            } else if ("Announcement".equals(type)) {
                out.add(new Notice(POSTED, "New in " + (code == null ? "your class" : code),
                    title == null ? "Announcement" : title, at.longValue(), course, null,
                    id(record, "announcement_id")));
            } else if ("DiscussionTopic".equals(type)) {
                out.add(new Notice(POSTED, "New discussion in " + (code == null ? "your class" : code),
                    title == null ? "Discussion" : title, at.longValue(), course, null,
                    id(record, "discussion_topic_id")));
            } else if ("Conversation".equals(type)) {
                out.add(new Notice(MESSAGE, "New message", title == null ? "Inbox" : title, at.longValue(),
                    course, null, null));
            } else if ("Message".equals(type) && title != null) {
                String category = Json.text(record, "notification_category");
                out.add(new Notice(MESSAGE, title, category == null ? (code == null ? "Canvas" : code) : category,
                    at.longValue(), course, null, null));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    private static boolean numeric(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String code(List<Course> courses, String id) {
        if (id == null) return null;
        for (Course course : courses) {
            if (course.id.equals(id)) return course.code;
        }
        return null;
    }

    private static String id(Map<String, Object> record, String key) {
        Object value = record.get(key);
        if (!(value instanceof Number) || ((Number) value).longValue() <= 0) return null;
        return Long.toString(((Number) value).longValue());
    }

    Map<String, Object> toMap() {
        return Json.map("kind", kind, "title", title, "detail", detail, "at", Long.valueOf(at),
            "courseId", courseId, "assignmentId", assignmentId, "topicId", topicId);
    }

    static Notice fromMap(Map<String, Object> map) {
        String kind = Json.text(map, "kind");
        String title = Json.text(map, "title");
        Double at = Json.number(map, "at");
        if (kind == null || title == null || at == null) return null;
        String detail = Json.text(map, "detail");
        return new Notice(kind, title, detail == null ? "" : detail, (long) at.doubleValue(),
            Json.text(map, "courseId"), Json.text(map, "assignmentId"), Json.text(map, "topicId"));
    }
}
