package com.delta.xp3.net;

import com.delta.xp3.data.Chat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cloud agent sessions: the provider's cloud session protocol, as far as a
 * phone needs it.
 *
 * A session is created once with the first message riding along, and after
 * that everything is events. Messages and control requests are posted to the
 * session; what the cloud worker does comes back on a server-sent event
 * stream that replays from any sequence number, so a dropped connection
 * resumes where it stopped instead of starting over.
 *
 * Every message is preceded by a request for Auto permission mode, so the agent
 * decides which tool calls are safe in the sandbox rather than stopping to
 * ask. Sent each time rather than once, because the worker behind a session is
 * started afresh after it has been idle.
 */
public final class Cloud implements EventSource {

    public static final URI API = URI.create("https://api.anthropic.com");
    /** Where the same session opens on a computer. */
    public static final String WEB = "https://claude.ai/code/";
    /** The permission mode every turn runs in. */
    public static final String MODE = "auto";

    private static final String CLI_VERSION = "2.1.263";
    private static final String BETA = "ccr-byoc-2025-07-29";

    /** Where a session runs when it runs in the provider's cloud rather than on someone's computer. */
    static final String CLOUD_KIND = "anthropic_cloud";

    private final Http http;
    private final CloudAuth auth;
    private final URI base;
    private String organization;
    private String environment;

    public Cloud(Http http, CloudAuth auth) {
        this(http, auth, API);
    }

    Cloud(Http http, CloudAuth auth, URI base) {
        this.http = http;
        this.auth = auth;
        this.base = base;
    }

