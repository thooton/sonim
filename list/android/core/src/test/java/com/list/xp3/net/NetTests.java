package com.list.xp3.net;

import com.list.xp3.Check;
import com.list.xp3.TestServer;
import com.list.xp3.data.Checklist;
import com.list.xp3.data.Item;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class NetTests {

    private NetTests() {}

    public static void run(final Check check) {
        check.run("ListTools gate", new Check.Body() {
            @Override public void run() { gate(check); }
        });
        check.run("ListTools validation", new Check.Body() {
            @Override public void run() { validation(check); }
        });
        check.run("Agent loop", new Check.Body() {
            @Override public void run() throws Exception { agent(check); }
        });
        check.run("Agent abandoned turn", new Check.Body() {
            @Override public void run() throws Exception { abandoned(check); }
        });
        check.run("Claude token renewal", new Check.Body() {
            @Override public void run() throws Exception { claudeLogin(check); }
        });
        check.run("Claude transport", new Check.Body() {
            @Override public void run() throws Exception { claudeModel(check); }
        });
        check.run("Config", new Check.Body() {
            @Override public void run() { config(check); }
        });
    }

    private static final String SET_MILK =
        "{\"items\":[{\"text\":\"Buy milk\",\"done\":false},{\"text\":\"Walk dog\",\"done\":true}]}";

    private static void gate(Check check) {
        Checklist list = new Checklist(Arrays.asList(new Item("Walk dog", true)));
        final AtomicInteger changes = new AtomicInteger();
        ListTools tools = new ListTools(list, new Runnable() {
            @Override public void run() { changes.incrementAndGet(); }
        });

        check.same("set before any get is refused", ListTools.NEVER_READ,
            tools.call("set_list", SET_MILK));
        check.same("refused write leaves the list", Arrays.asList(new Item("Walk dog", true)), list.items());

        String read = tools.call("get_list", "{}");
        Map<String, Object> parsed = Json.object(Json.parse(read));
        Map<String, Object> first = Json.object(Json.children(parsed, "items").get(0));
        check.same("get_list returns text", "Walk dog", first.get("text"));
        check.same("get_list returns done", Boolean.TRUE, first.get("done"));
        check.same("set in the same round as its get is refused", ListTools.NEVER_READ,
            tools.call("set_list", SET_MILK));

        tools.endRound();
        String ok = tools.call("set_list", SET_MILK);
        check.ok("set after a seen get succeeds", ok.startsWith("OK"));
        check.same("list replaced in order",
            Arrays.asList(new Item("Buy milk", false), new Item("Walk dog", true)), list.items());
        check.same("change callback ran", 1, changes.get());

        String again = tools.call("set_list", "{\"items\":[{\"text\":\"Buy milk\",\"done\":true}]}");
        check.ok("a successful set counts as a read", again.startsWith("OK"));

        list.toggle(0); // The person unticks it on the List tab.
        String stale = tools.call("set_list", "{\"items\":[]}");
        check.same("set after a user edit is refused", ListTools.STALE, stale);
        check.same("stale write did not clear the list", 1, list.size());
        check.same("stale write did not notify", 2, changes.get());

        tools.call("get_list", "{}");
        tools.endRound();
        check.ok("re-reading allows the write", tools.call("set_list", "{\"items\":[]}").startsWith("OK"));
        check.same("list emptied", 0, list.size());

        tools.call("get_list", "{}");
        tools.endRound();
        tools.forget();
        check.same("forget requires a new read", ListTools.NEVER_READ,
            tools.call("set_list", "{\"items\":[]}"));

        tools.call("get_list", "{}");
        list.insert(0, "Late edit");
        tools.endRound();
        check.same("an edit between read and round end is caught", ListTools.STALE,
            tools.call("set_list", "{\"items\":[]}"));
        check.same("late edit survived", "Late edit", list.get(0).text);
    }

    private static void validation(Check check) {
        Checklist list = new Checklist(Arrays.asList(new Item("Keep me", false)));
        ListTools tools = new ListTools(list, null);
        tools.call("get_list", "{}");
        tools.endRound();
        long version = list.version();

        String[] bad = {
            "{}",
            "{\"items\":\"milk\"}",
            "{\"items\":[\"milk\"]}",
            "{\"items\":[{\"text\":\"\"}]}",
            "{\"items\":[{\"text\":\"ok\",\"done\":\"yes\"}]}",
            "{\"items\":[{\"done\":true}]}",
            "not json",
            "[]",
        };
        for (String arguments : bad) {
            String result = tools.call("set_list", arguments);
            check.ok("refused: " + arguments, result.startsWith("Error"));
        }
        check.same("bad arguments change nothing", Long.valueOf(version), Long.valueOf(list.version()));
        check.ok("unknown tool is an error", tools.call("delete_everything", "{}").startsWith("Error"));

        StringBuilder many = new StringBuilder("{\"items\":[");
        for (int i = 0; i <= Checklist.MAX_ITEMS; i++) {
            if (i > 0) many.append(',');
            many.append("{\"text\":\"t\",\"done\":false}");
        }
        many.append("]}");
        check.ok("too many items refused", tools.call("set_list", many.toString()).startsWith("Error"));

        String ok = tools.call("set_list", "{\"items\":[{\"text\":\"  two\\nlines \"}]}");
        check.ok("done may be omitted", ok.startsWith("OK"));
        check.same("text cleaned, done defaults false", new Item("two lines", false), list.get(0));

        Map<String, Object> getDef = Json.object(ListTools.DEFINITIONS.get(0));
        Map<String, Object> setDef = Json.object(ListTools.DEFINITIONS.get(1));
        check.same("two tools", 2, ListTools.DEFINITIONS.size());
        check.same("first is get_list", "get_list", getDef.get("name"));
        check.same("second is set_list", "set_list", setDef.get("name"));
    }

    // ------------------------------------------------------------------

    /** A model that replays prepared rounds and records what it was sent. */
    private static final class Replay implements Agent.Responder {

        interface Hook {
            void before(int round) throws IOException;
        }

        final List<List<Object>> rounds = new ArrayList<List<Object>>();
        final List<String> sent = new ArrayList<String>();
        Hook hook;

        Replay say(String text) {
            rounds.add(Json.list(message(text)));
            return this;
        }

        Replay call(String id, String name, String arguments) {
            rounds.add(Json.list(Json.map("type", "function_call", "call_id", id, "id", id,
                "name", name, "arguments", arguments)));
            return this;
        }

        Replay sayThenCall(String text, String id, String name, String arguments) {
            rounds.add(Json.list(message(text), Json.map("type", "function_call", "call_id", id,
                "id", id, "name", name, "arguments", arguments)));
            return this;
        }

        @Override
        public Map<String, Object> respond(List<Object> input, Agent.Activity activity)
            throws IOException {
            int round = sent.size();
            sent.add(Json.write(input));
            if (hook != null) hook.before(round);
            if (round >= rounds.size()) throw new IOException("no more rounds");
            for (Object item : rounds.get(round)) {
                Map<String, Object> entry = Json.object(item);
                if ("message".equals(entry.get("type"))) {
                    activity.text(String.valueOf(Json.object(
                        Json.children(entry, "content").get(0)).get("text")));
                }
            }
            return Json.map("output", new ArrayList<Object>(rounds.get(round)));
        }

        private static Map<String, Object> message(String text) {
            return Json.map("type", "message", "role", "assistant",
                "content", Json.list(Json.map("type", "output_text", "text", text)));
        }
    }

    private static void agent(Check check) throws Exception {
        Checklist list = new Checklist(Arrays.asList(new Item("Walk dog", false)));
        ListTools tools = new ListTools(list, null);
        Replay model = new Replay()
            .call("t1", "set_list", SET_MILK)
            .call("t2", "get_list", "{}")
            .call("t3", "set_list", SET_MILK)
            .say("Added milk.");
        Agent agent = new Agent(model, tools);
        final StringBuilder streamed = new StringBuilder();
        String answer = agent.chat("add milk", new Agent.Activity() {
            @Override public void text(String text) { streamed.append(text); }
        });
        check.same("final answer", "Added milk.", answer);
        check.same("streamed answer", "Added milk.", streamed.toString());
        check.ok("first blind write got the never-read error",
            model.sent.get(1).contains("you must call get_list before calling set_list"));
        check.ok("the get result went back to the model", model.sent.get(2).contains("Walk dog"));
        check.same("the informed write landed",
            Arrays.asList(new Item("Buy milk", false), new Item("Walk dog", true)), list.items());

        // The completed turn is history, including its read, so a follow-up
        // turn may write straight away until the person edits something.
        check.ok("a completed turn keeps its read",
            tools.call("set_list", SET_MILK).startsWith("OK"));

        Replay quiet = new Replay()
            .sayThenCall("Checking.", "q1", "get_list", "{}")
            .call("q2", "set_list", "{\"items\":[]}")
            .say("");
        Checklist other = new Checklist(Arrays.asList(new Item("x", false)));
        String done = new Agent(quiet, new ListTools(other, null)).chat("clear it", Agent.Activity.NOOP);
        check.same("text before a tool call is kept", "Checking.", done);
        check.same("quiet edit still applied", 0, other.size());

        Replay silent = new Replay().call("s1", "get_list", "{}").say("");
        check.same("a tool turn with no words says done", "Done.",
            new Agent(silent, new ListTools(new Checklist(), null)).chat("look", Agent.Activity.NOOP));

        final Replay failing = new Replay().call("f1", "get_list", "{}");
        Checklist failList = new Checklist();
        ListTools failTools = new ListTools(failList, null);
        Agent failAgent = new Agent(failing, failTools);
        try {
            failAgent.chat("look then crash", Agent.Activity.NOOP);
            check.ok("a turn with no further rounds fails", false);
        } catch (IOException expected) {
            check.ok("a turn with no further rounds fails", true);
        }
        check.same("a failed turn forgets its read", ListTools.NEVER_READ,
            failTools.call("set_list", "{\"items\":[]}"));

        Replay summary = new Replay().call("c1", "set_list", "{\"items\":[]}").say("Summary.");
        Checklist kept = new Checklist(Arrays.asList(new Item("keep", false)));
        ListTools summaryTools = new ListTools(kept, null);
        summaryTools.call("get_list", "{}");
        summaryTools.endRound();
        check.same("summary", "Summary.", new Agent(summary, summaryTools).summarize(Agent.Activity.NOOP));
        check.same("summarizing never writes the list", 1, kept.size());
    }

    private static void abandoned(Check check) throws Exception {
        Checklist list = new Checklist(Arrays.asList(new Item("Mine", false)));
        ListTools tools = new ListTools(list, null);
        final Replay model = new Replay()
            .call("a1", "get_list", "{}")
            .call("a2", "set_list", "{\"items\":[]}");
        final Agent agent = new Agent(model, tools);
        // The person presses Stop while the second request is on the wire.
        model.hook = new Replay.Hook() {
            @Override public void before(int round) {
                if (round == 1) agent.reset();
            }
        };
        try {
            agent.chat("clear my list", Agent.Activity.NOOP);
            check.ok("an abandoned turn is interrupted", false);
        } catch (InterruptedException expected) {
            check.ok("an abandoned turn is interrupted", true);
        }
        check.same("an abandoned turn never writes", Arrays.asList(new Item("Mine", false)), list.items());
    }

    // ------------------------------------------------------------------

    private static void claudeLogin(final Check check) throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        TestServer server = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                requests.incrementAndGet();
                Map<String, Object> body = Json.object(Json.parse(request.body));
                check.same("claude token request has client id",
                    "9d1c250a-e61b-44d9-88ed-5944d1962f5e", body.get("client_id"));
                return TestServer.Reply.json(
                    "{\"access_token\":\"sk-ant-oat-new\",\"refresh_token\":\"refresh-new\",\"expires_in\":3600}"
                );
            }
        });
        try {
            final List<String> stored = new ArrayList<String>();
            ClaudeAuth auth = new ClaudeAuth(new Http(), server.uri("/v1/oauth/token"),
                "old", "refresh-old", 1, new ClaudeAuth.Rotated() {
                    public void store(String access, String refresh, long expiresAt) {
                        stored.add(access + "|" + refresh);
                    }
                });
            check.same("expired Claude token is renewed", "sk-ant-oat-new", auth.token());
            check.same("Claude token rotation is stored", "sk-ant-oat-new|refresh-new", stored.get(0));
            auth.invalidate();
            auth.token();
            check.same("a rejected Claude token forces refresh", 2, requests.get());

        } finally {
            server.close();
        }
    }

    private static void claudeModel(Check check) throws Exception {
        TestServer server = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                return TestServer.Reply.of(200, "text/event-stream", sse(
                    "{\"type\":\"message_start\",\"message\":{}}",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi \"}}",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"there\"}}",
                    "{\"type\":\"content_block_stop\",\"index\":0}",
                    "{\"type\":\"message_stop\"}"
                ));
            }
        });
        try {
            ClaudeAuth auth = new ClaudeAuth(new Http(), server.uri("/oauth/token"),
                "sk-ant-oat-test", null, 0, null);
            ClaudeModel model = new ClaudeModel(new Http(), auth, server.uri("/v1/messages"));
            List<Object> history = new ArrayList<Object>();
            history.add(Json.map("role", "user", "content", "what is on my list"));
            history.add(Json.map("type", "function_call", "call_id", "toolu_1",
                "name", "get_list", "arguments", "{}"));
            history.add(Json.map("type", "function_call_output", "call_id", "toolu_1",
                "output", "{\"items\":[]}"));
            final StringBuilder streamed = new StringBuilder();
            Map<String, Object> response = model.respond(history, new Agent.Activity() {
                @Override public void text(String text) { streamed.append(text); }
            });
            check.same("streamed text arrives", "Hi there", streamed.toString());
            check.same("one message out", 1, Json.children(response, "output").size());

            TestServer.Request sent = server.seen.get(0);
            check.same("POST", "POST", sent.method);
            check.same("bearer auth", "Bearer sk-ant-oat-test", sent.header("Authorization"));
            check.ok("OAuth beta header", sent.header("anthropic-beta").contains("oauth-2025-04-20"));
            check.ok("streams", sent.header("Accept").contains("text/event-stream"));
            Map<String, Object> body = Json.object(Json.parse(sent.body));
            check.same("model is Opus 4.6", "claude-opus-4-6", body.get("model"));
            List<Object> tools = Json.children(body, "tools");
            check.same("two tools offered", 2, tools.size());
            check.same("get_list offered", "get_list", Json.object(tools.get(0)).get("name"));
            check.same("set_list offered", "set_list", Json.object(tools.get(1)).get("name"));
            check.ok("tools use input_schema", Json.object(tools.get(1)).containsKey("input_schema"));
            check.same("one tool call per response", Boolean.TRUE,
                Json.child(body, "tool_choice").get("disable_parallel_tool_use"));
            List<Object> system = Json.children(body, "system");
            check.same("identity block first",
                "You are Claude Code, Anthropic's official CLI for Claude.",
                Json.object(system.get(0)).get("text"));
            String prompt = String.valueOf(Json.object(system.get(1)).get("text"));
            check.ok("prompt is Amber", prompt.startsWith("You are Amber,"));
            check.ok("prompt explains the tools", prompt.contains("Always call get_list before set_list"));
            check.ok("prompt has the date", prompt.contains("Today is "));
            List<Object> messages = Json.children(body, "messages");
            check.same("history alternates into three messages", 3, messages.size());
            check.same("calls become tool_use", "tool_use", Json.object(
                Json.children(Json.object(messages.get(1)), "content").get(0)).get("type"));
            check.same("outputs become tool_result", "tool_result", Json.object(
                Json.children(Json.object(messages.get(2)), "content").get(0)).get("type"));
        } finally {
            server.close();
        }

        TestServer refused = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                return TestServer.Reply.of(401, "application/json", "{\"secret\":\"do-not-show\"}");
            }
        });
        try {
            ClaudeAuth auth = new ClaudeAuth(new Http(), refused.uri("/oauth/token"),
                "sk-ant-oat-test", null, 0, null);
            final ClaudeModel model = new ClaudeModel(new Http(), auth, refused.uri("/v1/messages"));
            final List<Object> history = new ArrayList<Object>();
            history.add(Json.map("role", "user", "content", "hi"));
            check.fails("a refused login says how to fix it", "claude-auth.json", new Check.Body() {
                @Override public void run() throws Exception { model.respond(history, Agent.Activity.NOOP); }
            });
            try {
                model.respond(history, Agent.Activity.NOOP);
            } catch (IOException e) {
                check.ok("error never carries the body", !e.getMessage().contains("do-not-show"));
            }
        } finally {
            refused.close();
        }

        String stream = sse(
            "{\"type\":\"message_start\",\"message\":{}}",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"set_list\",\"input\":{}}}",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"items\\\":\"}}",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                + "{\"type\":\"input_json_delta\",\"partial_json\":\"[]}\"}}",
            "{\"type\":\"content_block_stop\",\"index\":0}",
            "{\"type\":\"message_stop\"}"
        );
        Map<String, Object> streamed = ClaudeModel.parseEvents(
            new java.io.ByteArrayInputStream(stream.getBytes("UTF-8")), Agent.Activity.NOOP);
        Map<String, Object> call = Json.object(Json.children(streamed, "output").get(0));
        check.same("streamed tool call is converted", "set_list", call.get("name"));
        check.same("streamed arguments are joined", "{\"items\":[]}", call.get("arguments"));

        final String cut = sse("{\"type\":\"message_start\",\"message\":{}}");
        check.fails("a stream that ends early fails", "ended early", new Check.Body() {
            @Override public void run() throws Exception {
                ClaudeModel.parseEvents(new java.io.ByteArrayInputStream(cut.getBytes("UTF-8")),
                    Agent.Activity.NOOP);
            }
        });
    }

    private static void config(Check check) {
        check.ok("blank is not ready", !Config.blank().chatReady());
        check.ok("refresh alone is ready", Config.of(null, "r", 0).chatReady());
        Config parsed = Config.parse(Config.of("a", "r", 99).toJson());
        check.same("round trip access", "a", parsed.accessToken());
        check.same("round trip expiry", Long.valueOf(99), Long.valueOf(parsed.expiresAt()));
        check.fails("header-unsafe credential refused", new Check.Body() {
            @Override public void run() { Config.of("a b", null, 0); }
        });
    }

    private static String sse(String... events) {
        StringBuilder out = new StringBuilder();
        for (String event : events) out.append("data: ").append(event).append("\n\n");
        return out.toString();
    }
}
