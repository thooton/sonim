package com.canvas.xp3.net;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete set of actions available to the assistant.
 *
 * Canvas tools are read-only; no tool writes Canvas data, accepts a URL or
 * file path, or evaluates arbitrary code.
 */
public final class Tools {

    private final Canvas canvas;
    private final ZoneId zone;
    private final Clock clock;

    public Tools(Canvas canvas) {
        this(canvas, ZoneId.systemDefault(), Clock.systemUTC());
    }

    public Tools(Canvas canvas, ZoneId zone) {
        this(canvas, zone, Clock.systemUTC());
    }

    Tools(Canvas canvas, ZoneId zone, Clock clock) {
        this.canvas = canvas;
        if (zone == null) throw new IllegalArgumentException("A time zone is required");
        if (clock == null) throw new IllegalArgumentException("A clock is required");
        this.zone = zone;
        this.clock = clock;
    }

    public static final List<Object> DEFINITIONS = definitions();

    private static List<Object> definitions() {
        List<Object> all = new ArrayList<Object>();

        all.add(
            tool(
                "show_my_courses",
                "Show the student's courses, including current grade totals and academic term. Set active_only=false to also include past enrollments.",
                "active_only",
                "boolean"
            )
        );

        all.add(
            tool(
                "open_course_details",
                "Show helpful details about a course, including its syllabus, timezone, grading method, and grading standard ID.",
                "course_id",
                "integer"
            )
        );

        all.add(
            tool(
                "show_course_assignments",
                "Show every visible assignment in a course, including points, due dates, group IDs, and the student's submission and grade information. Undated and past assignments are included too.",
                "course_id",
                "integer"
            )
        );

        all.add(
            tool(
                "open_assignment_details",
                "Show the complete assignment instructions, rubric, points, availability, and the student's submission status and grade.",
                "course_id",
                "integer",
                "assignment_id",
                "integer"
            )
        );

        all.add(
            tool(
                "show_grading_rules",
                "Show assignment-group percentage weights and drop or never-drop rules. This is especially helpful alongside course details, assignments, and grades.",
                "course_id",
                "integer"
            )
        );

        all.add(
            tool(
                "show_my_grades",
                "Show the student's enrollment information and Canvas-calculated current and final grades. Hidden or missing scores are reported as unknown rather than zero.",
                "course_id",
                "integer"
            )
        );

        all.add(
            tool(
                "show_grading_scale",
                "Show the letter-grade thresholds for a course using its grading standard ID. This information may not be available to students.",
                "course_id",
                "integer",
                "standard_id",
                "integer"
            )
        );

        all.add(
            tool(
                "show_course_posts",
                "Show course announcements when announcements=true, or discussion topics when announcements=false. Messages are included, along with non-assignment posts, with the newest activity first.",
                "course_id",
                "integer",
                "announcements",
                "boolean"
            )
        );

        all.add(
            tool(
                "open_course_post",
                "Show one announcement or discussion topic using its topic ID from show_course_posts. Opening it does not mark it as read.",
                "course_id",
                "integer",
                "topic_id",
                "integer"
            )
        );

        all.add(
            tool(
                "show_post_comments",
                "Show all visible top-level comments on an announcement or discussion. Nested replies are separate and can be viewed with show_comment_replies. Initial-post visibility rules are respected.",
                "course_id",
                "integer",
                "topic_id",
                "integer"
            )
        );

        all.add(
            tool(
                "show_comment_replies",
                "Show visible replies to a discussion comment. Use an entry ID from show_post_comments. This tool never posts a reply.",
                "course_id",
                "integer",
                "topic_id",
                "integer",
                "entry_id",
                "integer"
            )
        );

        all.add(
            tool(
                "show_course_pages",
                "Show the available course pages, such as readings, policies, schedules, and other non-assignment content. Use a page ID with open_course_page to read a page.",
                "course_id",
                "integer"
            )
        );

        all.add(
            tool(
                "open_course_page",
                "Show the HTML content of a course page using its numeric page ID from show_course_pages. Linked files and external websites are not fetched.",
                "course_id",
                "integer",
                "page_id",
                "integer"
            )
        );

        all.add(
            tool(
                "show_upcoming_assignments",
                "Show dated assignments across all active student courses, sorted by each student's effective due date. The from time is inclusive and the to time is exclusive. Both must be ISO-8601 timestamps with an offset, such as 2026-09-01T00:00:00-07:00. Set include_completed=false to leave out submitted, graded, pending-review, and excused work; set it to true to include those items. Course failures are clearly reported.",
                "from",
                "string",
                "to",
                "string",
                "include_completed",
                "boolean"
            )
        );

        all.add(
            tool(
                "calculate_decimal",
                "Safely evaluate decimal arithmetic locally using +, -, *, /, ^, unary signs, and parentheses. Exponents must be integers from -100 through 100. Variables, code, functions, and the percent operator are not supported; use 85/100 for 85%. Results use DECIMAL128 precision.",
                "expression",
                "string"
            )
        );

        all.add(
            tool(
                "get_current_time",
                "Get the current date and time in the user's local time zone. The result includes the UTC offset and IANA time zone name."
            )
        );

        return all;
    }

