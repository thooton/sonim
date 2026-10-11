package com.cloud.xp3.data;

import com.cloud.xp3.Check;
import com.cloud.xp3.net.Json;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public final class DataTests {

    private DataTests() {}

    public static void run(final Check check) {
        check.run("transcript keeps the conversation", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                check.ok("log while idle is not a change", !t.apply("client_event", "1", frame(1, "worker",
                    Json.map("type", "env_manager_log", "data", Json.map("content", "Allocating sandbox"),
                        "uuid", "a"))));
                check.ok("client prompt shown", t.apply("client_event", "2", frame(2, "client", user("u1", "Hello"))));
                check.ok("prompt starts a turn", t.working());
                t.apply("client_event", "3", frame(3, "worker",
                    Json.map("type", "env_manager_log", "data", Json.map("content", "Starting Claude Code"))));
                check.same("start-up line is the status", "Starting Claude Code", t.status());
                t.apply("client_event", "4", frame(4, "worker", assistant(null,
                    Json.map("type", "tool_use", "name", "Bash", "input", Json.map("command", "ls\n-la"))
                )));
                t.apply("client_event", "5", frame(5, "worker", Json.map("type", "user", "message",
                    Json.map("role", "user", "content", Json.list(Json.map("type", "tool_result", "content", "x"))))));
                t.apply("client_event", "6", frame(6, "worker", assistant("toolu_1",
                    Json.map("type", "text", "text", "subagent chatter"))));
                t.apply("client_event", "7", frame(7, "worker", assistant(null,
                    Json.map("type", "thinking", "thinking", "hmm"),
                    Json.map("type", "text", "text", "  Done.  "))));
                check.ok("still working before the result", t.working());
                t.apply("client_event", "8", frame(8, "worker", Json.map("type", "result", "subtype", "success",
                    "is_error", Boolean.FALSE, "result", "Done.")));
                check.ok("result ends the turn", !t.working());
                List<Entry> entries = t.entries();
                check.same("you, tool, claude", 3, entries.size());
                check.same("prompt", "Hello", entries.get(0).text);
                check.same("tool line flattened", "Bash: ls -la", entries.get(1).text);
                check.same("tool kind", Entry.TOOL, entries.get(1).kind);
                check.same("text trimmed", "Done.", entries.get(2).text);
                check.same("sequence", 8L, t.sequence());

                check.ok("replay ignored", !t.apply("client_event", "7", frame(7, "worker", assistant(null,
                    Json.map("type", "text", "text", "again")))));
                check.same("nothing added by replay", 3, t.size());
            }
        });

        check.run("own messages are not shown twice", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                t.sent("u-1", "Fix it");
                check.ok("sending starts a turn", t.working());
                check.ok("echo is not a change", !t.apply("client_event", "1", frame(1, "client", user("u-1", "Fix it"))));
                check.same("one message", 1, t.size());
                t.apply("client_event", "2", frame(2, "client", user("u-2", "From the web")));
                check.same("another client's message shown", 2, t.size());
                t.apply("client_event", "3", frame(3, "client",
                    Json.map("type", "user", "uuid", "u-3", "isMeta", Boolean.TRUE,
                        "message", Json.map("role", "user", "content", "meta"))));
                check.same("meta messages hidden", 2, t.size());
                t.apply("client_event", "4", frame(4, "client", Json.map("type", "user", "uuid", "u-4",
                    "message", Json.map("role", "user", "content", Json.list(
                        Json.map("type", "text", "text", "one"), Json.map("type", "image"),
                        Json.map("type", "text", "text", "two"))))));
                check.same("text blocks joined", "one\n\ntwo", t.entries().get(2).text);
                check.same("picture counted", 1, t.entries().get(2).pictures);
                check.ok("picture alone is a message", t.apply("client_event", "5", frame(5, "client",
                    Json.map("type", "user", "uuid", "u-5", "message", Json.map("role", "user",
                        "content", Json.list(Json.map("type", "image"), Json.map("type", "image")))))));
                check.same("no text", "", t.entries().get(3).text);
                check.same("two pictures", 2, t.entries().get(3).pictures);
                t.sent("u-6", "", 1);
                check.ok("own picture echo skipped", !t.apply("client_event", "6", frame(6, "client",
                    Json.map("type", "user", "uuid", "u-6", "message", Json.map("role", "user",
                        "content", Json.list(Json.map("type", "image")))))));
                check.same("still one copy", 5, t.size());
            }
        });

        check.run("results that end badly leave a note", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                t.sent("u", "go");
                t.apply("client_event", "1", frame(1, "worker", Json.map("type", "result",
                    "subtype", "error_during_execution", "is_error", Boolean.TRUE)));
                check.same("interrupt note", "Stopped.", t.entries().get(1).text);
                check.ok("idle", !t.working());
                t.apply("client_event", "2", frame(2, "worker", Json.map("type", "result",
                    "subtype", "success", "is_error", Boolean.TRUE, "result", "API Error:\n 529 overloaded")));
                check.same("error text flattened", "API Error: 529 overloaded", t.entries().get(2).text);
            }
        });

        check.run("permission requests", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                check.ok("client requests ignored", !t.apply("client_event", "1", frame(1, "client",
                    Json.map("type", "control_request", "request_id", "c1",
                        "request", Json.map("subtype", "set_permission_mode", "mode", "auto")))));
                t.apply("client_event", "2", frame(2, "worker", Json.map("type", "control_request",
                    "request_id", "p1", "request", Json.map("subtype", "can_use_tool", "tool_name", "Bash",
                        "input", Json.map("command", "rm -rf build")))));
                Transcript.Permission pending = t.pending();
                check.ok("pending", pending != null);
                check.same("request id", "p1", pending.requestId);
                check.same("input kept", "rm -rf build", pending.input.get("command"));
                check.same("note", "Claude asks to use Bash: rm -rf build. Menu to allow or deny.",
                    t.entries().get(0).text);
                check.ok("other responses leave it", !t.apply("client_event", "3", frame(3, "worker",
                    Json.map("type", "control_response", "response", Json.map("request_id", "c1")))));
                check.ok("still pending", t.pending() != null);
                check.ok("answered elsewhere", t.apply("client_event", "4", frame(4, "client",
                    Json.map("type", "control_response", "response", Json.map("request_id", "p1")))));
                check.ok("cleared", t.pending() == null);
            }
        });

        check.run("malformed frames are skipped but counted", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                check.ok("bad json", !t.apply("client_event", "5", "{nope"));
                check.same("sequence still advances", 5L, t.sequence());
                check.ok("other events", !t.apply("session_update", "", "{\"connection_status\":\"connected\"}"));
                check.ok("truncation noted", t.apply("catch_up_truncated", "", "{}"));
                check.same("sequence from payload when no id", 9L, sequenceAfter("", 9));
            }
        });

        check.run("transcript is capped", new Check.Body() {
            @Override
            public void run() {
                Transcript t = new Transcript();
                for (int i = 0; i < Transcript.MAX_ENTRIES + 5; i++) t.note("n" + i);
                check.same("size", Transcript.MAX_ENTRIES, t.size());
                check.same("oldest dropped", "n5", t.entries().get(0).text);
            }
        });

        check.run("session round trip", new Check.Body() {
            @Override
            public void run() throws Exception {
                File directory = Files.createTempDirectory("cloud-store").toFile();
                Store store = new Store(directory);
                Store.Saved empty = store.session();
                check.ok("no session yet", empty.id == null && empty.transcript.size() == 0);

                Transcript t = new Transcript();
                t.sent("u-1", "Hi", 2);
                t.apply("client_event", "12", frame(12, "worker", assistant(null,
                    Json.map("type", "text", "text", "Hello **there**"))));
                store.saveSession("session_01abc", "Hi", t);

                Store.Saved back = store.session();
                check.same("id", "session_01abc", back.id);
                check.same("title", "Hi", back.title);
                check.same("sequence", 12L, back.transcript.sequence());
                check.ok("working", back.transcript.working());
                check.same("entries", 2, back.transcript.size());
                check.same("own id kept", "u-1", back.transcript.entries().get(0).id);
                check.same("pictures kept", 2, back.transcript.entries().get(0).pictures);
                check.same("none on claude's", 0, back.transcript.entries().get(1).pictures);
                check.ok("echo still recognised after restore", !back.transcript.apply("client_event", "13",
                    frame(13, "client", user("u-1", "Hi"))));

                store.saveSession(null, null, new Transcript());
                check.ok("cleared", store.session().id == null);

                Files.write(new File(directory, "session.json").toPath(), "{\"id\":\"evil\"}".getBytes("UTF-8"));
                check.ok("odd id refused", store.session().id == null);
            }
        });
    }

    private static long sequenceAfter(String id, int sequence) {
        Transcript t = new Transcript();
        t.apply("client_event", id, frame(sequence, "worker", Json.map("type", "system")));
        return t.sequence();
    }

    static String frame(int sequence, String source, Map<String, Object> payload) {
        return Json.write(Json.map(
            "event_id", "e" + sequence,
            "sequence_num", String.valueOf(sequence),
            "source", source,
            "payload", payload
        ));
    }

    static Map<String, Object> user(String uuid, String text) {
        return Json.map("type", "user", "uuid", uuid, "parent_tool_use_id", null,
            "message", Json.map("role", "user", "content", text));
    }

    static Map<String, Object> assistant(String parent, Object... blocks) {
        return Json.map("type", "assistant", "parent_tool_use_id", parent,
            "message", Json.map("role", "assistant", "content", Json.list(blocks)));
    }
}
