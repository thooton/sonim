package com.canvas.xp3.net;

import com.xp3.common.Http;
import com.xp3.common.Json;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The read-only half of the Canvas API, and only the routes this app asks for.
 *
 * Every route is written out here rather than assembled from anything the model
 * or the network says, which is what makes "this app cannot post, submit or
 * change a grade" a fact about the code instead of a promise in a prompt. The
 * only thing that varies is an id, and an id has been through Canvas.id.
 *
 * Responses are filtered down to named fields on the way through. That is
 * partly to keep a course's worth of JSON inside a phone's memory, and partly
 * because a submission body or an attachment blob has no bearing on when
 * something is due and no business being kept on a phone.
 */
public final class Canvas {

    /** Sleeping is injected so the tests do not spend the retry schedule in real time. */
    public interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    private static final String INCLUDE_SUBMISSION = "include%5B%5D=submission";
    private static final Pattern NEXT =
        Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?(?=\\s*(?:,|;|$))");

    private final URI base;
    private final RemoteCookies cookies;
    private final Http http;
    private final Sleeper sleeper;

    public Canvas(Config config, Http http) {
        this(config.canvasBase(), new RemoteCookies(config, http), http, milliseconds -> {
            Thread.sleep(milliseconds);
        });
    }

    Canvas(URI base, RemoteCookies cookies, Http http, Sleeper sleeper) {
        this.base = base;
        this.cookies = cookies;
        this.http = http;
        this.sleeper = sleeper;
    }

    public List<Object> courses(boolean activeOnly) throws IOException, InterruptedException {
        return list("courses?enrollment_type=student&include%5B%5D=total_scores&include%5B%5D=term"
            + (activeOnly ? "&enrollment_state=active" : ""), COURSE_FIELDS);
    }

    public Object course(String id) throws IOException, InterruptedException {
        return select(get("courses/" + id
            + "?include%5B%5D=syllabus_body&include%5B%5D=total_scores&include%5B%5D=term"), COURSE_FIELDS);
    }

    public List<Object> assignments(String course) throws IOException, InterruptedException {
        return list("courses/" + course + "/assignments?" + INCLUDE_SUBMISSION, ASSIGNMENT_FIELDS);
    }

    public Map<String, Object> assignment(String course, String id) throws IOException, InterruptedException {
        Map<String, Object> data = Json.object(get("courses/" + course + "/assignments/" + id
            + "?" + INCLUDE_SUBMISSION));
        Map<String, Object> result = select(data, ASSIGNMENT_FIELDS);
        String[] extra = { "description", "rubric", "rubric_settings", "lock_info", "lock_explanation",
            "submission_types", "allowed_extensions" };
        for (int i = 0; i < extra.length; i++) {
            if (data.containsKey(extra[i])) result.put(extra[i], data.get(extra[i]));
        }
        return result;
    }

    public Object grading(String course) throws IOException, InterruptedException {
        return list("courses/" + course + "/assignment_groups",
            new String[] { "id", "name", "group_weight", "rules", "position" });
    }

    public Object enrollment(String course) throws IOException, InterruptedException {
        return list("courses/" + course + "/enrollments?user_id=self&type%5B%5D=StudentEnrollment",
            new String[] { "course_id", "type", "enrollment_state", "grades", "html_url" });
    }

    public Object gradingStandard(String course, String standard) throws IOException, InterruptedException {
        return get("courses/" + course + "/grading_standards/" + standard);
    }

    public Object posts(String course, boolean announcements) throws IOException, InterruptedException {
        return list("courses/" + course + "/discussion_topics?order_by=recent_activity&only_announcements="
            + announcements, POST_FIELDS);
    }

    public Object post(String course, String topic) throws IOException, InterruptedException {
        return select(get("courses/" + course + "/discussion_topics/" + topic), POST_FIELDS);
    }

    public Object entries(String course, String topic) throws IOException, InterruptedException {
        return list("courses/" + course + "/discussion_topics/" + topic + "/entries", ENTRY_FIELDS);
    }

    public Object replies(String course, String topic, String entry) throws IOException, InterruptedException {
        return list("courses/" + course + "/discussion_topics/" + topic + "/entries/" + entry + "/replies",
            ENTRY_FIELDS);
    }

    public Object pages(String course) throws IOException, InterruptedException {
        return list("courses/" + course + "/pages", PAGE_FIELDS);
    }

