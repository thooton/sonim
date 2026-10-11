package com.canvas.xp3.net;

import com.canvas.xp3.Check;
import com.canvas.xp3.TestServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Tests for everything under net, in its package so it can reach inside. */
public final class NetTests {

    private NetTests() {}

    public static void run(final Check check) {
        check.run("json", new Check.Body() {
            public void run() {
                json(check);
            }
        });
        check.run("calculator", new Check.Body() {
            public void run() {
                calculator(check);
            }
        });
        check.run("cookies", new Check.Body() {
            public void run() {
                cookies(check);
            }
        });
        check.run("config", new Check.Body() {
            public void run() {
                config(check);
            }
        });
        check.run("origins", new Check.Body() {
            public void run() {
                origins(check);
            }
        });
        check.run("cookie service", new Check.Body() {
            public void run() throws Exception {
                cookieService(check);
            }
        });
        check.run("canvas paging", new Check.Body() {
            public void run() throws Exception {
                paging(check);
            }
        });
        check.run("canvas auth", new Check.Body() {
            public void run() throws Exception {
                auth(check);
            }
        });
        check.run("canvas due", new Check.Body() {
            public void run() throws Exception {
                due(check);
            }
        });
        check.run("tools", new Check.Body() {
            public void run() throws Exception {
                tools(check);
            }
        });
        check.run("model events", new Check.Body() {
            public void run() throws Exception {
                events(check);
            }
        });
        check.run("model request", new Check.Body() {
            public void run() throws Exception {
                request(check);
            }
        });
        check.run("codex login", new Check.Body() {
            public void run() throws Exception {
                codexLogin(check);
            }
        });
        check.run("claude login", new Check.Body() {
            public void run() throws Exception {
                claudeLogin(check);
            }
        });
        check.run("claude model", new Check.Body() {
            public void run() throws Exception {
                claudeModel(check);
            }
        });
        check.run("agent", new Check.Body() {
            public void run() throws Exception {
                agent(check);
            }
        });
    }

    // ------------------------------------------------------------------

    private static void json(Check check) {
        Map<String, Object> parsed = Json.object(Json.parse("{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":2.5}}"));
        check.same("number is exact", new BigDecimal("1"), parsed.get("a"));
        check.same("nested", new BigDecimal("2.5"), Json.child(parsed, "c").get("d"));
        check.same("array size", 3, Json.children(parsed, "b").size());
        check.same("round trip", "{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":2.5}}", Json.write(parsed));

        check.same("escapes", "\"a\\\"b\\\\c\\nd\"", Json.write("a\"b\\c\nd"));
        check.same("control chars", "\"\\u0001\"", Json.write("\u0001"));
        check.same("unicode escape", "ab", Json.parse("\"\\u0061b\""));
        check.same("text helper", null, Json.text(parsed, "missing"));
        check.same("number helper", Double.valueOf(2.5), Json.number(Json.child(parsed, "c"), "d"));
        check.ok("flag helper is false for absent", !Json.flag(parsed, "nope"));
        check.same("child of absent is empty", 0, Json.child(parsed, "nope").size());