    /**
     * The account's cloud sessions, most recently active first. Archived
     * sessions are left out, and so is anything that is not running in the
     * cloud: a Remote Control session ("bridge") is somebody's own computer,
     * which a phone has no business driving.
     */
    public List<Chat> sessions() throws IOException, InterruptedException {
        // Unscoped, as the provider's CLI lists them: this route reads the
        // organization from the login.
        Map<String, Object> listed = object(request("/v1/code/sessions", null, false, false), "session list");
        List<Chat> chats = new ArrayList<Chat>();
        for (Object value : Json.children(listed, "data")) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> session = Json.object(value);
            String kind = Json.text(session, "environment_kind");
            if (kind != null && !CLOUD_KIND.equals(kind)) continue;
            if ("archived".equals(Json.text(session, "status"))) continue;
            String id = Chat.sessionId(Json.text(session, "id"));
            if (!Chat.validId(id)) continue;
            long updated = Json.instant(session, "last_event_at");
            if (updated == 0) updated = Json.instant(session, "updated_at");
            if (updated == 0) updated = Json.instant(session, "created_at");
            chats.add(new Chat(id, Json.text(session, "title"), Json.text(session, "worker_status"), updated));
        }
        Collections.sort(chats, new Comparator<Chat>() {
            @Override
            public int compare(Chat a, Chat b) {
                return a.updated == b.updated ? 0 : a.updated > b.updated ? -1 : 1;
            }
        });
        return chats;
    }

    /**
     * Puts a session away, as the web app's archive does. It stops showing
     * up in `sessions`; one already archived counts as done.
     */
    public void archive(String session) throws IOException, InterruptedException {
        int status = statusOf("/v1/code/sessions/" + cse(session) + "/archive", "{}");
        if (status == 409) return;
        Http.requireSuccess(status, "Cloud");
    }

    /**
     * Starts a session with its first message, and returns its id. The sandbox
     * is empty: no repository is cloned into it.
     */
    public String create(String title, String uuid, String text) throws IOException, InterruptedException {
        return create(title, uuid, text, null);
    }

    /** As above, with JPEG pictures riding along in the first message. */
    public String create(String title, String uuid, String text, List<byte[]> jpegs)
        throws IOException, InterruptedException {
        String body = Json.write(Json.map(
            "title", title,
            "events", Json.list(
                Json.map("type", "event", "data", modeRequest()),
                Json.map("type", "event", "data", userEvent(uuid, text, jpegs, ""))
            ),
            "session_context", Json.map("sources", Json.list(), "outcomes", Json.list()),
            "environment_id", environment()
        ));
        Map<String, Object> session = object(post("/v1/sessions", body, true), "session");
        String id = Json.string(session, "id");
        // The id becomes a file name and a list key, so it has to pass the
        // same check as every id the list hands back.
        if (!Chat.validId(id)) throw new IOException("The cloud returned an unexpected session id");
        return id;
    }

    /** Sends a follow-up message. The worker queues it if a turn is running. */
    public void send(String session, String uuid, String text) throws IOException, InterruptedException {
        send(session, uuid, text, null);
    }

    /** As above, with JPEG pictures. */
    public void send(String session, String uuid, String text, List<byte[]> jpegs)
        throws IOException, InterruptedException {
        post(events(session), Json.write(Json.map("events", Json.list(
            Json.map("payload", modeRequest()),
            Json.map("payload", userEvent(uuid, text, jpegs, cse(session)))
        ))), false);
    }

    /** Asks the worker to stop the turn it is running. */
    public void interrupt(String session) throws IOException, InterruptedException {
        post(events(session), Json.write(Json.map("events", Json.list(
            Json.map("payload", controlRequest(Json.map("subtype", "interrupt")))
        ))), false);
    }

    /**
     * Answers a permission request the worker is waiting on. An allowed call
     * runs with the input it was asked about, unchanged.
     */
    public void answer(String session, String requestId, boolean allow, Map<String, Object> input)
        throws IOException, InterruptedException {
        Map<String, Object> decision = allow
            ? Json.map("behavior", "allow", "updatedInput", input)
            : Json.map("behavior", "deny", "message", "Denied from the phone.");
        post(events(session), Json.write(Json.map("events", Json.list(Json.map("payload", Json.map(
            "type", "control_response",
            "response", Json.map("subtype", "success", "request_id", requestId, "response", decision),
            "uuid", UUID.randomUUID().toString()
        ))))), false);
    }

    /**
     * Opens the session's event stream after `afterSequence`; zero replays the
     * whole session. The caller reads it with Sse and closes it, which is also
     * how it is cancelled from another thread.
     */
    @Override
    public Http.Stream open(String session, long afterSequence) throws IOException, InterruptedException {
        String path = "/v1/code/sessions/" + cse(session) + "/events/stream";
        if (afterSequence > 0) path += "?from_sequence_num=" + afterSequence;
        for (int attempt = 0; ; attempt++) {
            Http.Request request = headers(Http.Request.to(base.resolve(path)), false)
                .header("Accept", "text/event-stream")
                .identity();
            if (afterSequence > 0) request.header("Last-Event-ID", String.valueOf(afterSequence));
            Http.Stream stream = http.stream(request);
            if (stream.status() == 401 && attempt == 0) {
                stream.close();
                auth.invalidate();
                continue;
            }
            if (stream.status() < 200 || stream.status() >= 300) {
                int status = stream.status();
                stream.close();
                Http.requireSuccess(status, "Cloud");
            }
            return stream;
        }
    }

    /**
     * Every event of a session, oldest first, as the event list pages them
     * (the payloads only). This is the whole conversation as the agent had
     * it, tool calls and results included, which the phone's own transcript
     * leaves out; it is what a conversation carried to another account is
     * built from.
     */
    public List<Map<String, Object>> history(String session) throws IOException, InterruptedException {
        List<Map<String, Object>> payloads = new ArrayList<Map<String, Object>>();
        String cursor = null;
        for (int page = 0; page < 10000; page++) {
            String path = "/v1/code/sessions/" + cse(session) + "/events?sort_order=asc";
            if (cursor != null) path += "&cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8");
            Map<String, Object> listed = object(request(path, null, false, true), "event list");
            List<Object> data = Json.children(listed, "data");
            for (Object value : data) {
                if (!(value instanceof Map)) continue;
                Map<String, Object> event = Json.object(value);
                // The next page starts after the last sequence number seen,
                // as the provider's own client pages it.
                Object sequence = event.get("sequence_num");
                if (sequence instanceof Number) cursor = String.valueOf(((Number) sequence).longValue());
                else if (sequence instanceof String) cursor = (String) sequence;
                Map<String, Object> payload = Json.child(event, "payload");
                if (!payload.isEmpty()) payloads.add(payload);
            }
            Object next = listed.get("next_cursor");
            if (data.isEmpty() || next == null || "".equals(next)) return payloads;
        }
        throw new IOException("The session's history is too long to read");
    }

    public static String link(String session) {
        return WEB + session;
    }

    /** The events API names a session cse_… where the sessions API says session_…. */
    static String cse(String session) {
        return session.startsWith("session_") ? "cse_" + session.substring("session_".length()) : session;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    private static Map<String, Object> userEvent(String uuid, String text, List<byte[]> jpegs, String sessionId) {
        return Json.map(
            "uuid", uuid,
            "session_id", sessionId,
            "type", "user",
            "parent_tool_use_id", null,
            "message", Json.map("role", "user", "content", content(text, jpegs))
        );
    }

    /**
     * Plain text alone, or content blocks when there are pictures: each one
     * inline as base64, the way the provider's own clients send a pasted
     * image, then the text after them.
     */
    static Object content(String text, List<byte[]> jpegs) {
        if (jpegs == null || jpegs.isEmpty()) return text;
        List<Object> blocks = new ArrayList<Object>();
        for (byte[] jpeg : jpegs) {
            blocks.add(Json.map("type", "image", "source", Json.map(
                "type", "base64",
                "media_type", "image/jpeg",
                "data", Base64.getEncoder().encodeToString(jpeg)
            )));
        }
        if (text != null && text.trim().length() > 0) blocks.add(Json.map("type", "text", "text", text));
        return blocks;
    }

    private static Map<String, Object> modeRequest() {
        return controlRequest(Json.map("subtype", "set_permission_mode", "mode", MODE));
    }

    private static Map<String, Object> controlRequest(Map<String, Object> request) {
        return Json.map(
            "type", "control_request",
            "request_id", UUID.randomUUID().toString(),
            "request", request,
            "uuid", UUID.randomUUID().toString()
        );
    }

    private static String events(String session) {
        return "/v1/code/sessions/" + cse(session) + "/events";
    }

    // ------------------------------------------------------------------
    // The account
    // ------------------------------------------------------------------

    /** Every sessions call is scoped to the login's organization. */
    private synchronized String organization() throws IOException, InterruptedException {
        if (organization == null) {
            Map<String, Object> profile = object(request("/api/oauth/profile", null, false, false), "profile");
            organization = Json.string(Json.child(profile, "organization"), "uuid");
        }
        return organization;
    }

    /**
     * The first cloud environment on the account, made if there is none. A
     * made one has full network access (`*`, the "Full" setting in
     * the web app), so the sandbox can reach the command center and
     * anything else a task needs. One that already exists is used as it is.
     */
    private synchronized String environment() throws IOException, InterruptedException {
        if (environment != null) return environment;
        Map<String, Object> listed = object(request("/v1/environment_providers", null, false, true), "environments");
        List<Object> all = Json.children(listed, "environments");
        for (Object entry : all) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> candidate = Json.object(entry);
            if (CLOUD_KIND.equals(Json.text(candidate, "kind"))) {
                environment = Json.string(candidate, "environment_id");
                return environment;
            }
        }
        Map<String, Object> made = object(post("/v1/environment_providers/cloud/create", Json.write(Json.map(
            "name", "Default",
            "kind", CLOUD_KIND,
            "description", "Default - full network access",
            "config", Json.map(
                "environment_type", "anthropic",
                "cwd", "/home/user",
                "init_script", null,
                "environment", Json.map(),
                "languages", Json.list(
                    Json.map("name", "python", "version", "3.11"),
                    Json.map("name", "node", "version", "20")
                ),
                "network_config", Json.map(
                    "allowed_hosts", Json.list("*"),
                    "allow_default_hosts", Boolean.TRUE
                )
            )
        )), true), "environment");
        environment = Json.string(made, "environment_id");
        return environment;
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private String post(String path, String body, boolean beta) throws IOException, InterruptedException {
        return request(path, body, beta, true);
    }

    /** One call, retried once with a renewed login if the first was refused as expired. */
    private String request(String path, String body, boolean beta, boolean scoped)
        throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            Http.Request request = headers(Http.Request.to(base.resolve(path)), beta, scoped)
                .header("Accept", "application/json")
                .timeout(60000);
            if (body != null) request.header("Content-Type", "application/json").post(body);
            Http.Response response = http.send(request);
            if (response.status() == 401 && attempt == 0) {
                auth.invalidate();
                continue;
            }
            Http.requireSuccess(response.status(), "Cloud");
            return response.body();
        }
    }

    /** A POST whose status is the answer, retried once with a renewed login. */
    private int statusOf(String path, String body) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            Http.Request request = headers(Http.Request.to(base.resolve(path)), false, false)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(60000)
                .post(body);
            int status = http.send(request).status();
            if (status == 401 && attempt == 0) {
                auth.invalidate();
                continue;
            }
            return status;
        }
    }

    private Http.Request headers(Http.Request request, boolean beta) throws IOException, InterruptedException {
        return headers(request, beta, true);
    }

    private Http.Request headers(Http.Request request, boolean beta, boolean scoped)
        throws IOException, InterruptedException {
        // The organization first: looking it up can renew the login, and the
        // token read before that would go out already replaced.
        if (scoped) request.header("x-organization-uuid", organization());
        request
            .header("Authorization", "Bearer " + auth.token())
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-client-platform", "claude_code_cli")
            .header("User-Agent", "claude-code/" + CLI_VERSION);
        if (beta) request.header("anthropic-beta", BETA);
        return request;
    }

    private static Map<String, Object> object(String body, String what) throws IOException {
        try {
            return Json.object(Json.parse(body));
        } catch (RuntimeException e) {
            throw new IOException("The cloud sent an unreadable " + what);
        }
    }
}
