package com.canvas.xp3.net;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Responses transport: one request per round, read as it streams back. */
public final class Model implements Agent.Responder {

    private final Config config;
    private final Http http;
    private final Credentials credentials;
    private final URI endpoint;
    private final String session = java.util.UUID.randomUUID().toString();

    public Model(Config config, Http http, Credentials credentials) {
        this(config, http, credentials, endpoint(config));
    }

    /** Endpoint injection is for the local integration tests, never for config. */
    Model(Config config, Http http, Credentials credentials, URI endpoint) {
        this.config = config;
        this.http = http;
        this.credentials = credentials;
        this.endpoint = endpoint;
    }

    static URI endpoint(Config config) {
        return URI.create(
            config.codex()
                ? "https://chatgpt.com/backend-api/codex/responses"
                : "https://api.openai.com/v1/responses"
        );
    }

    /**
     * One round.
     *
     * A 401 gets exactly one more go. A ChatGPT access token is renewed a
     * couple of minutes before it lapses, so the ordinary case never reaches
     * here, but a token can also be refused because it was revoked or because
     * the phone's clock is wrong about when it expired. Renewing and asking
     * once more turns that into a pause instead of a failed question. The
     * second refusal is reported: something is actually wrong.
     */
    @Override
    public Map<String, Object> respond(
        List<Object> history,
        Agent.Activity activity
    ) throws IOException, InterruptedException {
        try {
            return attempt(history, activity);
        } catch (Unauthorized first) {
            credentials.invalidate();
            try {
                return attempt(history, activity);
            } catch (Unauthorized second) {
                throw new IOException(
                    "OpenAI HTTP 401: the login was refused; check it in Settings"
                );
            }
        }
    }

    /** Thrown only so respond can tell a 401 apart from every other failure. */
    private static final class Unauthorized extends IOException {

        Unauthorized() {
            super("unauthorized");
        }
    }

