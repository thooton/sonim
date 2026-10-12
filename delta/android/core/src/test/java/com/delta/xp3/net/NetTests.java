package com.delta.xp3.net;

import com.delta.xp3.Check;
import com.delta.xp3.TestServer;

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

        check.run("listing and archiving sessions", new Check.Body() {
            @Override
            public void run() throws Exception {
                TestServer server = new TestServer(new TestServer.Handler() {
                    @Override
                    public TestServer.Reply handle(TestServer.Request request) {
                        if (request.path.equals("/v1/code/sessions")) {
                            return TestServer.Reply.json("{\"data\":["
                                + "{\"id\":\"cse_old\",\"title\":\"Old\",\"status\":\"active\","
                                + "\"worker_status\":\"idle\",\"environment_kind\":\"anthropic_cloud\","
                                + "\"last_event_at\":\"2026-10-01T10:00:00Z\"},"
                                + "{\"id\":\"session_new\",\"title\":\"New\",\"worker_status\":\"running\","
                                + "\"environment_kind\":\"anthropic_cloud\",\"last_event_at\":\"2026-10-11T01:26:00.123456Z\"},"
                                + "{\"id\":\"cse_local\",\"title\":\"Laptop\",\"environment_kind\":\"bridge\"},"
                                + "{\"id\":\"cse_gone\",\"title\":\"Gone\",\"status\":\"archived\","
                                + "\"environment_kind\":\"anthropic_cloud\"},"
                                + "{\"id\":\"cse_bad/../x\",\"title\":\"Odd\"},"
                                + "\"junk\"]}");
                        }
                        if (request.path.equals("/v1/code/sessions/cse_new/archive")) {
                            return TestServer.Reply.json("{}");
                        }
                        if (request.path.equals("/v1/code/sessions/cse_old/archive")) {
                            return TestServer.Reply.of(409, "application/json", "{}");
                        }
                        return TestServer.Reply.of(404, "application/json", "{}");
                    }
                });
                try {
                    Cloud cloud = new Cloud(new Http(), new ClaudeAuth(new Http(), "tok", null, 0, null), server.uri(""));
                    List<com.delta.xp3.data.Chat> chats = cloud.sessions();
                    check.same("cloud sessions only, archived and odd ids left out", 2, chats.size());
                    check.same("newest first", "session_new", chats.get(0).id);
                    check.same("cse ids stored as session ids", "session_old", chats.get(1).id);
                    check.same("status", "running", chats.get(0).status);
                    check.same("fractional seconds", 1791681960123L, chats.get(0).updated);
                    TestServer.Request listed = find(server.seen, "/v1/code/sessions");
                    check.same("listed unscoped, as the CLI does", "", listed.header("x-organization-uuid"));
                    check.same("listed with the login", "Bearer tok", listed.header("authorization"));

                    cloud.archive("session_new");
                    TestServer.Request archived = find(server.seen, "/v1/code/sessions/cse_new/archive");
                    check.same("archive is a post", "POST", archived.method);
                    cloud.archive("session_old");
                    check.ok("already archived is fine", true);
                } finally {
                    server.close();
                }
            }
        });

        check.run("a new environment has full network access", new Check.Body() {
            @Override
            public void run() throws Exception {
                TestServer server = new TestServer(new TestServer.Handler() {
                    @Override
                    public TestServer.Reply handle(TestServer.Request request) {
                        if (request.path.equals("/api/oauth/profile")) {
                            return TestServer.Reply.json("{\"organization\":{\"uuid\":\"org-1\"}}");
                        }
                        if (request.path.equals("/v1/environment_providers")) {
                            return TestServer.Reply.json("{\"environments\":[]}");
                        }
                        if (request.path.equals("/v1/environment_providers/cloud/create")) {
                            return TestServer.Reply.json("{\"environment_id\":\"env_new\"}");
                        }
                        if (request.path.equals("/v1/sessions")) {
                            return TestServer.Reply.json("{\"id\":\"session_01X\"}");
                        }
                        return TestServer.Reply.of(404, "application/json", "{}");
                    }
                });
                try {
                    Cloud cloud = new Cloud(new Http(), new ClaudeAuth(new Http(), "tok", null, 0, null),
                        server.uri(""));
                    cloud.create("t", "u", "hi");
                    Map<String, Object> made = Json.object(Json.parse(
                        find(server.seen, "/v1/environment_providers/cloud/create").body));
                    List<Object> hosts = Json.children(Json.child(Json.child(made, "config"), "network_config"),
                        "allowed_hosts");
                    check.same("every host allowed", "*", hosts.get(0));
                    check.same("used", "env_new", Json.object(Json.parse(find(server.seen, "/v1/sessions").body))
                        .get("environment_id"));
                } finally {
                    server.close();
                }
            }
        });

        check.run("the command center", new Check.Body() {
            @Override
            public void run() throws Exception {
                final String apk = "PK\u0003\u0004 not really an apk";
                TestServer server = new TestServer(new TestServer.Handler() {
                    @Override
                    public TestServer.Reply handle(TestServer.Request request) {
                        if (!request.header("authorization").equals("Bearer delta_t")) {
                            return TestServer.Reply.of(401, "application/json", "{\"error\":\"invalid\"}");
                        }
                        if (request.path.equals("/cc/auth")) return TestServer.Reply.json("{\"ok\":true}");
                        if (request.path.equals("/cc/files")) {
                            return TestServer.Reply.json("[{\"name\":\"list.apk\",\"size\":1234,"
                                + "\"modified\":\"2026-10-11T01:26:00.5Z\"},"
                                + "{\"name\":\".upload-1\",\"size\":1,\"modified\":\"2026-10-11T01:26:00Z\"},"
                                + "{\"name\":\"notes.txt\",\"size\":3,\"modified\":\"bad\"}]");
                        }
                        if (request.path.equals("/cc/files/My%20app%2B1.apk") && request.method.equals("GET")) {
                            return TestServer.Reply.of(200, "application/vnd.android.package-archive", apk);
                        }
                        if (request.path.equals("/cc/files/list.apk") && request.method.equals("DELETE")) {
                            return TestServer.Reply.json("{\"deleted\":\"list.apk\"}");
                        }
                        if (request.path.equals("/cc/files/gone.apk")) {
                            return TestServer.Reply.of(404, "application/json", "{\"error\":\"no such file\"}");
                        }
                        return TestServer.Reply.of(500, "application/json", "{}");
                    }
                });
                try {
                    String base = server.uri("/cc").toString();
                    final Center center = new Center(new Http(), base, "delta_t");
                    center.check();
                    List<com.delta.xp3.data.RemoteFile> files = center.list();
                    check.same("hidden names skipped", 2, files.size());
                    check.same("name", "list.apk", files.get(0).name);
                    check.same("size", 1234L, files.get(0).size);
                    check.same("modified", 1791681960500L, files.get(0).modified);
                    check.same("unreadable time is unknown", 0L, files.get(1).modified);

                    final long[] seen = {-2, -2};
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    center.download("My app+1.apk", out, new Center.Progress() {
                        @Override
                        public boolean cancelled() {
                            return false;
                        }

                        @Override
                        public void progress(long done, long total) {
                            seen[0] = done;
                            seen[1] = total;
                        }
                    });
                    check.same("bytes", apk, out.toString("UTF-8"));
                    check.same("progress reached the end", Long.valueOf(apk.getBytes("UTF-8").length), Long.valueOf(seen[0]));
                    check.same("total known", seen[0], seen[1]);

                    center.delete("list.apk");
                    check.same("delete", "DELETE", find(server.seen, "/cc/files/list.apk").method);
                    center.delete("gone.apk");
                    check.ok("already gone is deleted", true);

                    check.fails("cancel", "Cancelled", new Check.Body() {
                        @Override
                        public void run() throws Exception {
                            center.download("My app+1.apk", new java.io.ByteArrayOutputStream(), new Center.Progress() {
                                @Override
                                public boolean cancelled() {
                                    return true;
                                }

                                @Override
                                public void progress(long done, long total) {}
                            });
                        }
                    });
                    check.fails("missing file named", "no longer on the command center", new Check.Body() {
                        @Override
                        public void run() throws Exception {
                            center.download("gone.apk", new java.io.ByteArrayOutputStream(), null);
                        }
                    });
                    final Center wrong = new Center(new Http(), base, "nope");
                    check.fails("wrong token named, body not shown", "refused the token", new Check.Body() {
                        @Override
                        public void run() throws Exception {
                            wrong.list();
                        }
                    });
                    check.same("segment", "a%20b%2Bc%2F%C3%A9.apk", Center.segment("a b+c/é.apk"));
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
