package com.cloud.xp3.net;

import com.cloud.xp3.Check;
import com.cloud.xp3.TestServer;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class NetTests {

    private NetTests() {}

    public static void run(final Check check) {
        check.run("sse frames", new Check.Body() {
            @Override
            public void run() throws Exception {
                String wire = ":keepalive\n\n"
                    + "event: session_update\r\ndata: {\"a\":1}\r\n\r\n"
                    + "event: client_event\nid: 7\ndata: line one\ndata:line two\n\n"
                    + "data: été\n\n"
                    + "event: client_event\nid: 8\ndata: unterminated";
                Sse sse = new Sse(new ByteArrayInputStream(wire.getBytes("UTF-8")));
                Sse.Frame first = sse.next();
                check.same("event", "session_update", first.event);
                check.same("crlf stripped", "{\"a\":1}", first.data);
                Sse.Frame second = sse.next();
                check.same("id", "7", second.id);
                check.same("multi-line data", "line one\nline two", second.data);
                Sse.Frame third = sse.next();
                check.same("default event", "message", third.event);
                check.same("utf-8", "été", third.data);
                check.ok("a frame cut off by the end is not dispatched", sse.next() == null);
            }
        });

        check.run("session protocol", new Check.Body() {
            @Override
            public void run() throws Exception {
                TestServer server = new TestServer(new TestServer.Handler() {
                    @Override
                    public TestServer.Reply handle(TestServer.Request request) {
                        if (request.path.equals("/api/oauth/profile")) {
                            return TestServer.Reply.json("{\"organization\":{\"uuid\":\"org-1\"}}");
                        }
                        if (request.path.equals("/v1/environment_providers")) {
                            return TestServer.Reply.json("{\"environments\":["
                                + "{\"kind\":\"byoc\",\"environment_id\":\"env_byoc\"},"
                                + "{\"kind\":\"anthropic_cloud\",\"environment_id\":\"env_cloud\"}]}");
                        }
                        if (request.path.equals("/v1/sessions")) {
                            return TestServer.Reply.json("{\"id\":\"session_01X\",\"title\":\"t\"}");
                        }
                        if (request.path.startsWith("/v1/code/sessions/cse_01X/events/stream")) {
                            return TestServer.Reply.of(200, "text/event-stream",
                                "event: client_event\nid: 4\ndata: {\"sequence_num\":\"4\"}\n\n");
                        }
                        if (request.path.equals("/v1/code/sessions/cse_01X/events")) {
                            return TestServer.Reply.json("{\"results\":[]}");
                        }
                        return TestServer.Reply.of(404, "application/json", "{}");
                    }
                });
                try {
                    ClaudeAuth auth = new ClaudeAuth(new Http(), "tok-1", null, 0, null);
                    Cloud cloud = new Cloud(new Http(), auth, server.uri(""));

                    String id = cloud.create("Hello", "uuid-1", "Hello there");
                    check.same("session id", "session_01X", id);
                    TestServer.Request create = find(server.seen, "/v1/sessions");
                    check.same("bearer", "Bearer tok-1", create.header("authorization"));
                    check.same("organization", "org-1", create.header("x-organization-uuid"));
                    check.same("beta on create", "ccr-byoc-2025-07-29", create.header("anthropic-beta"));
                    Map<String, Object> body = Json.object(Json.parse(create.body));
                    check.same("cloud environment chosen", "env_cloud", body.get("environment_id"));
                    List<Object> events = Json.children(body, "events");
                    Map<String, Object> mode = Json.child(Json.object(events.get(0)), "data");
                    check.same("auto mode first", "auto", Json.child(mode, "request").get("mode"));
                    Map<String, Object> user = Json.child(Json.object(events.get(1)), "data");
                    check.same("user uuid", "uuid-1", user.get("uuid"));
                    check.same("user text", "Hello there", Json.child(user, "message").get("content"));
                    check.ok("parent is null", user.containsKey("parent_tool_use_id") && user.get("parent_tool_use_id") == null);

                    cloud.send(id, "uuid-2", "More");
                    TestServer.Request send = find(server.seen, "/v1/code/sessions/cse_01X/events");
                    check.same("no beta on events", "", send.header("anthropic-beta"));
                    List<Object> sent = Json.children(Json.object(Json.parse(send.body)), "events");
                    check.same("mode then message", 2, sent.size());
                    Map<String, Object> payload = Json.child(Json.object(sent.get(1)), "payload");
                    check.same("session named cse", "cse_01X", payload.get("session_id"));

                    server.seen.clear();
                    cloud.send(id, "uuid-3", "What is this?", java.util.Arrays.asList(
                        new byte[] {(byte) 0xff, (byte) 0xd8, 1}, new byte[] {2}));
                    Map<String, Object> pictured = Json.child(Json.object(Json.children(
                        Json.object(Json.parse(server.seen.get(0).body)), "events").get(1)), "payload");
                    List<Object> blocks = Json.children(Json.child(pictured, "message"), "content");
                    check.same("two pictures then text", 3, blocks.size());
                    Map<String, Object> source = Json.child(Json.object(blocks.get(0)), "source");
                    check.same("base64", "base64", source.get("type"));
                    check.same("jpeg", "image/jpeg", source.get("media_type"));
                    check.same("encoded", "/9gB", source.get("data"));
                    check.same("text last", "What is this?", Json.object(blocks.get(2)).get("text"));
                    check.same("picture alone has no text block", 1,
                        ((List<?>) Cloud.content("  ", java.util.Arrays.asList(new byte[] {1}))).size());
                    check.same("no pictures is plain text", "hi", Cloud.content("hi", null));

                    server.seen.clear();
                    cloud.interrupt(id);
                    Map<String, Object> interrupt = Json.child(Json.object(Json.children(
                        Json.object(Json.parse(server.seen.get(0).body)), "events").get(0)), "payload");
                    check.same("interrupt", "interrupt", Json.child(interrupt, "request").get("subtype"));

                    server.seen.clear();
                    cloud.answer(id, "req-9", true, Json.map("command", "ls"));
                    Map<String, Object> answer = Json.child(Json.object(Json.children(
                        Json.object(Json.parse(server.seen.get(0).body)), "events").get(0)), "payload");
                    Map<String, Object> response = Json.child(answer, "response");
                    check.same("answers the request", "req-9", response.get("request_id"));
                    check.same("allow", "allow", Json.child(response, "response").get("behavior"));
                    check.same("input echoed", "ls",
                        Json.child(Json.child(response, "response"), "updatedInput").get("command"));

                    server.seen.clear();
                    Http.Stream stream = cloud.open(id, 3);
                    try {
                        Sse.Frame frame = new Sse(stream.stream()).next();
                        check.same("streamed", "4", frame.id);
                    } finally {
                        stream.close();
                    }
                    TestServer.Request open = server.seen.get(0);
                    check.same("resumes after", "/v1/code/sessions/cse_01X/events/stream?from_sequence_num=3", open.path);
                    check.same("last event id", "3", open.header("last-event-id"));
                    check.same("sse accept", "text/event-stream", open.header("accept"));
                    check.same("link", "https://claude.ai/code/session_01X", Cloud.link(id));
                } finally {
                    server.close();
                }
            }
        });

        check.run("an expired login is renewed once", new Check.Body() {
            @Override
            public void run() throws Exception {
                final AtomicInteger renewals = new AtomicInteger();
                TestServer server = new TestServer(new TestServer.Handler() {
                    @Override
                    public TestServer.Reply handle(TestServer.Request request) {
                        if (request.path.equals("/token")) {
                            renewals.incrementAndGet();
                            check.ok("scope asked for", request.body.contains("user:sessions:claude_code"));
                            return TestServer.Reply.json(
                                "{\"access_token\":\"new\",\"refresh_token\":\"r2\",\"expires_in\":3600}");
                        }
                        if (!request.header("authorization").equals("Bearer new")) {
                            return TestServer.Reply.of(401, "application/json", "{}");
                        }
                        if (request.path.equals("/api/oauth/profile")) {
                            return TestServer.Reply.json("{\"organization\":{\"uuid\":\"org-1\"}}");
                        }
                        return TestServer.Reply.json("{}");
                    }
                });
                try {
                    final String[] stored = new String[1];
                    ClaudeAuth auth = new ClaudeAuth(new Http(), server.uri("/token"), "old", "r1",
                        System.currentTimeMillis() + 3600000L, new ClaudeAuth.Rotated() {
                            @Override
                            public void store(String access, String refresh, long expiresAt) {
                                stored[0] = refresh;
                            }
                        });
                    Cloud cloud = new Cloud(new Http(), auth, server.uri(""));
                    cloud.interrupt("session_01X");
                    check.same("renewed once", 1, renewals.get());
                    check.same("rotation stored", "r2", stored[0]);
                } finally {
                    server.close();
                }
            }
        });

        check.run("failures are named, not dumped", new Check.Body() {
            @Override
            public void run() throws Exception {
                TestServer server = new TestServer(new TestServer.Handler() {
                    @Override
                    public TestServer.Reply handle(TestServer.Request request) {
                        if (request.path.equals("/api/oauth/profile")) {
                            return TestServer.Reply.json("{\"organization\":{\"uuid\":\"org-1\"}}");
                        }
                        return TestServer.Reply.of(429, "application/json", "{\"secret\":\"tok\"}");
                    }
                });
                try {
                    final Cloud cloud = new Cloud(new Http(), new ClaudeAuth(new Http(), "t", null, 0, null),
                        server.uri(""));
                    check.fails("rate limit", "usage limit", new Check.Body() {
                        @Override
                        public void run() throws Exception {
                            cloud.send("session_01X", "u", "hi");
                        }
                    });
                    check.fails("stream status", "HTTP 429", new Check.Body() {
                        @Override
                        public void run() throws Exception {
                            cloud.open("session_01X", 0).close();
                        }
                    });
                } finally {
                    server.close();
                }
            }
        });
    }

    private static TestServer.Request find(List<TestServer.Request> seen, String path) {
        synchronized (seen) {
            for (TestServer.Request request : seen) {
                if (request.path.equals(path)) return request;
            }
        }
        throw new AssertionError("No request to " + path);
    }
}