    private static Object tool(
        String name,
        String description,
        String... args
    ) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();

        for (int i = 0; i < args.length; i += 2) {
            Map<String, Object> field = Json.map("type", args[i + 1]);

            if (args[i + 1].equals("integer")) {
                field.put("minimum", BigDecimal.ONE);
            }

            properties.put(args[i], field);
        }

        return Json.map(
            "type",
            "function",
            "name",
            name,
            "description",
            description,
            "strict",
            Boolean.TRUE,
            "parameters",
            Json.map(
                "type",
                "object",
                "properties",
                properties,
                "required",
                new ArrayList<String>(properties.keySet()),
                "additionalProperties",
                Boolean.FALSE
            )
        );
    }

    /**
     * Runs one tool call and returns its result as JSON.
     *
     * Expected problems are returned as friendly JSON errors so the assistant
     * can understand what happened and continue helping. Interruption is
     * different: it means the application is shutting down, so it is allowed
     * to propagate.
     */
    public String call(String name, String arguments)
        throws InterruptedException {
        try {
            if (arguments == null) {
                throw new IllegalArgumentException(
                    "The tool arguments are missing. Please provide them and try again."
                );
            }

            if (arguments.length() > 16000) {
                throw new IllegalArgumentException(
                    "Those tool arguments are a little too large. " +
                        "Please try a shorter request."
                );
            }

            Map<String, Object> args = Json.object(Json.parse(arguments));

            validate(name, args);

            Object result;

            if (name.equals("show_my_courses")) {
                result = canvas.courses(bool(args, "active_only"));
            } else if (name.equals("open_course_details")) {
                result = canvas.course(id(args, "course_id"));
            } else if (name.equals("show_course_assignments")) {
                result = canvas.assignments(id(args, "course_id"));
            } else if (name.equals("open_assignment_details")) {
                result = canvas.assignment(
                    id(args, "course_id"),
                    id(args, "assignment_id")
                );
            } else if (name.equals("show_grading_rules")) {
                result = canvas.grading(id(args, "course_id"));
            } else if (name.equals("show_my_grades")) {
                result = canvas.enrollment(id(args, "course_id"));
            } else if (name.equals("show_grading_scale")) {
                result = canvas.gradingStandard(
                    id(args, "course_id"),
                    id(args, "standard_id")
                );
            } else if (name.equals("show_course_posts")) {
                result = canvas.posts(
                    id(args, "course_id"),
                    bool(args, "announcements")
                );
            } else if (name.equals("open_course_post")) {
                result = canvas.post(
                    id(args, "course_id"),
                    id(args, "topic_id")
                );
            } else if (name.equals("show_post_comments")) {
                result = canvas.entries(
                    id(args, "course_id"),
                    id(args, "topic_id")
                );
            } else if (name.equals("show_comment_replies")) {
                result = canvas.replies(
                    id(args, "course_id"),
                    id(args, "topic_id"),
                    id(args, "entry_id")
                );
            } else if (name.equals("show_course_pages")) {
                result = canvas.pages(id(args, "course_id"));
            } else if (name.equals("open_course_page")) {
                result = canvas.page(
                    id(args, "course_id"),
                    id(args, "page_id")
                );
            } else if (name.equals("show_upcoming_assignments")) {
                result = canvas.due(
                    Instant.parse(Json.string(args, "from")),
                    Instant.parse(Json.string(args, "to")),
                    bool(args, "include_completed")
                );
            } else if (name.equals("calculate_decimal")) {
                result = Json.map(
                    "result",
                    Calculator.evaluate(Json.string(args, "expression")),
                    "precision",
                    "34 significant digits"
                );
            } else if (name.equals("get_current_time")) {
                ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), zone);
                result = Json.map(
                    "current_time",
                    now.toString(),
                    "timezone",
                    zone.getId(),
                    "utc_offset",
                    now.getOffset().toString()
                );
            } else {
                throw new IllegalArgumentException(
                    "I'm sorry, but I don't recognize that tool."
                );
            }

            // Canvas expresses instants in UTC. Keep them that way while the
            // application filters and sorts, then translate the copy handed
            // to the model so a due date reads the way it does to the student.
            String output = Json.write(localize(result, zone));

            if (output.length() > 250000) {
                return Json.write(
                    Json.map(
                        "error",
                        "There is more information than can fit in one " +
                            "response. Please narrow this to one course or " +
                            "assignment, or use a shorter date range. Nothing " +
                            "was returned yet."
                    )
                );
            }

            return output;
        } catch (InterruptedException e) {
            throw e;
        } catch (IOException e) {
            String message = e.getMessage();

            return Json.write(
                Json.map(
                    "error",
                    message == null
                        ? "I couldn't complete that request because the " +
                              "course service did not respond as expected."
                        : "I couldn't complete that request: " + message
                )
            );
        } catch (IllegalArgumentException e) {
            return Json.write(Json.map("error", e.getMessage()));
        } catch (RuntimeException e) {
            return Json.write(
                Json.map(
                    "error",
                    "I couldn't complete that request. Please check the " +
                        "tool fields, IDs, timestamps, and arithmetic " +
                        "expression, then try again."
                )
            );
        }
    }

    /**
     * A deep copy in which ISO-8601 instants use the student's local zone.
     *
     * Trying the value rather than maintaining a list of field names also
     * covers timestamps nested in Canvas structures such as grading periods,
     * rubrics and lock information. Ordinary prose and identifiers are left
     * untouched because they are not complete ISO-8601 instants.
     */
    static Object localize(Object value, ZoneId zone) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(String.valueOf(entry.getKey()), localize(entry.getValue(), zone));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (List<?>) value) copy.add(localize(item, zone));
            return copy;
        }
        if (value instanceof String) {
            try {
                return Instant.parse((String) value).atZone(zone).toString();
            } catch (RuntimeException ignored) {
                // Most strings in a Canvas response are not timestamps.
            }
        }
        return value;
    }

    /**
     * Checks that the requested tool exists and that its arguments match the
     * published schema exactly, including the set of field names.
     */
    private static void validate(String name, Map<String, Object> args) {
        Map<String, Object> definition = null;

        for (Object candidate : DEFINITIONS) {
            Map<String, Object> entry = Json.object(candidate);

            if (name.equals(entry.get("name"))) {
                definition = entry;
                break;
            }
        }

        if (definition == null) {
            throw new IllegalArgumentException(
                "I'm sorry, but I don't recognize that tool."
            );
        }

        Map<String, Object> fields = Json.child(
            Json.child(definition, "parameters"),
            "properties"
        );

        if (!args.keySet().equals(fields.keySet())) {
            throw new IllegalArgumentException(
                "The request fields do not quite match what this tool " +
                    "needs. Please check the tool's required fields."
            );
        }

        for (Map.Entry<String, Object> field : fields.entrySet()) {
            String type = (String) Json.object(field.getValue()).get("type");

            if (type.equals("integer")) {
                id(args, field.getKey());
            } else if (type.equals("string")) {
                Json.string(args, field.getKey());
            } else if (!(args.get(field.getKey()) instanceof Boolean)) {
                throw new IllegalArgumentException(
                    "The '" +
                        field.getKey() +
                        "' field needs to be either true or false."
                );
            }
        }
    }

    private static boolean bool(Map<String, Object> args, String key) {
        Object value = args.get(key);

        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(
                "The '" + key + "' field needs to be either true or false."
            );
        }

        return ((Boolean) value).booleanValue();
    }

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