    public Object page(String course, String page) throws IOException, InterruptedException {
        return select(get("courses/" + course + "/pages/page_id:" + page), PAGE_FIELDS);
    }

    /**
     * Dated work across every active course, sorted by the date that actually
     * applies to this student rather than the one printed on the assignment.
     */
    public Object due(Instant from, Instant to, boolean includeCompleted)
        throws IOException, InterruptedException {
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to (exclusive)");
        List<Object> found = new ArrayList<Object>();
        List<Object> failures = new ArrayList<Object>();
        for (Object c : courses(true)) {
            Map<String, Object> course = Json.object(c);
            String id = id(course, "id");
            try {
                for (Object a : assignments(id)) {
                    Map<String, Object> assignment = new LinkedHashMap<String, Object>(Json.object(a));
                    Map<String, Object> submission = Json.child(assignment, "submission");
                    Object date = submission.containsKey("cached_due_date")
                        ? submission.get("cached_due_date")
                        : assignment.get("due_at");
                    if (!(date instanceof String)) continue;
                    Instant due = Instant.parse((String) date);
                    if (due.isBefore(from) || !due.isBefore(to)) continue;
                    if (!includeCompleted && Submissions.completed(submission)) continue;
                    assignment.put("effective_due_at", date);
                    assignment.put("course_name", course.get("name"));
                    assignment.put("course_id", course.get("id"));
                    found.add(assignment);
                }
            } catch (IOException e) {
                failures.add(Json.map("course_id", id, "error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                failures.add(Json.map("course_id", id, "error", "Invalid Canvas data"));
            }
        }
        Collections.sort(found, (left, right) -> {
            return Instant.parse((String) Json.object(left).get("effective_due_at"))
                .compareTo(Instant.parse((String) Json.object(right).get("effective_due_at")));
        });
        return Json.map("from", from.toString(), "to_exclusive", to.toString(), "assignments", found,
            "complete", Boolean.valueOf(failures.isEmpty()), "unavailable_courses", failures,
            "scope", "Active student enrollments; dated assignments only. No-date work is available via list_assignments.");
    }

    private Object get(String path) throws IOException, InterruptedException {
        return Json.parse(request(base.resolve("api/v1/" + path)).body());
    }

    private List<Object> list(String path, String[] fields) throws IOException, InterruptedException {
        URI next = base.resolve("api/v1/" + path + (path.contains("?") ? "&" : "?") + "per_page=100");
        Set<URI> seen = new HashSet<URI>();
        List<Object> all = new ArrayList<Object>();
        int chars = 0;
        while (next != null) {
            if (!seen.add(next) || seen.size() > 100) {
                throw new IOException("Canvas pagination limit or cycle; results are incomplete");
            }
            Http.Response response = request(next);
            for (Object item : Json.array(Json.parse(response.body()))) {
                Map<String, Object> selected = select(item, fields);
                chars += Json.write(selected).length();
                if (chars > 2000000) throw new IOException("Canvas list too large; results are incomplete");
                all.add(selected);
            }
            Matcher match = NEXT.matcher(join(response.all("Link")));
            next = match.find() ? next.resolve(match.group(1)) : null;
        }
        return all;
    }

    private static String join(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) joined.append(',');
            joined.append(values.get(i));
        }
        return joined.toString();
    }