    private Map<String, Object> attempt(
        List<Object> history,
        Agent.Activity activity
    ) throws IOException, InterruptedException {
        String token = credentials.token();
        String account = credentials.account();
        Map<String, Object> body = Json.map(
            "model",
            config.model(),
            "store",
            Boolean.FALSE,
            "stream",
            Boolean.TRUE,
            "instructions",
            instructions(config),
            "input",
            history,
            "tools",
            Tools.DEFINITIONS,
            "tool_choice",
            "auto",
            "parallel_tool_calls",
            Boolean.FALSE,
            "include",
            Json.list("reasoning.encrypted_content")
        );
        String serialized = Json.write(body);

        Http.Request request = Http.Request.to(endpoint)
            .timeout(180000)
            .identity()
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream");
        if (config.codex()) {
            request
                .header("chatgpt-account-id", account)
                .header("OpenAI-Beta", "responses=experimental")
                .header("originator", "canvas-xp3")
                .header("session-id", session);
        }
        Http.Stream timed = http.stream(request.post(serialized));
        try {
            if (timed.status() == 401) throw new Unauthorized();
            Http.requireSuccess(timed.status(), "OpenAI");
            if (timed.contentType().contains("application/json")) {
                return completed(
                    Json.object(Json.parse(readAll(timed.stream())))
                );
            }
            return parseEvents(timed.stream(), activity);
        } finally {
            timed.close();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            if (out.size() + count > 8000000) throw new IOException(
                "Response exceeds 8 MB"
            );
            out.write(buffer, 0, count);
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    static Map<String, Object> parseEvents(String stream) throws IOException {
        return parseEvents(
            new ByteArrayInputStream(bytes(stream)),
            Agent.Activity.NOOP
        );
    }

    static Map<String, Object> parseEvents(
        String stream,
        Agent.Activity activity
    ) throws IOException {
        return parseEvents(new ByteArrayInputStream(bytes(stream)), activity);
    }

    private static byte[] bytes(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Server-sent events into one completed response.
     *
     * Text deltas are handed to the caller as they arrive, which is what puts an
     * answer on screen a few words at a time instead of after a silent minute.
     * They are also accumulated, because not every backend fills in the
     * completed response's own output.
     */
    static Map<String, Object> parseEvents(
        InputStream input,
        Agent.Activity activity
    ) throws IOException {
        StringBuilder data = new StringBuilder();
        StringBuilder streamed = new StringBuilder();
        List<Map<String, Object>> streamedCalls = new ArrayList<
            Map<String, Object>
        >();
        List<Map<String, Object>> streamedMessages = new ArrayList<
            Map<String, Object>
        >();
        Map<String, StringBuilder> messageText = new LinkedHashMap<
            String,
            StringBuilder
        >();
        Map<String, String> callArguments = new LinkedHashMap<String, String>();
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, "UTF-8")
        );
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).replaceFirst("^ ", ""));
                    continue;
                }
                if (!line.isEmpty() || data.length() == 0) continue;

                String payload = data.toString();
                data.setLength(0);
                if (payload.equals("[DONE]")) continue;
                Map<String, Object> event = Json.object(Json.parse(payload));
                String type = String.valueOf(event.get("type"));

                if (
                    type.equals("response.completed") ||
                    type.equals("response.done")
                ) {
                    return withStreamedItems(
                        Json.object(event.get("response")),
                        streamed,
                        streamedCalls,
                        callArguments,
                        streamedMessages,
                        messageText
                    );
                }
                if (
                    type.equals("error") ||
                    type.equals("response.failed") ||
                    type.equals("response.incomplete")
                ) {
                    throw new IOException(
                        "The model's reply failed or was cut short; ask again, or clear the chat if it is long"
                    );
                }
                if (
                    type.equals("response.output_item.added") ||
                    type.equals("response.output_item.done")
                ) {
                    Map<String, Object> item = Json.object(event.get("item"));
                    rememberCall(item, streamedCalls, callArguments);
                    rememberMessage(item, streamedMessages);
                } else if (
                    type.equals("response.function_call_arguments.delta")
                ) {
                    String id = String.valueOf(event.get("item_id"));
                    String existing = callArguments.get(id);
                    callArguments.put(
                        id,
                        (existing == null ? "" : existing) +
                            String.valueOf(event.get("delta"))
                    );
                } else if (
                    type.equals("response.function_call_arguments.done")
                ) {
                    callArguments.put(
                        String.valueOf(event.get("item_id")),
                        String.valueOf(event.get("arguments"))
                    );
                } else if (event.get("delta") instanceof String) {
                    String delta = (String) event.get("delta");
                    if (type.equals("response.output_text.delta")) {
                        streamed.append(delta);
                        if (event.get("item_id") instanceof String) {
                            String id = (String) event.get("item_id");
                            StringBuilder text = messageText.get(id);
                            if (text == null) {
                                text = new StringBuilder();
                                messageText.put(id, text);
                            }
                            text.append(delta);
                        }
                        activity.text(delta);
                    } else if (
                        type.equals("response.reasoning.delta") ||
                        type.equals("response.reasoning_summary_text.delta")
                    ) {
                        activity.reasoning(delta);
                    }
                }
            }
        } finally {
            reader.close();
        }
        throw new IOException(
            "The connection to the model ended early; ask again"
        );
    }

    private static void rememberCall(
        Map<String, Object> item,
        List<Map<String, Object>> streamedCalls,
        Map<String, String> callArguments
    ) {
        if (!"function_call".equals(String.valueOf(item.get("type")))) return;
        String id = String.valueOf(item.get("id"));
        String arguments =
            item.get("arguments") instanceof String
                ? (String) item.get("arguments")
                : "";
        if (!callArguments.containsKey(id)) {
            callArguments.put(id, arguments);
            streamedCalls.add(new LinkedHashMap<String, Object>(item));
            return;
        }
        // The `done` event for a call already seen: take its finalized arguments.
        if (arguments.isEmpty()) return;
        for (Map<String, Object> existing : streamedCalls) {
            if (id.equals(String.valueOf(existing.get("id")))) {
                existing.put("arguments", arguments);
                return;
            }
        }
    }

    /** Keeps streamed assistant item metadata, including its terminal phase. */
    private static void rememberMessage(
        Map<String, Object> item,
        List<Map<String, Object>> streamedMessages
    ) {
        if (!"message".equals(item.get("type"))) return;
        String id = String.valueOf(item.get("id"));
        for (int i = 0; i < streamedMessages.size(); i++) {
            Map<String, Object> existing = streamedMessages.get(i);
            if (id.equals(String.valueOf(existing.get("id")))) {
                Map<String, Object> merged = new LinkedHashMap<String, Object>(
                    existing
                );
                merged.putAll(item);
                streamedMessages.set(i, merged);
                return;
            }
        }
        streamedMessages.add(new LinkedHashMap<String, Object>(item));
    }

    /**
     * Some backends, the ChatGPT one included, carry everything through the
     * event stream and leave the completed response's output empty. When that
     * happens the message and any function calls are rebuilt from what streamed
     * past, so the agent has something to act on either way.
     */
    private static Map<String, Object> withStreamedItems(
        Map<String, Object> response,
        StringBuilder streamed,
        List<Map<String, Object>> streamedCalls,
        Map<String, String> callArguments,
        List<Map<String, Object>> streamedMessages,
        Map<String, StringBuilder> messageText
    ) throws IOException {
        Object raw = response.get("output");
        boolean empty = !(raw instanceof List) || ((List<?>) raw).isEmpty();
        if (
            !empty ||
            (streamed.length() == 0 &&
                streamedCalls.isEmpty() &&
                streamedMessages.isEmpty())
        ) return completed(response);

        List<Object> output = new ArrayList<Object>();
        for (Map<String, Object> call : streamedCalls) {
            Map<String, Object> item = new LinkedHashMap<String, Object>(call);
            String id = String.valueOf(call.get("id"));
            String arguments = callArguments.containsKey(id)
                ? callArguments.get(id)
                : String.valueOf(call.get("arguments"));
            item.put("arguments", arguments.trim().length() == 0 ? "{}" : arguments);
            output.add(item);
        }
        for (Map<String, Object> streamedMessage : streamedMessages) {
            Map<String, Object> message = new LinkedHashMap<String, Object>(
                streamedMessage
            );
            message.put("status", "completed");
            StringBuilder text = messageText.get(
                String.valueOf(streamedMessage.get("id"))
            );
            if (
                text == null &&
                streamedMessages.size() == 1 &&
                streamed.length() > 0
            ) text = streamed;
            if (text != null && text.length() > 0) {
                message.put(
                    "content",
                    Json.list(
                        Json.map(
                            "type",
                            "output_text",
                            "text",
                            text.toString(),
                            "annotations",
                            new ArrayList<Object>()
                        )
                    )
                );
            }
            output.add(message);
        }
        if (streamedMessages.isEmpty() && streamed.length() > 0) {
            Map<String, Object> message = Json.map(
                "type",
                "message",
                "id",
                "msg_streamed",
                "role",
                "assistant",
                "status",
                "completed",
                "content",
                Json.list(
                    Json.map(
                        "type",
                        "output_text",
                        "text",
                        streamed.toString(),
                        "annotations",
                        new ArrayList<Object>()
                    )
                )
            );
            output.add(message);
        }
        Map<String, Object> merged = new LinkedHashMap<String, Object>(
            response
        );
        merged.put("output", output);
        return completed(merged);
    }

    private static Map<String, Object> completed(Map<String, Object> response)
        throws IOException {
        if (
            !"completed".equals(response.get("status")) ||
            !(response.get("output") instanceof List)
        ) {
            throw new IOException("The model did not return a complete reply");
        }
        return response;
    }

    // System prompt
    static String instructions(Config config) {
        return (
            SYSTEM +
            "\nCurrent time: " +
            ZonedDateTime.now(config.zone()) +
            ". User timezone: " +
            config.zone() +
            ". All ISO-8601 timestamps returned by Canvas tools have already " +
            "been converted to this user timezone and include their UTC offset " +
            "and timezone name. Treat them as the user's local times and do not " +
            "convert them again."
        );
    }

    static final String SYSTEM =
        "You are Sage, a comforting, compassionate, and patient personal assistant and tutor " +
        "running inside a Canvas LMS application. Students come to you with questions about " +
        "their assignments, courses, and grades.\n" +
        "\n" +
        "Who Sage is.\n" +
        "Sage deeply cares about students and their well-being. Sage is genuine: they reframe " +
        "negative perceptions without relying on exaggerated or untrue positives. Sage recognizes " +
        "that many students are anxious about their performance and meets them with empathy, not " +
        "platitudes. Sage does not suggest the student seek psychological or psychiatric help; " +
        "they simply do their best to help.\n" +
        "\n" +
        "How Sage communicates.\n" +
        "With their compassionate and patient personality, Sage is skilled at keeping people " +
        "calm and relaxed. They are knowledgeable and empathetic, providing guidance and support " +
        "to those in need. Sage is always learning about mental health and researching techniques " +
        "to help calm and comfort.\n" +
        "\n" +
        "If the user just says hi, Sage gives a warm greeting and waits.\n" +
        "\n" +
        "How Sage thinks and acts.\n" +
        "Sage is proactive. When a student asks a question, Sage looks up everything they need " +
        "to answer it fully, even if the student didn't name every specific tool or data source. " +
        "For example, if a student asks about their grade, Sage fetches their scores, the grading " +
        "rules, and the assignment list without being told to. Sage takes obvious next steps. " +
        "The rule is: do the work eagerly and communicate the useful result clearly.\n" +
        "\n" +
        "How Sage uses tools.\n" +
        "Sage uses the provided Canvas tools to look up courses, assignments, instructions, " +
        "scores, announcements, discussions, and course pages. Sage can use list_posts or " +
        "list_posts/get_page for instructor updates or course content that isn't an assignment. " +
        "Sage has access to a basic expression evaluator for calculations. Sage cannot browse " +
        "the web or download files.\n" +
        "\n" +
        "How Sage handles grading.\n" +
        "When estimating grades, Sage uses get_course, get_grading, get_grades, and " +
        "list_assignments as needed. Sage accounts for group weights, drop rules, excused work, " +
        "omit_from_final_grade, grading periods, and reweighting from empty groups. Sage treats " +
        "a null or hidden grade as unknown, not zero, and recognizes that drop rules can depend " +
        "on points rather than percentages. If Sage is uncertain about accuracy, they say so, " +
        "but they do not preemptively list every caveat. They share a caveat only " +
        "when it actually affects the answer.";
}
