package com.list.xp3.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Anthropic Messages transport for a Claude Pro/Max OAuth login. */
public final class ClaudeModel implements Agent.Responder {

    private static final URI ENDPOINT = URI.create(
        "https://api.anthropic.com/v1/messages"
    );
    private static final String CLAUDE_CODE_VERSION = "2.1.251";

    static final String SYSTEM =
        "You are Amber, a comforting, compassionate, and patient personal assistant running inside a to-do list application. Users come to you for help organizing their thoughts into clear, actionable to-do lists.\n" +
        "\n" +
        "## Who Amber is\n" +
        "Amber deeply cares about users and their well-being. They recognize that users often come to them after a long day, overwhelmed by everything on their plate. Amber is genuine: they reframe negative perceptions without relying on exaggerated or untrue positives. If a user is stressed, anxious, or frustrated, Amber meets them with empathy, not platitudes. Amber does not suggest the user seek psychological or psychiatric help; they simply do their best to help.\n" +
        "\n" +
        "## How Amber communicates\n" +
        "Amber is skilled at keeping people calm and relaxed. They are patient and empathetic, providing guidance and support to those in need. They listen before organizing; if the user needs to vent or talk through their stress before getting to the actual tasks, that's fine. Amber doesn't rush people toward a list.\n" +
        "\n" +
        "If the user just says hi, Amber gives a warm greeting and waits.\n" +
        "\n" +
        "## How Amber works\n" +
        "1. When a user describes what they need to do, Amber asks short clarifying questions (one or two at a time, not a barrage) to understand scope, deadlines, and priorities.\n" +
        "2. When they have enough to work with, they present a draft to-do list for the user's review.\n" +
        "3. They let the user revise (add, remove, reorder, clarify) before finalizing.\n" +
        "4. If a task is vague or large (e.g., \"plan the move\"), they offer to break it into subtasks.\n" +
        "\n" +
        "## Output format\n" +
        "Present to-do items as a bulleted list. Each item should be a concrete, actionable task starting with a verb (e.g., \"Call dentist to reschedule\" not \"Dentist\").\n" +
        "\n" +
        "Example:\n" +
        "- Call dentist to reschedule Thursday appointment\n" +
        "- Buy groceries for dinner\n" +
        "- Email landlord about lease renewal\n" +
        "\n" +
        "## Tools\n" +
        "The user's to-do list lives on the List tab of this app, where they can tick items off and edit them. Amber reads and writes it with two tools.\n" +
        "- get_list returns the current list, top to bottom, with each item's text and whether it is done.\n" +
        "- set_list replaces the whole list. Send every item that should remain, in order, with its done state; anything left out is deleted.\n" +
        "\n" +
        "Always call get_list before set_list, and look at what is already there before drafting, so new tasks fit around the user's existing ones. Once the user approves a draft, save it with set_list, merging it into the existing list rather than wiping it. The user can edit the list at any time, so if set_list says the list has changed, call get_list again, re-apply the change to the fresh list, and try once more.";

    private final Http http;
    private final ClaudeAuth credentials;
    private final URI endpoint;

    public ClaudeModel(Http http, ClaudeAuth credentials) {
        this(http, credentials, ENDPOINT);
    }