        check.fails("duplicate keys refused", new Check.Body() {
            public void run() {
                Json.parse("{\"a\":1,\"a\":2}");
            }
        });
        check.fails("trailing content refused", new Check.Body() {
            public void run() {
                Json.parse("{} x");
            }
        });
        check.fails("raw control character refused", new Check.Body() {
            public void run() {
                Json.parse("\"a\nb\"");
            }
        });
        check.fails("unterminated string refused", new Check.Body() {
            public void run() {
                Json.parse("\"abc");
            }
        });
        check.fails("leading zero refused", new Check.Body() {
            public void run() {
                Json.parse("01");
            }
        });
        check.fails("bad escape refused", new Check.Body() {
            public void run() {
                Json.parse("\"\\q\"");
            }
        });
        check.fails("non-finite refused", new Check.Body() {
            public void run() {
                Json.write(Double.valueOf(Double.NaN));
            }
        });
        check.fails("unsupported type refused", new Check.Body() {
            public void run() {
                Json.write(new Object());
            }
        });
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200; i++) deep.append('[');
        final String nested = deep.toString();
        check.fails("depth limit", new Check.Body() {
            public void run() {
                Json.parse(nested);
            }
        });
    }

    private static void calculator(Check check) {
        check.same("what do I need on the final", "104", Calculator.evaluate("(90 - 84*0.7)/0.3"));
        check.same("powers", "1024", Calculator.evaluate("2^10"));
        check.same("negative exponent", "0.25", Calculator.evaluate("2^-2"));
        check.same("unary signs", "-3", Calculator.evaluate("-(1+2)"));
        check.same("precision", 34, Calculator.evaluate("1/3").length() - 2);
        check.fails("letters refused", new Check.Body() {
            public void run() {
                Calculator.evaluate("2+x");
            }
        });
        check.fails("unbalanced refused", new Check.Body() {
            public void run() {
                Calculator.evaluate("(1+2");
            }
        });
        check.fails("huge exponent refused", new Check.Body() {
            public void run() {
                Calculator.evaluate("2^101");
            }
        });
        check.fails("percent is not an operator", new Check.Body() {
            public void run() {
                Calculator.evaluate("85%");
            }
        });
    }

    private static void cookies(Check check) {
        check.same("plain pair", "a=b; c=d", Cookies.parse("  a=b; c=d "));
        check.same("header prefix stripped", "a=b", Cookies.parse("Cookie: a=b"));
        check.fails("empty refused", new Check.Body() {
            public void run() {
                Cookies.parse("");
            }
        });
        check.fails("valueless refused", new Check.Body() {
            public void run() {
                Cookies.parse("novalue");
            }
        });
        check.fails("newline refused", new Check.Body() {
            public void run() {
                Cookies.parse("a=b\nc=d");
            }
        });
    }

    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static void config(Check check) {
        Config blank = Config.blank();
        check.ok("blank is not ready", !blank.canvasReady() && !blank.chatReady());

        Config canvas = blank
            .with(Config.CANVAS_URL, "https://canvas.asu.edu")
            .with(Config.COOKIE_URL, "https://edit.example.org/api/canvas-cookie/cookie")
            .with(Config.COOKIE_TOKEN, TOKEN);
        check.ok("canvas alone is ready for lists", canvas.canvasReady());
        check.ok("canvas alone is not ready for chat", !canvas.chatReady());
        check.same("trailing slash added", "https://canvas.asu.edu/", canvas.canvasBase().toString());
        check.same("stored without the slash", "https://canvas.asu.edu", canvas.get(Config.CANVAS_URL));

        Config chat = canvas.with(Config.MODEL, "gpt-5-codex").with(Config.API_KEY, "sk-abc123");
        check.ok("api key is enough", chat.chatReady());

        Config codex = canvas.with(Config.PROVIDER, "codex").with(Config.MODEL, "gpt-5-codex")
            .with(Config.ACCESS_TOKEN, "tok");
        check.ok("codex needs an account too", !codex.chatReady());
        check.ok("codex with an account", codex.with(Config.ACCOUNT_ID, "acc").chatReady());

        Config claude = canvas.with(Config.PROVIDER, "claude").with(Config.MODEL, "claude-sonnet-4-6");
        check.ok("claude needs a login", !claude.chatReady());
        check.ok("claude refresh token is enough",
            claude.with(Config.REFRESH_TOKEN, "refresh").chatReady());

        Config again = Config.parse(chat.toJson());
        check.ok("survives a round trip", again.chatReady());
        check.same("round trip keeps the model", "gpt-5-codex", again.model());
        check.same("round trip keeps the token", TOKEN, again.cookieToken());

        check.same("a field can be cleared", "", chat.with(Config.API_KEY, "").get(Config.API_KEY));
        check.ok("clearing the key ends chat", !chat.with(Config.API_KEY, "").chatReady());

        check.fails("plain http refused", new Check.Body() {
            public void run() {
                Config.blank().with(Config.CANVAS_URL, "http://canvas.asu.edu");
            }
        });
        check.fails("a path refused", new Check.Body() {
            public void run() {
                Config.blank().with(Config.CANVAS_URL, "https://canvas.asu.edu/courses");
            }
        });
        check.fails("cookie url must end in /cookie", new Check.Body() {
            public void run() {
                Config.blank().with(Config.COOKIE_URL, "https://edit.example.org/api/");
            }
        });
        check.fails("short token refused", new Check.Body() {
            public void run() {
                Config.blank().with(Config.COOKIE_TOKEN, "short");
            }
        });
        check.fails("key with a space refused", new Check.Body() {
            public void run() {
                Config.blank().with(Config.API_KEY, "sk abc");
            }
        });
        check.fails("bad provider refused", new Check.Body() {
            public void run() {
                Config.blank().with(Config.PROVIDER, "unknown");
            }
        });
        check.fails("bad timezone refused", new Check.Body() {
            public void run() {
                Config.blank().with(Config.TIMEZONE, "Mars/Olympus");
            }
        });
        check.same("timezone is used", "America/Phoenix",
            Config.blank().with(Config.TIMEZONE, "America/Phoenix").zone().getId());
    }

    private static void origins(final Check check) {
        final URI base = URI.create("https://canvas.asu.edu/");
        check.run("pagination guard", new Check.Body() {
            public void run() throws Exception {
                Canvas.validatePage(base, URI.create("https://canvas.asu.edu/api/v1/courses?page=2"));
            }
        });
        check.fails("another host refused", new Check.Body() {
            public void run() throws Exception {
                Canvas.validatePage(base, URI.create("https://evil.example.com/api/v1/courses"));
            }
        });
        check.fails("plain http refused", new Check.Body() {
            public void run() throws Exception {
                Canvas.validatePage(base, URI.create("http://canvas.asu.edu/api/v1/courses"));
            }
        });
        check.fails("outside the api refused", new Check.Body() {
            public void run() throws Exception {
                Canvas.validatePage(base, URI.create("https://canvas.asu.edu/login"));
            }
        });
        check.fails("credentials in the url refused", new Check.Body() {
            public void run() throws Exception {
                Canvas.validatePage(base, URI.create("https://a:b@canvas.asu.edu/api/v1/courses"));
            }
        });
        check.fails("escaped traversal refused", new Check.Body() {
            public void run() throws Exception {
                Canvas.validatePage(base, URI.create("https://canvas.asu.edu/api/v1/%2e%2e/login"));
            }
        });
        check.fails("unnormalized refused", new Check.Body() {
            public void run() throws Exception {
                Canvas.validatePage(base, URI.create("https://canvas.asu.edu/api/v1/../api/v1/courses"));
            }
        });
    }

    // ------------------------------------------------------------------

    private static final String COOKIE_BODY = "{\"cookie\":\"canvas_session=abc; _csrf=d\"}";

    private static void cookieService(Check check) throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong clock = new AtomicLong();
        TestServer server = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                calls.incrementAndGet();
                return TestServer.Reply.json(COOKIE_BODY);
            }
        });
        try {
            RemoteCookies cookies = new RemoteCookies(server.uri("/cookie"), TOKEN, new Http(),
                new LongSupplier() {
                    public long getAsLong() {
                        return clock.get();
                    }
                });
            check.same("fetches the cookie", "canvas_session=abc; _csrf=d", cookies.fetch());
            cookies.fetch();
            check.same("cached", 1, calls.get());
            check.same("token is sent", "Bearer " + TOKEN, server.seen.get(0).header("Authorization"));

            clock.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(14));
            cookies.fetch();
            check.same("still cached at 14 minutes", 1, calls.get());
            clock.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(16));
            cookies.fetch();
            check.same("refetched after 15 minutes", 2, calls.get());

            cookies.invalidate();
            cookies.fetch();
            check.same("invalidate forces a refetch", 3, calls.get());
        } finally {
            server.close();
        }

        check.fails("a rejected token says so", "rejected its token",
            fetching(TestServer.Reply.of(401, "application/json", "{}")));
        check.fails("not ready says so", "not ready",
            fetching(TestServer.Reply.of(503, "application/json", "{}")));
        check.fails("other statuses say no Canvas request went out", "no Canvas request",
            fetching(TestServer.Reply.of(500, "application/json", "{}")));
        check.fails("html is refused", "something else",
            fetching(TestServer.Reply.of(200, "text/html", "<html>login</html>")));
        check.fails("a cookie with no session is refused", "invalid Canvas cookie",
            fetching(TestServer.Reply.json("{\"cookie\":\"other=1\"}")));
        check.fails("a malformed cookie is refused", "invalid Canvas cookie",
            fetching(TestServer.Reply.json("{\"cookie\":\"nonsense\"}")));
    }

    private static Check.Body fetching(final TestServer.Reply reply) {
        return new Check.Body() {
            public void run() throws Exception {
                TestServer server = new TestServer(new TestServer.Handler() {
                    public TestServer.Reply handle(TestServer.Request request) {
                        return reply;
                    }
                });
                try {
                    new RemoteCookies(server.uri("/cookie"), TOKEN, new Http(), nowNanos()).fetch();
                } finally {
                    server.close();
                }
            }
        };
    }

    private static LongSupplier nowNanos() {
        return new LongSupplier() {
            public long getAsLong() {
                return System.nanoTime();
            }
        };
    }

    /**
     * A Canvas pointed at a test server, with sleeping turned off.
     *
     * Public because the data tests need one too, and the constructors that
     * take an injected clock and origin are package-private on purpose: the app
     * only ever builds a Canvas from a validated Config.
     */
    public static Canvas canvasFor(URI base, URI cookieUrl) {
        return new Canvas(base, new RemoteCookies(cookieUrl, TOKEN, new Http(), nowNanos()),
            new Http(), new Canvas.Sleeper() {
                public void sleep(long milliseconds) {
                    // The retry schedule is under test, not the wall clock.
                }
            });
    }

    private static Canvas canvas(TestServer server) {
        return canvasFor(server.uri("/"), server.uri("/cookie"));
    }

    private static void paging(Check check) throws Exception {
        final AtomicInteger pages = new AtomicInteger();
        TestServer server = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
                if (request.path.contains("page=2")) {
                    pages.incrementAndGet();
                    return TestServer.Reply.json("[{\"id\":2,\"name\":\"Chem\",\"secret\":\"drop me\"}]");
                }
                pages.incrementAndGet();
                return TestServer.Reply.json("[{\"id\":1,\"name\":\"Bio\"}]")
                    .header("Link: <" + request.path.split("\\?")[0] + "?page=2>; rel=\"next\"");
            }
        });
        try {
            List<Object> courses = canvas(server).courses(true);
            check.same("both pages", 2, courses.size());
            check.same("followed the Link header", 2, pages.get());
            check.same("first page", "Bio", Json.object(courses.get(0)).get("name"));
            check.ok("unlisted fields are dropped", !Json.object(courses.get(1)).containsKey("secret"));
            check.ok("only active enrolments asked for", server.seen.get(1).path.contains("enrollment_state=active"));
            check.ok("the cookie is sent to canvas",
                server.seen.get(1).header("Cookie").contains("canvas_session"));
        } finally {
            server.close();
        }

        TestServer cycle = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
                return TestServer.Reply.json("[]").header("Link: <" + request.path + ">; rel=\"next\"");
            }
        });
        try {
            final Canvas client = canvas(cycle);
            check.fails("a Link cycle is caught", "cycle", new Check.Body() {
                public void run() throws Exception {
                    client.courses(true);
                }
            });
        } finally {
            cycle.close();
        }

        TestServer away = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
                return TestServer.Reply.json("[]").header("Link: <https://evil.example.com/api/v1/x>; rel=\"next\"");
            }
        });
        try {
            final Canvas client = canvas(away);
            check.fails("a Link off-origin is refused", "outside the configured", new Check.Body() {
                public void run() throws Exception {
                    client.courses(true);
                }
            });
        } finally {
            away.close();
        }
    }

    private static void auth(Check check) throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        TestServer recovering = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
                if (attempts.incrementAndGet() == 1) {
                    return TestServer.Reply.of(401, "application/json", "{\"status\":\"unauthenticated\"}");
                }
                return TestServer.Reply.json("[{\"id\":1,\"name\":\"Bio\"}]");
            }
        });
        try {
            check.same("a stale cookie is retried through", 1, canvas(recovering).courses(true).size());
            check.same("it took two attempts", 2, attempts.get());
        } finally {
            recovering.close();
        }

        TestServer expired = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
                return TestServer.Reply.of(401, "application/json", "{}");
            }
        });
        try {
            final Canvas client = canvas(expired);
            check.fails("it gives up rather than hanging", "would not accept the session", new Check.Body() {
                public void run() throws Exception {
                    client.courses(true);
                }
            });
        } finally {
            expired.close();
        }

        TestServer login = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
                return TestServer.Reply.of(200, "text/html", "<html>Log in</html>");
            }
        });
        try {
            final Canvas client = canvas(login);
            check.fails("a login page is not data", "login page", new Check.Body() {
                public void run() throws Exception {
                    client.courses(true);
                }
            });
        } finally {
            login.close();
        }

        TestServer forbidden = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
                return TestServer.Reply.of(403, "application/json", "{\"secret\":\"do not echo me\"}");
            }
        });
        try {
            final Canvas client = canvas(forbidden);
            check.fails("403 is explained without its body", "not permitted", new Check.Body() {
                public void run() throws Exception {
                    client.courses(true);
                }
            });
        } finally {
            forbidden.close();
        }
    }

    private static void due(Check check) throws Exception {
        TestServer server = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
                if (request.path.contains("/courses/1/assignments")) {
                    return TestServer.Reply.json("["
                        + "{\"id\":11,\"name\":\"Late lab\",\"due_at\":\"2026-09-02T06:59:00Z\","
                        + "\"submission\":{\"workflow_state\":\"unsubmitted\",\"missing\":true}},"
                        + "{\"id\":12,\"name\":\"Handed in\",\"due_at\":\"2026-09-03T06:59:00Z\","
                        + "\"submission\":{\"workflow_state\":\"submitted\",\"submitted_at\":\"2026-09-01T00:00:00Z\"}},"
                        + "{\"id\":13,\"name\":\"Personal date\",\"due_at\":\"2026-09-30T06:59:00Z\","
                        + "\"submission\":{\"cached_due_date\":\"2026-09-04T06:59:00Z\"}},"
                        + "{\"id\":14,\"name\":\"No date\"}]");
                }
                if (request.path.contains("/courses/2/assignments")) {
                    return TestServer.Reply.of(403, "application/json", "{}");
                }
                return TestServer.Reply.json("[{\"id\":1,\"name\":\"Bio\"},{\"id\":2,\"name\":\"Locked\"}]");
            }
        });
        try {
            Map<String, Object> result = Json.object(canvas(server).due(
                java.time.Instant.parse("2026-09-01T00:00:00Z"),
                java.time.Instant.parse("2026-09-10T00:00:00Z"), false));
            List<Object> found = Json.children(result, "assignments");
            check.same("undated and completed work is left out", 2, found.size());
            check.same("sorted by the date that applies", "Late lab",
                Json.object(found.get(0)).get("name"));
            check.same("a personal due date is used", "2026-09-04T06:59:00Z",
                Json.object(found.get(1)).get("effective_due_at"));
            check.same("the course is named", "Bio", Json.object(found.get(0)).get("course_name"));
            check.ok("an unreadable course is reported", !Json.flag(result, "complete"));
            check.same("and named", 1, Json.children(result, "unavailable_courses").size());

            Map<String, Object> withDone = Json.object(canvas(server).due(
                java.time.Instant.parse("2026-09-01T00:00:00Z"),
                java.time.Instant.parse("2026-09-10T00:00:00Z"), true));
            check.same("completed work can be included", 3, Json.children(withDone, "assignments").size());
        } finally {
            server.close();
        }
    }

    // ------------------------------------------------------------------

    private static void tools(Check check) throws Exception {
        Tools tools = new Tools(null);
        check.ok("calculate needs no network",
            tools.call("calculate_decimal", "{\"expression\":\"2+2\"}").contains("\"4\""));
        Tools summerClock = new Tools(
            null,
            ZoneId.of("America/New_York"),
            Clock.fixed(Instant.parse("2026-07-10T06:59:00Z"), ZoneOffset.UTC)
        );
        Map<String, Object> current = Json.object(Json.parse(
            summerClock.call("get_current_time", "{}")
        ));
        check.same("current time uses the user's local zone",
            "2026-07-10T02:59-04:00[America/New_York]", current.get("current_time"));
        check.same("current time names the user's zone",
            "America/New_York", current.get("timezone"));
        check.same("current time includes its UTC offset", "-04:00", current.get("utc_offset"));
        check.ok("the time tool refuses arguments",
            summerClock.call("get_current_time", "{\"timezone\":\"UTC\"}").contains("error"));
        check.ok("an unknown tool is refused",
            tools.call("delete_everything", "{}").contains("error"));
        check.ok("extra fields are refused",
            tools.call("calculate", "{\"expression\":\"1\",\"extra\":1}").contains("error"));
        check.ok("missing fields are refused",
            tools.call("list_courses", "{}").contains("error"));
        check.ok("a wrong type is refused",
            tools.call("list_courses", "{\"active_only\":\"yes\"}").contains("error"));
        check.ok("bad arithmetic is an error not a crash",
            tools.call("calculate_decimal", "{\"expression\":\"2+\"}").contains("error"));
        check.ok("a bad timestamp is refused",
            tools.call("due_assignments",
                "{\"from\":\"soon\",\"to\":\"later\",\"include_completed\":false}").contains("error"));

        check.ok("chat output is not disguised as a tool",
            tools.call("send_user_message", "{\"message\":\"On it.\"}").contains("recognize"));

        Map<String, Object> localized = Json.object(Tools.localize(Json.map(
            "due_at", "2026-09-10T06:59:00Z",
            "submission", Json.map("submitted_at", "2026-09-08T01:02:03Z"),
            "description", "Meet at 2026-09-10T06:59:00Z in the example"
        ), ZoneId.of("America/Phoenix")));
        check.same("due dates become student-local times",
            "2026-09-09T23:59-07:00[America/Phoenix]", localized.get("due_at"));
        check.same("nested timestamps become student-local times",
            "2026-09-07T18:02:03-07:00[America/Phoenix]",
            Json.child(localized, "submission").get("submitted_at"));
        check.same("timestamp examples inside prose stay untouched",
            "Meet at 2026-09-10T06:59:00Z in the example", localized.get("description"));

        Map<String, Object> winter = Json.object(Tools.localize(
            Json.map("due_at", "2026-01-10T06:59:00Z"),
            ZoneId.of("America/New_York")
        ));
        Map<String, Object> summer = Json.object(Tools.localize(
            Json.map("due_at", "2026-07-10T06:59:00Z"),
            ZoneId.of("America/New_York")
        ));
        check.ok("localization observes standard time",
            String.valueOf(winter.get("due_at")).contains("-05:00[America/New_York]"));
        check.ok("localization observes daylight time",
            String.valueOf(summer.get("due_at")).contains("-04:00[America/New_York]"));

        String prompt = Model.instructions(
            Config.blank().with(Config.TIMEZONE, "America/Phoenix")
        );
        check.ok("the model is told tool timestamps are already local",
            prompt.contains("already been converted to this user timezone"));
        check.ok("the model is told not to convert tool timestamps again",
            prompt.contains("do not convert them again"));

        check.same("every tool is declared", 16, Tools.DEFINITIONS.size());
        for (Object definition : Tools.DEFINITIONS) {
            Map<String, Object> tool = Json.object(definition);
            Map<String, Object> parameters = Json.child(tool, "parameters");
            check.ok(tool.get("name") + " is strict", Boolean.TRUE.equals(tool.get("strict")));
            check.ok(tool.get("name") + " forbids extra fields",
                Boolean.FALSE.equals(parameters.get("additionalProperties")));
            check.same(tool.get("name") + " requires all of its fields",
                Json.child(parameters, "properties").size(), Json.children(parameters, "required").size());
        }

        Map<String, Object> args = Json.object(Json.parse("{\"a\":12,\"b\":0,\"c\":\"x\",\"d\":1.5}"));
        check.same("an id passes through", "12", Tools.id(args, "a"));
        check.fails("zero is not an id", new Check.Body() {
            public void run() {
                Tools.id(Json.object(Json.parse("{\"a\":0}")), "a");
            }
        });
        check.fails("a word is not an id", new Check.Body() {
            public void run() {
                Tools.id(Json.object(Json.parse("{\"a\":\"1\"}")), "a");
            }
        });
        check.fails("a fraction is not an id", new Check.Body() {
            public void run() {
                Tools.id(Json.object(Json.parse("{\"a\":1.5}")), "a");
            }
        });
    }

    // ------------------------------------------------------------------

    private static String sse(String... events) {
        StringBuilder out = new StringBuilder();
        for (String event : events) out.append("data: ").append(event).append("\n\n");
        return out.toString();
    }

    private static void events(Check check) throws Exception {
        String done = sse("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
            + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Two things.\"}]}]}}");
        Map<String, Object> response = Model.parseEvents(done);
        check.same("a completed response is read", 1, Json.children(response, "output").size());

        final StringBuilder streamed = new StringBuilder();
        Agent.Activity collector = new Agent.Activity() {
            public void text(String text) {
                streamed.append(text);
            }

            public void reasoning(String text) {}

            public void note(String line) {}
        };
        String deltas = sse(
            "{\"type\":\"response.output_text.delta\",\"delta\":\"Lab 3 \"}",
            "{\"type\":\"response.output_text.delta\",\"delta\":\"is due Thu.\"}",
            "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}");
        Map<String, Object> rebuilt = Model.parseEvents(deltas, collector);
        check.same("deltas reach the screen", "Lab 3 is due Thu.", streamed.toString());
        Map<String, Object> message = Json.object(Json.children(rebuilt, "output").get(0));
        check.same("an empty output is rebuilt from the stream", "Lab 3 is due Thu.",
            Json.object(Json.children(message, "content").get(0)).get("text"));

        String commentary = sse(
            "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"msg_1\","
                + "\"type\":\"message\",\"role\":\"assistant\",\"phase\":\"commentary\",\"content\":[]}}",
            "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\",\"delta\":\"I will check.\"}",
            "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}");
        Map<String, Object> rebuiltCommentary = Json.object(
            Json.children(Model.parseEvents(commentary), "output").get(0)
        );
        check.same("a streamed message keeps its phase", "commentary",
            rebuiltCommentary.get("phase"));

        String call = sse(
            "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\","
                + "\"name\":\"calculate\",\"call_id\":\"c1\",\"arguments\":\"\"}}",
            "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"delta\":\"{\\\"expr\"}",
            "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\","
                + "\"arguments\":\"{\\\"expression\\\":\\\"2+2\\\"}\"}",
            "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}");
        Map<String, Object> withCall = Json.object(Json.children(Model.parseEvents(call), "output").get(0));
        check.same("a streamed call is rebuilt", "calculate", withCall.get("name"));
        check.same("with its finished arguments", "{\"expression\":\"2+2\"}", withCall.get("arguments"));

        String noArgumentCall = sse(
            "{\"type\":\"response.output_item.added\",\"item\":{\"id\":\"fc_time\","
                + "\"type\":\"function_call\",\"name\":\"get_current_time\","
                + "\"call_id\":\"time_1\",\"arguments\":\"\"}}",
            "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}"
        );
        Map<String, Object> noArgument = Json.object(
            Json.children(Model.parseEvents(noArgumentCall), "output").get(0)
        );
        check.same("an empty streamed argument object is completed", "{}",
            noArgument.get("arguments"));

        check.fails("an error event fails the turn", "failed or was cut short", new Check.Body() {
            public void run() throws Exception {
                Model.parseEvents(sse("{\"type\":\"response.failed\"}"));
            }
        });
        check.fails("a truncated stream fails the turn", "ended early", new Check.Body() {
            public void run() throws Exception {
                Model.parseEvents(sse("{\"type\":\"response.output_text.delta\",\"delta\":\"half\"}"));
            }
        });
        check.fails("an incomplete response is refused", new Check.Body() {
            public void run() throws Exception {
                Model.parseEvents(sse("{\"type\":\"response.completed\",\"response\":"
                    + "{\"status\":\"in_progress\",\"output\":[]}}"));
            }
        });
    }

    private static void request(Check check) throws Exception {
        TestServer server = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                return TestServer.Reply.of(200, "text/event-stream",
                    sse("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":"
                        + "[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}}"));
            }
        });
        try {
            Config config = Config.blank().with(Config.MODEL, "gpt-5-codex").with(Config.API_KEY, "sk-test");
            Model model = new Model(config, new Http(),
                new Credentials.ApiKey("sk-test"), server.uri("/v1/responses"));
            List<Object> history = new ArrayList<>();
            history.add(Json.map("role", "user", "content", "what is due"));
            Map<String, Object> response = model.respond(history, Agent.Activity.NOOP);
            check.same("a complete reply comes back", 1, Json.children(response, "output").size());

            TestServer.Request sent = server.seen.get(0);
            check.same("it is a POST", "POST", sent.method);
            check.same("the key is sent", "Bearer sk-test", sent.header("Authorization"));
            check.ok("streaming is asked for", sent.header("Accept").contains("event-stream"));
            check.ok("compression is off so deltas arrive", sent.header("Accept-Encoding").contains("identity"));
            Map<String, Object> body = Json.object(Json.parse(sent.body));
            check.same("the model is named", "gpt-5-codex", body.get("model"));
            check.same("nothing is stored server side", Boolean.FALSE, body.get("store"));
            check.same("every tool is offered", 16, Json.children(body, "tools").size());
            check.ok("the instructions have no message length limit",
                !String.valueOf(body.get("instructions")).contains("280 characters"));
            check.ok("OpenAI receives the app system prompt",
                String.valueOf(body.get("instructions")).contains("You are Sage"));
            check.ok("OpenAI is not given a provider identity prompt",
                !String.valueOf(body.get("instructions")).contains("official CLI"));
            check.ok("no account header without codex", sent.header("chatgpt-account-id").isEmpty());

            // The ChatGPT backend needs its own headers, and needs them not to
            // be sent to the plain API above, where they mean nothing.
            Config codex = Config.blank()
                .with(Config.PROVIDER, "codex")
                .with(Config.MODEL, "gpt-5-codex")
                .with(Config.ACCESS_TOKEN, "codex-token")
                .with(Config.ACCOUNT_ID, "acct-1");
            new Model(codex, new Http(),
                new CodexAuth(new Http(), server.uri("/oauth/token"), "codex-token", null, "acct-1", null),
                server.uri("/v1/responses")).respond(history, Agent.Activity.NOOP);
            TestServer.Request second = server.seen.get(1);
            check.same("the codex token is sent", "Bearer codex-token", second.header("Authorization"));
            check.same("with its account", "acct-1", second.header("chatgpt-account-id"));
            check.ok("and its beta header", second.header("OpenAI-Beta").contains("responses"));
            check.ok("a session id is sent", second.header("session-id").length() > 0);
            Map<String, Object> codexBody = Json.object(Json.parse(second.body));
            check.ok("Codex receives the app system prompt",
                String.valueOf(codexBody.get("instructions")).contains("You are Sage"));
        } finally {
            server.close();
        }
    }

    // ------------------------------------------------------------------

    /** A JWT with the given expiry and account id. Only the payload matters here. */
    private static String jwt(long expSeconds, String account) {
        String payload = "{\"exp\":" + expSeconds
            + ",\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"" + account + "\"}}";
        String encoded;
        try {
            encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return "header." + encoded + ".signature";
    }

    private static void codexLogin(Check check) throws Exception {
        long soon = System.currentTimeMillis() / 1000 + 30;      // inside the renewal margin
        long later = System.currentTimeMillis() / 1000 + 86400;

        check.same("the account id is read out of the token", "acct-9",
            CodexAuth.accountOf(jwt(later, "acct-9")));
        check.same("an unreadable token never expires", Long.MAX_VALUE,
            CodexAuth.expiryOf("not-a-jwt"));
        check.ok("a readable one does", CodexAuth.expiryOf(jwt(later, "a")) < Long.MAX_VALUE);

        final AtomicInteger refreshes = new AtomicInteger();
        final List<String> sentRefreshTokens = new ArrayList<>();
        final String fresh = jwt(later, "acct-from-token");
        TestServer server = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) throws Exception {
                refreshes.incrementAndGet();
                for (String pair : request.body.split("&")) {
                    if (pair.startsWith("refresh_token=")) {
                        sentRefreshTokens.add(java.net.URLDecoder.decode(pair.substring(14), "UTF-8"));
                    }
                }
                return TestServer.Reply.json("{\"access_token\":\"" + fresh
                    + "\",\"refresh_token\":\"rotated-2\",\"expires_in\":86400}");
            }
        });
        try {
            final List<String> stored = new ArrayList<>();
            CodexAuth.Rotated recorder = new CodexAuth.Rotated() {
                public void store(String access, String refresh, String account) {
                    stored.add(refresh + "|" + account);
                }
            };

            // An access token still well inside its life is used as it is.
            CodexAuth valid = new CodexAuth(new Http(), server.uri("/oauth/token"),
                jwt(later, "acct-1"), "refresh-1", "acct-1", recorder);
            check.same("a live token is used unchanged", jwt(later, "acct-1"), valid.token());
            check.same("with no round trip", 0, refreshes.get());

            // One about to lapse is renewed before it is handed out.
            CodexAuth expiring = new CodexAuth(new Http(), server.uri("/oauth/token"),
                jwt(soon, "acct-1"), "refresh-1", "acct-1", recorder);
            check.same("a token about to lapse is renewed first", fresh, expiring.token());
            check.same("one round trip", 1, refreshes.get());
            check.same("the refresh token was sent", "refresh-1", sentRefreshTokens.get(0));
            check.same("the rotated token is reported for saving", "rotated-2|acct-from-token",
                stored.get(0));
            check.same("the account id comes from the new token", "acct-from-token",
                expiring.account());
            expiring.token();
            check.same("and the renewed token is then reused", 1, refreshes.get());

            // The rotation must be used next time, not the token it replaced.
            expiring.invalidate();
            expiring.token();
            check.same("renewing again uses the rotated token", "rotated-2", sentRefreshTokens.get(1));

            // With only a refresh token, everything is obtained from it.
            CodexAuth bare = new CodexAuth(new Http(), server.uri("/oauth/token"),
                null, "refresh-only", null, recorder);
            check.same("a refresh token alone is enough", fresh, bare.token());
            check.same("including the account", "acct-from-token", bare.account());

            // With no refresh token there is nothing to renew with.
            CodexAuth stuck = new CodexAuth(new Http(), server.uri("/oauth/token"),
                jwt(soon, "acct-1"), null, "acct-1", recorder);
            check.same("a lapsing token with no way to renew is still sent",
                jwt(soon, "acct-1"), stuck.token());
            stuck.invalidate();
            check.same("and invalidating it does not throw it away",
                jwt(soon, "acct-1"), stuck.token());
        } finally {
            server.close();
        }

        TestServer spent = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                return TestServer.Reply.of(400, "application/json",
                    "{\"error\":\"invalid_grant\"}");
            }
        });
        try {
            final CodexAuth dead = new CodexAuth(new Http(), spent.uri("/oauth/token"),
                null, "spent", null, null);
            check.fails("a spent refresh token says what to do", "Sign in with codex", new Check.Body() {
                public void run() throws Exception {
                    dead.token();
                }
            });
        } finally {
            spent.close();
        }

        // A 401 from the model renews once and asks again.
        final AtomicInteger models = new AtomicInteger();
        final AtomicInteger renewals = new AtomicInteger();
        TestServer both = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                if (request.path.contains("oauth")) {
                    renewals.incrementAndGet();
                    return TestServer.Reply.json("{\"access_token\":\"" + jwt(later, "acct-2")
                        + "\",\"refresh_token\":\"r2\",\"expires_in\":86400}");
                }
                if (models.incrementAndGet() == 1) {
                    return TestServer.Reply.of(401, "application/json", "{\"error\":\"expired\"}");
                }
                return TestServer.Reply.of(200, "text/event-stream",
                    sse("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                        + "\"output\":[{\"type\":\"message\",\"content\":"
                        + "[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}}"));
            }
        });
        try {
            Config codex = Config.blank().with(Config.PROVIDER, "codex").with(Config.MODEL, "m");
            CodexAuth auth = new CodexAuth(new Http(), both.uri("/oauth/token"),
                jwt(later, "acct-2"), "r1", "acct-2", null);
            Model model = new Model(codex, new Http(), auth, both.uri("/v1/responses"));
            List<Object> history = new ArrayList<>();
            history.add(Json.map("role", "user", "content", "hi"));
            model.respond(history, Agent.Activity.NOOP);
            check.same("a refused token is renewed and the question asked again", 2, models.get());
            check.same("renewing happened once", 1, renewals.get());
        } finally {
            both.close();
        }

        TestServer always401 = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                if (request.path.contains("oauth")) {
                    return TestServer.Reply.json("{\"access_token\":\"" + jwt(later, "a")
                        + "\",\"refresh_token\":\"r\",\"expires_in\":86400}");
                }
                return TestServer.Reply.of(401, "application/json", "{}");
            }
        });
        try {
            Config codex = Config.blank().with(Config.PROVIDER, "codex").with(Config.MODEL, "m");
            final Model model = new Model(codex, new Http(),
                new CodexAuth(new Http(), always401.uri("/oauth/token"), jwt(later, "a"), "r", "a", null),
                always401.uri("/v1/responses"));
            final List<Object> history = new ArrayList<>();
            history.add(Json.map("role", "user", "content", "hi"));
            check.fails("a second refusal is reported rather than looped", "401", new Check.Body() {
                public void run() throws Exception {
                    model.respond(history, Agent.Activity.NOOP);
                }
            });
        } finally {
            always401.close();
        }
    }

    private static void claudeLogin(Check check) throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        TestServer server = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                requests.incrementAndGet();
                Map<String, Object> body = Json.object(Json.parse(request.body));
                check.same("claude token request has client id",
                    "9d1c250a-e61b-44d9-88ed-5944d1962f5e", body.get("client_id"));
                check.ok("claude refresh omits scope", !body.containsKey("scope"));
                return TestServer.Reply.json(
                    "{\"access_token\":\"sk-ant-oat-new\",\"refresh_token\":\"refresh-new\",\"expires_in\":3600}"
                );
            }
        });
        try {
            final List<String> stored = new ArrayList<>();
            ClaudeAuth auth = new ClaudeAuth(new Http(), server.uri("/v1/oauth/token"),
                "old", "refresh-old", 1, new ClaudeAuth.Rotated() {
                    public void store(String access, String refresh, long expiresAt) {
                        stored.add(access + "|" + refresh);
                    }
                });
            check.same("expired Claude token is renewed", "sk-ant-oat-new", auth.token());
            check.same("Claude token rotation is stored", "sk-ant-oat-new|refresh-new", stored.get(0));
            check.same("one Claude refresh request", 1, requests.get());
            auth.invalidate();
            auth.token();
            check.same("a rejected Claude token forces refresh", 2, requests.get());

            ClaudeAuth.Login login = ClaudeAuth.begin();
            check.ok("Claude login opens the authorize endpoint",
                login.url().startsWith("https://claude.ai/oauth/authorize?"));
            String state = query(login.url(), "state");
            ClaudeAuth.exchange(new Http(), server.uri("/v1/oauth/token"), login,
                "http://localhost:53692/callback?code=from-browser&state=" + state, null);
            check.same("authorization code was exchanged", 3, requests.get());
            Map<String, Object> exchange = Json.object(Json.parse(server.seen.get(2).body));
            check.same("exchange uses the browser code", "from-browser", exchange.get("code"));
            check.same("exchange uses PKCE", state, exchange.get("code_verifier"));
        } finally {
            server.close();
        }
    }

    private static String query(String url, String wanted) throws Exception {
        String raw = URI.create(url).getRawQuery();
        for (String pair : raw.split("&")) {
            String[] halves = pair.split("=", 2);
            if (wanted.equals(java.net.URLDecoder.decode(halves[0], "UTF-8"))) {
                return java.net.URLDecoder.decode(halves[1], "UTF-8");
            }
        }
        return "";
    }

    private static void claudeModel(Check check) throws Exception {
        TestServer server = new TestServer(new TestServer.Handler() {
            public TestServer.Reply handle(TestServer.Request request) {
                return TestServer.Reply.json("{\"type\":\"message\",\"role\":\"assistant\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}");
            }
        });
        try {
            Config config = Config.blank().with(Config.PROVIDER, "claude")
                .with(Config.MODEL, "claude-sonnet-4-6")
                .with(Config.ACCESS_TOKEN, "sk-ant-oat-test");
            ClaudeAuth auth = new ClaudeAuth(new Http(), server.uri("/oauth/token"),
                "sk-ant-oat-test", null, 0, null);
            ClaudeModel model = new ClaudeModel(config, new Http(), auth, server.uri("/v1/messages"));
            List<Object> history = new ArrayList<>();
            history.add(Json.map("role", "user", "content", "what is due"));
            history.add(Json.map("type", "function_call", "call_id", "toolu_1",
                "name", "show_my_courses", "arguments", "{\"active_only\":true}"));
            history.add(Json.map("type", "function_call_output", "call_id", "toolu_1",
                "output", "{\"courses\":[]}"));
            Map<String, Object> response = model.respond(history, Agent.Activity.NOOP);
            check.same("Claude reply becomes an agent message", 1,
                Json.children(response, "output").size());

            TestServer.Request sent = server.seen.get(0);
            check.same("Claude OAuth uses bearer auth", "Bearer sk-ant-oat-test",
                sent.header("Authorization"));
            check.ok("Claude OAuth beta headers are sent",
                sent.header("anthropic-beta").contains("oauth-2025-04-20"));
            check.ok("Claude Code identifies itself", sent.header("user-agent").startsWith("claude-cli/"));
            Map<String, Object> body = Json.object(Json.parse(sent.body));
            check.same("Claude model is selected", "claude-sonnet-4-6", body.get("model"));
            check.same("all tools are offered to Claude", 16, Json.children(body, "tools").size());
            Map<String, Object> firstTool = Json.object(Json.children(body, "tools").get(0));
            check.ok("Claude tools use input_schema", firstTool.containsKey("input_schema"));
            List<Object> system = Json.children(body, "system");
            check.same("Claude receives identity then app system prompt", 2, system.size());
            check.same("Claude OAuth identifies the official client in its payload",
                "You are Claude Code, Anthropic's official CLI for Claude.",
                Json.object(system.get(0)).get("text"));
            check.ok("Claude system prompt is Sage",
                String.valueOf(Json.object(system.get(1)).get("text")).contains("You are Sage"));
            check.same("Claude identity cache is extended", "1h", Json.child(
                Json.object(system.get(0)), "cache_control").get("ttl"));
            check.same("Claude system cache is extended", "1h", Json.child(
                Json.object(system.get(1)), "cache_control").get("ttl"));
            List<Object> offered = Json.children(body, "tools");
            check.same("Claude tool cache is extended", "1h", Json.child(
                Json.object(offered.get(offered.size() - 1)), "cache_control").get("ttl"));
            List<Object> messages = Json.children(body, "messages");
            check.same("Claude history alternates into three messages", 3, messages.size());
            Map<String, Object> assistant = Json.object(messages.get(1));
            check.same("Responses calls become Claude tool use", "tool_use",
                Json.object(Json.children(assistant, "content").get(0)).get("type"));
            Map<String, Object> lastMessage = Json.object(messages.get(messages.size() - 1));
            List<Object> lastContent = Json.children(lastMessage, "content");
            check.same("Claude conversation cache is extended", "1h", Json.child(
                Json.object(lastContent.get(lastContent.size() - 1)), "cache_control").get("ttl"));
        } finally {
            server.close();
        }

        String stream = sse(
            "{\"type\":\"message_start\",\"message\":{}}",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"calculate_decimal\",\"input\":{}}}",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                + "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"expression\\\":\\\"2+2\\\"}\"}}",
            "{\"type\":\"content_block_stop\",\"index\":0}",
            "{\"type\":\"message_stop\"}"
        );
        Map<String, Object> streamed = ClaudeModel.parseEvents(
            new java.io.ByteArrayInputStream(stream.getBytes("UTF-8")), Agent.Activity.NOOP);
        Map<String, Object> call = Json.object(Json.children(streamed, "output").get(0));
        check.same("Claude streamed tool call is converted", "calculate_decimal", call.get("name"));
        check.same("Claude streamed arguments are complete", "{\"expression\":\"2+2\"}",
            call.get("arguments"));

        String noArgumentStream = sse(
            "{\"type\":\"message_start\",\"message\":{}}",
            "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_time\","
                + "\"name\":\"get_current_time\",\"input\":{}}}",
            "{\"type\":\"content_block_stop\",\"index\":0}",
            "{\"type\":\"message_stop\"}"
        );
        Map<String, Object> noArgument = Json.object(Json.children(
            ClaudeModel.parseEvents(
                new java.io.ByteArrayInputStream(noArgumentStream.getBytes("UTF-8")),
                Agent.Activity.NOOP
            ),
            "output"
        ).get(0));
        check.same("Claude no-argument calls contain an empty object", "{}",
            noArgument.get("arguments"));
    }

    // ------------------------------------------------------------------

    /** A model that replays prepared rounds, so the loop can be tested on its own. */
    private static final class Replay implements Agent.Responder {

        private final List<String> rounds = new ArrayList<>();
        private int at;
        List<Object> lastInput;
        boolean fail;

        Replay(String... responses) {
            for (String response : responses) rounds.add(response);
        }

        public Map<String, Object> respond(List<Object> input, Agent.Activity activity) throws IOException {
            lastInput = new ArrayList<>(input);
            if (fail) throw new IOException("network is down");
            if (at >= rounds.size()) throw new IOException("asked for more rounds than were prepared");
            return Json.object(Json.parse(rounds.get(at++)));
        }
    }

    private static String answer(String text) {
        return "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":"
            + "[{\"type\":\"output_text\",\"text\":\"" + text + "\"}]}]}";
    }

    private static String emptyAnswer() {
        return "{\"status\":\"completed\",\"output\":[]}";
    }

    private static String phasedAnswer(String phase, String text) {
        return "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"phase\":\""
            + phase + "\",\"content\":[{\"type\":\"output_text\",\"text\":\"" + text
            + "\"}]}]}";
    }

    private static String toolCall(String name, String arguments) {
        return "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"id\":\"fc\","
            + "\"call_id\":\"c1\",\"name\":\"" + name + "\",\"arguments\":\"" + arguments + "\"}]}";
    }

    private static void agent(Check check) throws Exception {
        Replay replay = new Replay(
            toolCall("calculate_decimal", "{\\\"expression\\\":\\\"84*0.7\\\"}"),
            answer("You need 104."));
        final List<String> notes = new ArrayList<>();
        Agent.Activity activity = new Agent.Activity() {
            public void text(String text) {}

            public void reasoning(String text) {}

            public void note(String line) {
                notes.add(line);
            }
        };
        Agent agent = new Agent(replay, new Tools(null), 2);
        check.same("ordinary assistant output is the answer", "You need 104.",
            agent.chat("what do I need", activity));
        check.ok("progress is reported", notes.contains("Working it out"));

        Map<String, Object> output = Json.object(replay.lastInput.get(2));
        check.same("the tool result is fed back", "function_call_output", output.get("type"));
        check.ok("with the computed value", String.valueOf(output.get("output")).contains("58.8"));

        Replay noArguments = new Replay(
            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\","
                + "\"id\":\"fc_time\",\"call_id\":\"time_1\","
                + "\"name\":\"get_current_time\"}]}",
            answer("It is 2:59 AM.")
        );
        Tools fixedTime = new Tools(
            null,
            ZoneId.of("America/New_York"),
            Clock.fixed(Instant.parse("2026-07-10T06:59:00Z"), ZoneOffset.UTC)
        );
        check.same("a provider may omit arguments for the time tool", "It is 2:59 AM.",
            new Agent(noArguments, fixedTime, 1).chat("what time is it", Agent.Activity.NOOP));
        check.same("an omitted argument object is normalized in history", "{}",
            Json.object(noArguments.lastInput.get(1)).get("arguments"));
        check.ok("the no-argument tool result is fed back",
            String.valueOf(Json.object(noArguments.lastInput.get(2)).get("output"))
                .contains("America/New_York"));

        Replay phased = new Replay(
            phasedAnswer("commentary", "I will check that."),
            phasedAnswer("final_answer", "It is finished."));
        Agent.Activity phasedActivity = new Agent.Activity() {
            public void text(String text) {}

            public void reasoning(String text) {}

            public void note(String line) {}
        };
        check.same("commentary does not end the loop", "It is finished.",
            new Agent(phased, new Tools(null), 1).chat("please do it", phasedActivity));
        check.same("commentary is replayed for the next round", "commentary",
            Json.object(phased.lastInput.get(1)).get("phase"));

        final List<String> separated = new ArrayList<>();
        Agent.Responder streamedRounds = new Agent.Responder() {
            private int round;

            public Map<String, Object> respond(List<Object> input, Agent.Activity activity) {
                String text = round++ == 0 ? "Sentence 1." : "Sentence 2.";
                activity.text(text);
                return Json.object(Json.parse(round == 1
                    ? phasedAnswer("commentary", text)
                    : phasedAnswer("final_answer", text)));
            }
        };
        Agent.Activity separatedActivity = new Agent.Activity() {
            public void text(String text) {
                separated.add(text);
            }

            public void reasoning(String text) {}

            public void note(String line) {}
        };
        new Agent(streamedRounds, new Tools(null), 1)
            .chat("please continue", separatedActivity);
        check.same("a later streamed message starts a new paragraph", "\n\nSentence 2.",
            separated.get(1));

        final List<String> visible = new ArrayList<>();
        Agent.Responder padded = new Agent.Responder() {
            public Map<String, Object> respond(List<Object> input, Agent.Activity activity) {
                activity.text(" \n");
                activity.text("\t  Answer");
                activity.text(" with spaces. \n");
                return Json.map(
                    "status", "completed",
                    "output", Json.list(Json.map(
                        "type", "message",
                        "content", Json.list(Json.map(
                            "type", "output_text",
                            "text", " \n\t  Answer with spaces. \n"
                        ))
                    ))
                );
            }
        };
        Agent.Activity visibleActivity = new Agent.Activity() {
            public void text(String text) {
                visible.add(text);
            }

            public void reasoning(String text) {}

            public void note(String line) {}
        };
        check.same("leading whitespace is absent from the completed answer",
            "Answer with spaces. \n",
            new Agent(padded, new Tools(null), 1).chat("answer", visibleActivity));
        check.same("leading whitespace-only stream deltas are ignored", 2, visible.size());
        check.same("leading whitespace is removed from the first visible delta",
            "Answer", visible.get(0));
        check.same("whitespace is preserved after streamed text begins",
            " with spaces. \n", visible.get(1));

        check.fails("a truly empty turn is still refused", "no answer", new Check.Body() {
            public void run() throws Exception {
                new Agent(new Replay(emptyAnswer()), new Tools(null), 1)
                    .chat("say something", Agent.Activity.NOOP);
            }
        });

        replay.rounds.add(answer("Second answer."));
        agent.chat("and now", Agent.Activity.NOOP);
        check.ok("history carries between turns", replay.lastInput.size() > 4);

        int before = replay.lastInput.size();
        replay.fail = true;
        check.fails("a failed turn is reported", new Check.Body() {
            public void run() throws Exception {
                new Agent(new Replay(), new Tools(null), 1).chat("x", Agent.Activity.NOOP);
            }
        });
        try {
            agent.chat("this one breaks", Agent.Activity.NOOP);
        } catch (IOException expected) {
            // The point is what is left behind, below.
        }
        replay.fail = false;
        replay.rounds.add(answer("Third answer."));
        agent.chat("after the failure", Agent.Activity.NOOP);
        check.same("a failed turn leaves no half-turn behind", before + 2, replay.lastInput.size());

        agent.reset();
        replay.rounds.add(answer("Fresh."));
        agent.chat("start over", Agent.Activity.NOOP);
        check.same("reset clears the history", 1, replay.lastInput.size());

        Replay seedReplay = new Replay(answer("Sure."));
        Agent restored = new Agent(seedReplay, new Tools(null), 1);
        restored.seed(true, "what is due");
        restored.seed(false, "Lab 3 on Thursday.");
        restored.chat("and after that", Agent.Activity.NOOP);
        check.same("seeded turns come back as history", 3, seedReplay.lastInput.size());
        check.same("in the right role", "assistant",
            Json.object(seedReplay.lastInput.get(1)).get("role"));

        Replay compactReplay = new Replay(
            answer("The user needs Lab 3 by Thursday."),
            answer("The original history is intact."),
            answer("Start with Lab 3."));
        Agent compacted = new Agent(compactReplay, new Tools(null), 1);
        compacted.seed(true, "what is due");
        compacted.seed(false, "Lab 3 on Thursday.");
        String summary = compacted.summarize(Agent.Activity.NOOP);
        check.same("compaction returns the summary",
            "The user needs Lab 3 by Thursday.", summary);
        check.same("the summary request sees the old conversation", 3,
            compactReplay.lastInput.size());
        check.ok("the compaction prompt asks for continuity",
            String.valueOf(Json.object(compactReplay.lastInput.get(2)).get("content"))
                .contains("continue naturally"));

        compacted.chat("do you still have the original", Agent.Activity.NOOP);
        check.same("generating a summary does not install it", 3,
            compactReplay.lastInput.size());
        check.same("the original assistant turn remains", "assistant",
            Json.object(compactReplay.lastInput.get(1)).get("role"));

        compacted.reset();
        compacted.seedCompaction(summary);
        compacted.chat("what should I start with", Agent.Activity.NOOP);
        check.same("only summary context and the new question remain", 2,
            compactReplay.lastInput.size());
        check.ok("the synthetic context contains the summary",
            String.valueOf(Json.object(compactReplay.lastInput.get(0)).get("content"))
                .contains("Lab 3 by Thursday"));
        check.same("the synthetic summary is a user context message", "user",
            Json.object(compactReplay.lastInput.get(0)).get("role"));

        final Agent[] replaced = new Agent[1];
        final List<List<Object>> replacementInputs = new ArrayList<>();
        Agent.Responder obsolete = new Agent.Responder() {
            private int call;

            public Map<String, Object> respond(List<Object> input, Agent.Activity activity) {
                if (call++ == 0) {
                    replaced[0].reset();
                    replaced[0].seed(true, "kept request");
                    return Json.object(Json.parse(answer("obsolete answer")));
                }
                replacementInputs.add(new ArrayList<>(input));
                return Json.object(Json.parse(answer("new answer")));
            }
        };
        replaced[0] = new Agent(obsolete, new Tools(null), 1);
        replaced[0].chat("discarded request", Agent.Activity.NOOP);
        replaced[0].chat("next request", Agent.Activity.NOOP);
        check.same("a replaced history is not overwritten by an obsolete turn", 2,
            replacementInputs.get(0).size());
        check.same("the replacement remains first", "kept request",
            Json.object(replacementInputs.get(0).get(0)).get("content"));

        check.fails("an enormous message is refused", "too long", new Check.Body() {
            public void run() throws Exception {
                StringBuilder huge = new StringBuilder();
                for (int i = 0; i < 100001; i++) huge.append('x');
                new Agent(new Replay(), new Tools(null), 1).chat(huge.toString(), Agent.Activity.NOOP);
            }
        });
    }
}
