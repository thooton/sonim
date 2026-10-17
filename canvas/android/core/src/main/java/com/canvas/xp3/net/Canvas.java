package com.canvas.xp3.net;

import com.xp3.common.Http;
import com.xp3.common.Json;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
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
 * The Canvas API, and only the routes this app asks for.
 *
 * Every route is written out here rather than assembled from anything the
 * network says, which is what keeps "this app can turn in work, comment and
 * answer a quiz, and nothing else" a fact about the code instead of a promise.
 * The only thing that varies in a path is an id, and an id has been through
 * Canvas.id. The writes are the student's own: a submission, a comment on it,
 * a reply in a discussion, a planner check mark and a classic quiz attempt.
 * Nothing here can touch a grade, another student's work or a course.
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
            + "&include%5B%5D=teachers"
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

    // ------------------------------------------------------------------
    // A class: its classwork, stream, people and one item's pages
    // ------------------------------------------------------------------

    /**
     * The course's modules with their items, which is how most courses lay
     * out their classwork. Canvas leaves a module's items out of this list
     * when there are too many of them, so those are fetched on their own.
     */
    public List<Object> modules(String course) throws IOException, InterruptedException {
        List<Object> modules = list("courses/" + course + "/modules?include%5B%5D=items&include%5B%5D=content_details",
            MODULE_FIELDS);
        for (Object entry : modules) {
            Map<String, Object> module = Json.object(entry);
            if (module.get("items") instanceof List) {
                module.put("items", selectAll(Json.array(module.get("items")), ITEM_FIELDS));
                continue;
            }
            String id;
            try {
                id = id(module, "id");
            } catch (IllegalArgumentException e) {
                continue;
            }
            module.put("items", list("courses/" + course + "/modules/" + id
                + "/items?include%5B%5D=content_details", ITEM_FIELDS));
        }
        return modules;
    }

    public List<Object> announcements(String course) throws IOException, InterruptedException {
        return list("courses/" + course + "/discussion_topics?only_announcements=true", POST_FIELDS);
    }

    /** Teachers, assistants and classmates, each with the kind of enrolment they have. */
    public List<Object> people(String course) throws IOException, InterruptedException {
        return list("courses/" + course + "/users?include%5B%5D=enrollments"
            + "&enrollment_type%5B%5D=teacher&enrollment_type%5B%5D=ta&enrollment_type%5B%5D=student",
            PERSON_FIELDS);
    }

    /** This student's submission, with the teacher's comments and any rubric marks. */
    public Map<String, Object> submission(String course, String assignment) throws IOException, InterruptedException {
        Map<String, Object> data = Json.object(get("courses/" + course + "/assignments/" + assignment
            + "/submissions/self?include%5B%5D=submission_comments&include%5B%5D=rubric_assessment"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < OWN_SUBMISSION_FIELDS.length; i++) {
            String key = OWN_SUBMISSION_FIELDS[i];
            if (data.containsKey(key)) result.put(key, data.get(key));
        }
        if (result.get("attachments") instanceof List) {
            result.put("attachments", selectAll(Json.array(result.get("attachments")), FILE_FIELDS));
        }
        if (result.get("submission_comments") instanceof List) {
            result.put("submission_comments", selectAll(Json.array(result.get("submission_comments")),
                COMMENT_FIELDS));
        }
        return result;
    }

    public Map<String, Object> file(String id) throws IOException, InterruptedException {
        return select(get("files/" + id), FILE_FIELDS);
    }

    public Map<String, Object> pageById(String course, String id) throws IOException, InterruptedException {
        return select(get("courses/" + course + "/pages/page_id:" + id), PAGE_FIELDS);
    }

    /** Recent grades, comments, announcements and messages, newest first. */
    public List<Object> activity() throws IOException, InterruptedException {
        return selectAll(Json.array(get("users/self/activity_stream?per_page=40")), ACTIVITY_FIELDS);
    }

    /** The check marks this student has put against work in their planner. */
    public List<Object> plannerOverrides() throws IOException, InterruptedException {
        return list("planner/overrides", OVERRIDE_FIELDS);
    }

    public Map<String, Object> quiz(String course, String quiz) throws IOException, InterruptedException {
        return select(get("courses/" + course + "/quizzes/" + quiz), QUIZ_FIELDS);
    }

    /** This student's attempts at a quiz; the last is the one in progress, if any. */
    public List<Object> quizAttempts(String course, String quiz) throws IOException, InterruptedException {
        return selectAll(Json.children(Json.object(get("courses/" + course + "/quizzes/" + quiz + "/submissions")),
            "quiz_submissions"), ATTEMPT_FIELDS);
    }

    public List<Object> quizQuestions(String attempt) throws IOException, InterruptedException {
        return selectAll(Json.children(Json.object(get("quiz_submissions/" + attempt + "/questions")),
            "quiz_submission_questions"), QUESTION_FIELDS);
    }

    // ------------------------------------------------------------------
    // Teacher files
    // ------------------------------------------------------------------

    /**
     * Saves a Canvas file into `out`, following the redirects its download
     * address goes through to wherever the bytes are kept.
     *
     * The session cookie only ever goes to Canvas's own download path; the
     * storage hosts it is sent on to are given nothing but their own signed
     * address. Every hop must be https, and there are at most four.
     */
    public void download(Map<String, Object> file, OutputStream out, long limit, Http.Sending progress)
        throws IOException, InterruptedException {
        String address = Json.text(file, "url");
        if (address == null) throw new IOException("This file can't be downloaded.");
        URI uri;
        try {
            uri = base.resolve(address);
        } catch (IllegalArgumentException e) {
            throw new IOException("This file can't be downloaded.");
        }
        for (int hop = 0; hop < 5; hop++) {
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IOException("Refused a download address that is not https");
            }
            Http.Request request = Http.Request.to(uri).timeout(300000).identity();
            if (canvasDownload(base, uri)) request.header("Cookie", cookies.fetch());
            Http.Stream stream = http.stream(request);
            try {
                int status = stream.status();
                if (status >= 300 && status < 400) {
                    String next = stream.header("Location");
                    if (next.length() == 0) throw new IOException("Canvas HTTP " + status + ": redirect refused");
                    try {
                        uri = uri.resolve(next);
                    } catch (IllegalArgumentException e) {
                        throw new IOException("Refused a download address that is not https");
                    }
                    continue;
                }
                Http.requireSuccess(status, "Download");
                if (stream.length() > limit) throw new IOException("This file is too large for the phone.");
                long total = stream.length();
                long done = 0;
                byte[] buffer = new byte[32768];
                InputStream in = stream.stream();
                for (int count; (count = in.read(buffer)) >= 0; ) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    done += count;
                    if (done > limit) throw new IOException("This file is too large for the phone.");
                    out.write(buffer, 0, count);
                    if (progress != null) progress.sent(done, total);
                }
                return;
            } finally {
                stream.close();
            }
        }
        throw new IOException("Download redirected too many times");
    }

    /** Canvas's own download path, the only place outside the API a cookie is sent. */
    static boolean canvasDownload(URI base, URI uri) {
        if (!equal(base.getHost(), uri.getHost()) || base.getPort() != uri.getPort()
            || !equal(base.getScheme(), uri.getScheme())) return false;
        String path = uri.getRawPath();
        return path != null && !path.contains("%") && uri.normalize().equals(uri)
            && path.matches("(/courses/\\d+|/users/\\d+)?/files/\\d+/download");
    }

    // ------------------------------------------------------------------
    // The student's own writes
    // ------------------------------------------------------------------

    /** Turns in typed work, as the HTML Canvas shows the teacher. */
    public Map<String, Object> submitText(String course, String assignment, String html, String comment)
        throws IOException, InterruptedException {
        return submit(course, assignment, comment, "submission[submission_type]", "online_text_entry",
            "submission[body]", html);
    }

    public Map<String, Object> submitUrl(String course, String assignment, String url, String comment)
        throws IOException, InterruptedException {
        URI parsed;
        try {
            parsed = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("That isn't a web address.");
        }
        if (!"https".equals(parsed.getScheme()) && !"http".equals(parsed.getScheme())) {
            throw new IOException("That isn't a web address.");
        }
        return submit(course, assignment, comment, "submission[submission_type]", "online_url",
            "submission[url]", url.trim());
    }

    /** Turns in files already uploaded with {@link #upload}. */
    public Map<String, Object> submitFiles(String course, String assignment, List<String> fileIds, String comment)
        throws IOException, InterruptedException {
        if (fileIds.isEmpty()) throw new IllegalArgumentException("Nothing to turn in");
        List<String> pairs = new ArrayList<String>();
        pairs.add("submission[submission_type]");
        pairs.add("online_upload");
        for (String file : fileIds) {
            pairs.add("submission[file_ids][]");
            pairs.add(number(file));
        }
        return submit(course, assignment, comment, pairs.toArray(new String[0]));
    }

    private Map<String, Object> submit(String course, String assignment, String comment, String... pairs)
        throws IOException, InterruptedException {
        List<String> all = new ArrayList<String>();
        Collections.addAll(all, pairs);
        if (comment != null && comment.trim().length() > 0) {
            all.add("comment[text_comment]");
            all.add(comment.trim());
        }
        return select(write("POST", "courses/" + number(course) + "/assignments/" + number(assignment)
            + "/submissions", form(all.toArray(new String[0])), FORM), OWN_SUBMISSION_FIELDS);
    }

    /** A private comment to the teacher on this student's own submission. */
    public void comment(String course, String assignment, String text) throws IOException, InterruptedException {
        write("PUT", "courses/" + number(course) + "/assignments/" + number(assignment) + "/submissions/self",
            form("comment[text_comment]", text.trim()), FORM);
    }

    /** A reply posted to a discussion or under an announcement. */
    public Map<String, Object> reply(String course, String topic, String html) throws IOException, InterruptedException {
        return select(write("POST", "courses/" + number(course) + "/discussion_topics/" + number(topic) + "/entries",
            form("message", html), FORM), ENTRY_FIELDS);
    }

    /**
     * Ticks work off in the planner, Canvas's own "mark as done" for work
     * nothing is turned in for. An existing override is updated rather than
     * a second one created.
     */
    public void markDone(String assignment, String override, boolean done) throws IOException, InterruptedException {
        if (override != null) {
            write("PUT", "planner/overrides/" + number(override),
                form("marked_complete", Boolean.toString(done)), FORM);
        } else {
            write("POST", "planner/overrides", form("plannable_type", "assignment",
                "plannable_id", number(assignment), "marked_complete", Boolean.toString(done)), FORM);
        }
    }

    /**
     * Uploads one file for a submission and returns the id to turn it in by.
     *
     * Canvas's three steps: ask Canvas where to send it, send it there, and
     * confirm with Canvas if the storage host asks for that. The storage host
     * is named by Canvas's reply, so it gets no cookie unless it is Canvas
     * itself, and the confirmation is only followed back into Canvas's API.
     */
    public String upload(String course, String assignment, String name, long size, String type,
                         InputStream body, Http.Sending progress) throws IOException, InterruptedException {
        Map<String, Object> ticket = Json.object(write("POST", "courses/" + number(course) + "/assignments/"
            + number(assignment) + "/submissions/self/files",
            form("name", name, "size", Long.toString(size), "content_type", type, "on_duplicate", "rename"), FORM));
        String target = Json.text(ticket, "upload_url");
        if (target == null) throw new IOException("Canvas did not say where to send the file");
        URI uri;
        try {
            uri = URI.create(target);
        } catch (IllegalArgumentException e) {
            throw new IOException("Refused an upload address that is not https");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IOException("Refused an upload address that is not https");
        }
        String field = Json.text(ticket, "file_param");
        String boundary = "xp3" + Long.toHexString(Double.doubleToLongBits(Math.random()))
            + Long.toHexString(System.nanoTime());
        StringBuilder head = new StringBuilder();
        for (Map.Entry<String, Object> param : Json.child(ticket, "upload_params").entrySet()) {
            if (param.getValue() == null) continue;
            head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(quote(param.getKey())).append("\"\r\n\r\n")
                .append(param.getValue() instanceof String ? (String) param.getValue() : Json.write(param.getValue()))
                .append("\r\n");
        }
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"").append(quote(field == null ? "file" : field))
            .append("\"; filename=\"").append(quote(name)).append("\"\r\n")
            .append("Content-Type: ").append(type).append("\r\n\r\n");
        byte[] start = head.toString().getBytes("UTF-8");
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes("UTF-8");
        Http.Request request = Http.Request.to(uri).method("POST").timeout(900000)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Accept", "application/json");
        boolean canvasHost = equal(base.getHost(), uri.getHost()) && base.getPort() == uri.getPort();
        if (canvasHost) {
            String cookie = cookies.fetch();
            request.header("Cookie", cookie).header("X-CSRF-Token", csrf(cookie));
        }
        InputStream whole = new SequenceInputStream(new ByteArrayInputStream(start),
            new SequenceInputStream(body, new ByteArrayInputStream(end)));
        Http.Response sent = http.upload(request, whole, start.length + size + end.length, progress);
        int status = sent.status();
        if (status >= 200 && status < 300) {
            Object parsed = null;
            try {
                parsed = Json.parse(sent.body());
            } catch (RuntimeException e) {
                // A storage host that answers with no JSON names the
                // confirmation in its Location instead.
            }
            if (parsed instanceof Map && Json.object(parsed).get("id") instanceof Number) {
                return id(Json.object(parsed), "id");
            }
            if (sent.header("Location").length() == 0) throw new IOException("The file host did not confirm the file");
        } else if (status < 300 || status >= 400) {
            Http.requireSuccess(status, "File upload");
        }
        URI confirm;
        try {
            confirm = uri.resolve(sent.header("Location"));
        } catch (IllegalArgumentException e) {
            throw new IOException("Refused a Canvas address outside the configured API");
        }
        Map<String, Object> file = Json.object(Json.parse(send("GET", confirm, null, null).body()));
        return id(file, "id");
    }

    /** Starts (or resumes) a classic quiz attempt. This uses an attempt and starts any timer. */
    public Map<String, Object> startQuiz(String course, String quiz) throws IOException, InterruptedException {
        List<Object> started = Json.children(Json.object(write("POST", "courses/" + number(course) + "/quizzes/"
            + number(quiz) + "/submissions", "{}", JSON)), "quiz_submissions");
        if (started.isEmpty()) throw new IOException("Canvas did not start the quiz");
        return select(started.get(0), ATTEMPT_FIELDS);
    }

    /** Saves one answer; answers can be changed until the attempt is turned in. */
    public void answer(String attempt, long round, String token, String question, Object answer)
        throws IOException, InterruptedException {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("id", Long.valueOf(number(question)));
        entry.put("answer", answer);
        write("POST", "quiz_submissions/" + number(attempt) + "/questions", Json.write(Json.map(
            "attempt", Long.valueOf(round), "validation_token", token, "quiz_questions", Json.list(entry))), JSON);
    }

    public void finishQuiz(String course, String quiz, String attempt, long round, String token)
        throws IOException, InterruptedException {
        write("POST", "courses/" + number(course) + "/quizzes/" + number(quiz) + "/submissions/" + number(attempt)
            + "/complete", Json.write(Json.map("attempt", Long.valueOf(round), "validation_token", token)), JSON);
    }

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String JSON = "application/json";

    private Object write(String method, String path, String body, String type) throws IOException, InterruptedException {
        String text = send(method, base.resolve("api/v1/" + path), body, type).body();
        return text.trim().length() == 0 ? new LinkedHashMap<String, Object>() : Json.parse(text);
    }

    /** A string id, held to the same rule as one read out of a Canvas record. */
    static String number(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Not a Canvas id");
        }
        return value;
    }

    static String form(String... pairs) {
        StringBuilder out = new StringBuilder();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                if (out.length() > 0) out.append('&');
                out.append(URLEncoder.encode(pairs[i], "UTF-8")).append('=')
                    .append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /** A header-safe file or field name inside a multipart part. */
    private static String quote(String value) {
        return value.replace("\\", "_").replace("\"", "_").replace("\r", " ").replace("\n", " ");
    }

    /**
     * Canvas's CSRF token, which a change made with a session cookie must
     * carry back in a header. It is the URL-encoded _csrf_token cookie.
     */
    static String csrf(String cookie) throws IOException {
        String[] parts = cookie.split(";");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (!part.startsWith("_csrf_token=")) continue;
            try {
                return URLDecoder.decode(part.substring("_csrf_token=".length()), "UTF-8");
            } catch (IllegalArgumentException e) {
                break;
            }
        }
        throw new IOException("This Canvas session can read but not make changes");
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
        return send("GET", uri, null, null);
    }

    /*
     * A write is retried only after a 401, which Canvas sends before it has
     * done anything. A write whose connection fails is never sent again: it
     * may have arrived, and turning work in twice is a second attempt the
     * teacher sees.
     */
    private Http.Response send(String method, URI uri, String body, String type)
        throws IOException, InterruptedException {
        validatePage(base, uri);
        boolean reading = "GET".equals(method);
        long delay = 1000;
        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Http.Response response;
            try {
                String cookie = cookies.fetch();
                Http.Request request = Http.Request.to(uri)
                    .method(method)
                    .timeout(reading ? 60000 : 120000)
                    .header("Accept", "application/json")
                    .header("Cookie", cookie);
                if (!reading) {
                    request.header("X-CSRF-Token", csrf(cookie));
                    if (body != null) request.header("Content-Type", type).post(body);
                }
                response = http.send(request);
            } catch (IOException e) {
                if (attempt == 0 || !reading) throw e;
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

    static List<Object> selectAll(List<Object> items, String[] fields) {
        List<Object> out = new ArrayList<Object>();
        for (Object item : items) {
            if (item instanceof Map) out.add(select(item, fields));
        }
        return out;
    }

    private static final String[] SUBMISSION_FIELDS = { "score", "grade", "submitted_at", "graded_at",
        "workflow_state", "late", "missing", "excused", "cached_due_date", "points_deducted",
        "late_policy_status", "attempt", "assignment_id", "posted_at", "submission_type" };
    private static final String[] OWN_SUBMISSION_FIELDS = { "id", "user_id", "score", "grade", "submitted_at", "graded_at",
        "posted_at", "workflow_state", "submission_type", "body", "url", "attachments", "submission_comments",
        "rubric_assessment", "attempt", "late", "missing", "excused" };
    private static final String[] FILE_FIELDS = { "id", "display_name", "filename", "size", "content-type",
        "url", "updated_at", "created_at", "locked_for_user", "mime_class" };
    private static final String[] COMMENT_FIELDS = { "id", "author_id", "author_name", "comment", "created_at" };
    private static final String[] MODULE_FIELDS = { "id", "name", "position", "items", "items_count", "state",
        "unlock_at" };
    private static final String[] ITEM_FIELDS = { "id", "module_id", "title", "type", "content_id", "page_url",
        "external_url", "indent", "position", "completion_requirement", "content_details", "published" };
    private static final String[] PERSON_FIELDS = { "id", "name", "short_name", "sortable_name", "enrollments" };
    private static final String[] ACTIVITY_FIELDS = { "id", "type", "title", "message", "created_at",
        "updated_at", "course_id", "assignment_id", "discussion_topic_id", "announcement_id", "score", "grade",
        "assignment", "submission_comments", "notification_category", "read_state", "workflow_state",
        "user_name", "author_name", "latest_messages", "html_url" };
    private static final String[] OVERRIDE_FIELDS = { "id", "plannable_type", "plannable_id", "marked_complete" };
    private static final String[] QUIZ_FIELDS = { "id", "title", "description", "quiz_type", "time_limit",
        "allowed_attempts", "question_count", "points_possible", "due_at", "lock_at", "unlock_at",
        "locked_for_user", "lock_explanation", "one_question_at_a_time", "cant_go_back", "access_code",
        "has_access_code", "assignment_id", "published" };
    private static final String[] ATTEMPT_FIELDS = { "id", "quiz_id", "attempt", "validation_token",
        "workflow_state", "started_at", "end_at", "finished_at", "score", "kept_score", "attempts_left",
        "overdue_and_needs_submission" };
    private static final String[] QUESTION_FIELDS = { "id", "position", "question_name", "question_type",
        "question_text", "answers", "answer", "flagged", "matches" };
    private static final String[] COURSE_FIELDS = { "id", "name", "course_code", "workflow_state", "term",
        "teachers", "course_color",
        "time_zone", "enrollments", "apply_assignment_group_weights", "grading_standard_id",
        "hide_final_grades", "syllabus_body", "public_description", "start_at", "end_at", "grading_periods" };
    private static final String[] ASSIGNMENT_FIELDS = { "id", "course_id", "name", "due_at", "unlock_at",
        "lock_at", "points_possible", "grading_type", "assignment_group_id", "html_url", "submission",
        "has_submitted_submissions", "published", "locked_for_user", "omit_from_final_grade",
        "in_closed_grading_period", "is_quiz_assignment", "submission_types", "quiz_id", "discussion_topic",
        "allowed_attempts", "position" };
    private static final String[] POST_FIELDS = { "id", "title", "message", "html_url", "posted_at",
        "last_reply_at", "user_name", "is_announcement", "assignment_id", "discussion_subentry_count",
        "published", "pinned", "locked", "locked_for_user", "lock_explanation", "require_initial_post",
        "user_can_see_posts" };
    private static final String[] ENTRY_FIELDS = { "id", "parent_id", "user_id", "user_name", "message",
        "created_at", "updated_at", "deleted", "has_more_replies", "recent_replies" };
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