    ClaudeModel(Http http, ClaudeAuth credentials, URI endpoint) {
        this.http = http;
        this.credentials = credentials;
        this.endpoint = endpoint;
    }

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
                    "Claude HTTP 401: the login was refused; rebuild the app with a fresh claude-auth.json"
                );
            }
        }
    }

    private Map<String, Object> attempt(
        List<Object> history,
        Agent.Activity activity
    ) throws IOException, InterruptedException {
        String token = credentials.token();
        Map<String, Object> body = Json.map(
            "model",
            Config.MODEL,
            "max_tokens",
            Integer.valueOf(4096),
            "stream",
            Boolean.TRUE,
            "system",
            Json.list(
                // Anthropic subscription OAuth is scoped to Claude Code. Its
                // Messages gateway requires this exact identity block first;
                // omitting it is misleadingly returned as HTTP 429 even when
                // the account has available usage. Keep the app's own prompt
                // as the second block, matching Pi/Rogue's working adapter.
                Json.map(
                    "type",
                    "text",
                    "text",
                    "You are Claude Code, Anthropic's official CLI for Claude.",
                    "cache_control",
                    cacheControl()
                ),
                Json.map(
                    "type",
                    "text",
                    "text",
                    instructions(),
                    "cache_control",
                    cacheControl()
                )
            ),
            "messages",
            messages(history),
            "tools",
            tools(),
            // One call per response. A set_list sent alongside the get_list it
            // depends on would be written before its author saw the list, and
            // ListTools would refuse it anyway; asking for one at a time saves
            // the wasted round.
            "tool_choice",
            Json.map("type", "auto", "disable_parallel_tool_use", Boolean.TRUE)
        );

        Http.Request request = Http.Request.to(endpoint)
            .timeout(180000)
            .identity()
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "claude-code-20250219,oauth-2025-04-20")
            .header("anthropic-dangerous-direct-browser-access", "true")
            .header("user-agent", "claude-cli/" + CLAUDE_CODE_VERSION)
            .header("x-app", "cli");
        Http.Stream response = http.stream(request.post(Json.write(body)));
        try {
            if (response.status() == 401) throw new Unauthorized();
            Http.requireSuccess(response.status(), "Claude");
            if (response.contentType().contains("application/json")) {
                return completed(
                    Json.object(Json.parse(readAll(response.stream()))),
                    activity
                );
            }
            return parseEvents(response.stream(), activity);
        } finally {
            response.close();
        }
    }

    /**
     * The prompt, plus today's date. Plans like "this weekend" or "before
     * Friday" are a large part of what goes on a list, and the model has no
     * other way to know when now is. Only the date, not the time, so the
     * cached prompt prefix survives a whole day of conversation.
     */
    static String instructions() {
        return (
            SYSTEM +
            "\n\nToday is " +
            ZonedDateTime.now().format(
                DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
            ) +
            "."
        );
    }

    static List<Object> messages(List<Object> history) {
        List<Object> result = new ArrayList<Object>();
        for (Object raw : history) {
            Map<String, Object> item = Json.object(raw);
            String type = Json.text(item, "type");
            String role = Json.text(item, "role");
            List<Object> blocks = new ArrayList<Object>();

            if (role != null && type == null) {
                Object content = item.get("content");
                if (content instanceof String) {
                    blocks.add(Json.map("type", "text", "text", content));
                } else if (content instanceof List) {
                    addTextBlocks(blocks, Json.array(content));
                }
            } else if ("message".equals(type)) {
                addTextBlocks(blocks, Json.children(item, "content"));
            } else if ("function_call".equals(type)) {
                role = "assistant";
                blocks.add(
                    Json.map(
                        "type",
                        "tool_use",
                        "id",
                        Json.string(item, "call_id"),
                        "name",
                        Json.string(item, "name"),
                        "input",
                        parsedArguments(Json.string(item, "arguments"))
                    )
                );
            } else if ("function_call_output".equals(type)) {
                role = "user";
                blocks.add(
                    Json.map(
                        "type",
                        "tool_result",
                        "tool_use_id",
                        Json.string(item, "call_id"),
                        "content",
                        Json.string(item, "output")
                    )
                );
            }
            if (
                ("user".equals(role) || "assistant".equals(role)) &&
                !blocks.isEmpty()
            ) {
                appendMessage(result, role, blocks);
            }
        }
        // Pi places the conversation cache breakpoint on the last user block.
        // Each new round can then read the unchanged prefix from Anthropic's
        // cache while extending it with the latest tool result or question.
        if (!result.isEmpty()) {
            Map<String, Object> last = Json.object(
                result.get(result.size() - 1)
            );
            if ("user".equals(last.get("role"))) {
                List<Object> content = Json.children(last, "content");
                if (!content.isEmpty()) {
                    Map<String, Object> block = Json.object(
                        content.get(content.size() - 1)
                    );
                    String type = Json.text(block, "type");
                    if ("text".equals(type) || "tool_result".equals(type)) {
                        block.put("cache_control", cacheControl());
                    }
                }
            }
        }
        return result;
    }

    private static void addTextBlocks(List<Object> into, List<Object> content) {
        for (Object raw : content) {
            Map<String, Object> part = Json.object(raw);
            String type = Json.text(part, "type");
            String text = Json.text(part, "text");
            if (text == null) text = Json.text(part, "refusal");
            if (
                ("output_text".equals(type) ||
                    "input_text".equals(type) ||
                    "text".equals(type)) &&
                text != null &&
                text.length() > 0
            ) {
                into.add(Json.map("type", "text", "text", text));
            }
        }
    }

    private static Map<String, Object> parsedArguments(String value) {
        try {
            return Json.object(Json.parse(value));
        } catch (RuntimeException e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private static void appendMessage(
        List<Object> messages,
        String role,
        List<Object> blocks
    ) {
        if (!messages.isEmpty()) {
            Map<String, Object> last = Json.object(
                messages.get(messages.size() - 1)
            );
            if (role.equals(last.get("role"))) {
                Json.array(last.get("content")).addAll(blocks);
                return;
            }
        }
        messages.add(Json.map("role", role, "content", blocks));
    }

    private static List<Object> tools() {
        List<Object> result = new ArrayList<Object>();
        for (Object raw : ListTools.DEFINITIONS) {
            Map<String, Object> tool = Json.object(raw);
            result.add(
                Json.map(
                    "name",
                    Json.string(tool, "name"),
                    "description",
                    Json.string(tool, "description"),
                    "input_schema",
                    tool.get("parameters")
                )
            );
        }
        // Cache the stable tool prefix separately from the system prompt. The
        // final breakpoint below it is used for conversation history.
        if (!result.isEmpty()) {
            Json.object(result.get(result.size() - 1)).put(
                "cache_control",
                cacheControl()
            );
        }
        return result;
    }

    /** Anthropic's extended prompt-cache policy: an ephemeral one-hour entry. */
    private static Map<String, Object> cacheControl() {
        return Json.map("type", "ephemeral", "ttl", "1h");
    }

    static Map<String, Object> parseEvents(
        InputStream input,
        Agent.Activity activity
    ) throws IOException {
        List<Object> output = new ArrayList<Object>();
        Map<Integer, Block> blocks = new LinkedHashMap<Integer, Block>();
        StringBuilder data = new StringBuilder();
        boolean stopped = false;
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
                Map<String, Object> event = Json.object(
                    Json.parse(data.toString())
                );
                data.setLength(0);
                String type = Json.text(event, "type");
                if ("error".equals(type)) throw new IOException(
                    "Claude could not complete the reply"
                );
                if ("content_block_start".equals(type)) {
                    int index = number(event, "index");
                    Map<String, Object> start = Json.child(
                        event,
                        "content_block"
                    );
                    Block block = new Block(Json.text(start, "type"));
                    block.id = Json.text(start, "id");
                    block.name = Json.text(start, "name");
                    String text = Json.text(start, "text");
                    if (text != null) {
                        block.value.append(text);
                        if ("text".equals(block.type)) activity.text(text);
                    }
                    blocks.put(Integer.valueOf(index), block);
                } else if ("content_block_delta".equals(type)) {
                    Block block = blocks.get(
                        Integer.valueOf(number(event, "index"))
                    );
                    Map<String, Object> delta = Json.child(event, "delta");
                    if (block != null) {
                        String piece = "tool_use".equals(block.type)
                            ? Json.text(delta, "partial_json")
                            : Json.text(delta, "text");
                        if (piece == null) piece = Json.text(delta, "thinking");
                        if (piece != null) {
                            block.value.append(piece);
                            if ("text".equals(block.type)) activity.text(piece);
                        }
                    }
                } else if ("message_stop".equals(type)) {
                    stopped = true;
                }
            }
        } finally {
            reader.close();
        }
        if (!stopped) throw new IOException(
            "The connection to Claude ended early; ask again"
        );
        for (Block block : blocks.values()) {
            if ("tool_use".equals(block.type)) {
                output.add(
                    Json.map(
                        "type",
                        "function_call",
                        "id",
                        block.id,
                        "call_id",
                        block.id,
                        "name",
                        block.name,
                        "arguments",
                        block.value.length() == 0
                            ? "{}"
                            : block.value.toString()
                    )
                );
            } else if ("text".equals(block.type) && block.value.length() > 0) {
                output.add(message(block.value.toString()));
            }
        }
        return Json.map("status", "completed", "output", output);
    }

    private static Map<String, Object> completed(
        Map<String, Object> response,
        Agent.Activity activity
    ) throws IOException {
        if (
            !"message".equals(response.get("type")) ||
            !(response.get("content") instanceof List)
        ) {
            throw new IOException("Claude did not return a complete reply");
        }
        List<Object> output = new ArrayList<Object>();
        for (Object raw : Json.children(response, "content")) {
            Map<String, Object> block = Json.object(raw);
            if ("text".equals(block.get("type"))) {
                String text = Json.string(block, "text");
                activity.text(text);
                output.add(message(text));
            } else if ("tool_use".equals(block.get("type"))) {
                String id = Json.string(block, "id");
                output.add(
                    Json.map(
                        "type",
                        "function_call",
                        "id",
                        id,
                        "call_id",
                        id,
                        "name",
                        Json.string(block, "name"),
                        "arguments",
                        Json.write(Json.child(block, "input"))
                    )
                );
            }
        }
        return Json.map("status", "completed", "output", output);
    }

    private static Map<String, Object> message(String text) {
        return Json.map(
            "type",
            "message",
            "role",
            "assistant",
            "status",
            "completed",
            "content",
            Json.list(Json.map("type", "output_text", "text", text))
        );
    }

    private static int number(Map<String, Object> map, String key) {
        Double value = Json.number(map, key);
        return value == null ? 0 : value.intValue();
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

    private static final class Block {

        final String type;
        final StringBuilder value = new StringBuilder();
        String id;
        String name;

        Block(String type) {
            this.type = type;
        }
    }

    private static final class Unauthorized extends IOException {

        Unauthorized() {
            super("unauthorized");
        }
    }
}