    /**
     * One Canvas request, with the one retry that is worth having.
     *
     * A 401 means the cached cookie has gone stale, which is ordinary: the
     * service refreshes its own Canvas login on its own schedule, and the
     * window either side of that is exactly when a request lands wrong. So the
     * cookie is dropped and the read is tried again on a backing-off schedule.
     *
     * Unlike the desktop program this came from, it gives up. There, waiting
     * indefinitely was reasonable because a person was watching a terminal and
     * could press Ctrl-C. Here the caller is a list that has to say something
     * to somebody holding a phone, and "still trying, a minute in" is a thing it
     * can say. Failing after about half a minute and showing the last-known
     * list beats a spinner with no end written into it.
     */
    private Http.Response request(URI uri) throws IOException, InterruptedException {
        validatePage(base, uri);
        long delay = 1000;
        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Http.Response response;
            try {
                response = http.send(Http.Request.to(uri)
                    .timeout(60000)
                    .header("Accept", "application/json")
                    .header("Cookie", cookies.fetch()));
            } catch (IOException e) {
                if (attempt == 0) throw e;
                // Mid-recovery the service itself may be briefly unavailable;
                // that is the same failure, not a new one.
                if (attempt >= RETRIES) throw e;
                cookies.invalidate();
                sleeper.sleep(delay);
                delay = Math.min(16000, delay * 2);
                continue;
            }
            if (response.status() == 401) {
                cookies.invalidate();
                if (attempt >= RETRIES) {
                    throw new IOException("Canvas would not accept the session; the cookie service's own login may have expired");
                }
                sleeper.sleep(delay);
                delay = Math.min(16000, delay * 2);
                continue;
            }
            Http.requireSuccess(response.status(), "Canvas");
            if (!response.header("Content-Type").toLowerCase(Locale.US).contains("application/json")) {
                throw new IOException("Canvas returned a login page rather than data; the cookie service's Canvas login needs refreshing");
            }
            return response;
        }
    }

    /** 1s, 2s, 4s, 8s, 16s: about half a minute before it says so. */
    private static final int RETRIES = 5;

    /**
     * A pagination URL arrives in a response header, which makes it the one URL
     * in this client the app did not write. It is checked back against the
     * configured origin before it is used, so a Link header cannot walk the
     * session cookie off to another host or out of the read-only API.
     */
    static void validatePage(URI base, URI uri) throws IOException {
        if (!equal(base.getScheme(), uri.getScheme()) || !equal(base.getHost(), uri.getHost())
            || base.getPort() != uri.getPort() || uri.getUserInfo() != null || uri.getFragment() != null
            || uri.getRawPath() == null || !uri.getRawPath().startsWith("/api/v1/")
            || uri.getRawPath().contains("%") || !uri.normalize().equals(uri)) {
            throw new IOException("Refused a Canvas address outside the configured API");
        }
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    static Map<String, Object> select(Object value, String[] fields) {
        Map<String, Object> source = Json.object(value);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i++) {
            if (source.containsKey(fields[i])) result.put(fields[i], source.get(fields[i]));
        }
        if (result.get("submission") instanceof Map) {
            result.put("submission", select(result.get("submission"), SUBMISSION_FIELDS));
        }
        return result;
    }

    private static final String[] SUBMISSION_FIELDS = { "score", "grade", "submitted_at", "graded_at",
        "workflow_state", "late", "missing", "excused", "cached_due_date", "points_deducted",
        "late_policy_status", "attempt", "assignment_id" };
    private static final String[] COURSE_FIELDS = { "id", "name", "course_code", "workflow_state", "term",
        "time_zone", "enrollments", "apply_assignment_group_weights", "grading_standard_id",
        "hide_final_grades", "syllabus_body", "public_description", "start_at", "end_at", "grading_periods" };
    private static final String[] ASSIGNMENT_FIELDS = { "id", "course_id", "name", "due_at", "unlock_at",
        "lock_at", "points_possible", "grading_type", "assignment_group_id", "html_url", "submission",
        "has_submitted_submissions", "published", "locked_for_user", "omit_from_final_grade",
        "in_closed_grading_period", "is_quiz_assignment" };
    private static final String[] POST_FIELDS = { "id", "title", "message", "html_url", "posted_at",
        "last_reply_at", "user_name", "is_announcement", "assignment_id", "discussion_subentry_count",
        "published", "pinned", "locked", "locked_for_user", "lock_explanation", "require_initial_post",
        "user_can_see_posts" };
    private static final String[] ENTRY_FIELDS = { "id", "parent_id", "user_id", "user_name", "message",
        "created_at", "updated_at", "deleted", "has_more_replies" };
    private static final String[] PAGE_FIELDS = { "page_id", "url", "title", "body", "html_url",
        "created_at", "updated_at", "published", "front_page", "locked_for_user", "lock_explanation" };

    /**
     * Accepts a positive whole number. This is the only value that is
     * interpolated into a Canvas path; all other route components are fixed
     * literals defined above.
     */
    public static String id(Map<String, Object> args, String key) {
        Object value = args.get(key);

        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(
                "The '" + key + "' field needs to be a number."
            );
        }

        long parsed;

        try {
            parsed = new BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                "The '" + key + "' field needs to be a whole number."
            );
        }

        if (parsed <= 0) {
            throw new IllegalArgumentException(
                "The '" + key + "' field needs to be a positive number."
            );
        }

        return Long.toString(parsed);
    }
}
