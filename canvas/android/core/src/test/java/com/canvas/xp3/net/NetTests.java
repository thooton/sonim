package com.canvas.xp3.net;

import com.xp3.common.Check;
import com.xp3.common.Http;
import com.xp3.common.Json;
import com.xp3.common.TestServer;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Tests for everything under net, in its package so it can reach inside. */
public final class NetTests {

    private NetTests() {}

    public static void run(final Check check) {
        check.run("json", () -> json(check));
        check.run("cookies", () -> cookies(check));
        check.run("config", () -> config(check));
        check.run("origins", () -> origins(check));
        check.run("cookie service", () -> cookieService(check));
        check.run("canvas paging", () -> paging(check));
        check.run("canvas auth", () -> auth(check));
        check.run("canvas ids", () -> ids(check));
        check.run("canvas due", () -> due(check));
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

        check.fails("duplicate keys refused", () -> {
            Json.parse("{\"a\":1,\"a\":2}");
        });
        check.fails("trailing content refused", () -> {
            Json.parse("{} x");
        });
        check.fails("raw control character refused", () -> {
            Json.parse("\"a\nb\"");
        });
        check.fails("unterminated string refused", () -> {
            Json.parse("\"abc");
        });
        check.fails("leading zero refused", () -> {
            Json.parse("01");
        });
        check.fails("bad escape refused", () -> {
            Json.parse("\"\\q\"");
        });
        check.fails("non-finite refused", () -> Json.write(Double.valueOf(Double.NaN)));
        check.fails("unsupported type refused", () -> Json.write(new Object()));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200; i++) deep.append('[');
        final String nested = deep.toString();
        check.fails("depth limit", () -> Json.parse(nested));
    }

    private static void cookies(Check check) {
        check.same("plain pair", "a=b; c=d", Cookies.parse("  a=b; c=d "));
        check.same("header prefix stripped", "a=b", Cookies.parse("Cookie: a=b"));
        check.fails("empty refused", () -> Cookies.parse(""));
        check.fails("valueless refused", () -> {
            Cookies.parse("novalue");
        });
        check.fails("newline refused", () -> {
            Cookies.parse("a=b\nc=d");
        });
    }

    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static void config(Check check) {
        Config blank = Config.blank();
        check.ok("blank is not ready", !blank.canvasReady());

        Config canvas = blank
            .with(Config.CANVAS_URL, "https://canvas.asu.edu")
            .with(Config.COOKIE_URL, "https://edit.example.org/api/canvas-cookie/cookie")
            .with(Config.COOKIE_TOKEN, TOKEN);
        check.ok("canvas alone is ready for lists", canvas.canvasReady());
        check.same("trailing slash added", "https://canvas.asu.edu/", canvas.canvasBase().toString());
        check.same("stored without the slash", "https://canvas.asu.edu", canvas.get(Config.CANVAS_URL));

        Config again = Config.parse(canvas.toJson());
        check.ok("survives a round trip", again.canvasReady());
        check.same("round trip keeps the token", TOKEN, again.cookieToken());
        check.same("a field can be cleared", "", canvas.with(Config.COOKIE_TOKEN, "").get(Config.COOKIE_TOKEN));
        check.ok("clearing the token stops syncing", !canvas.with(Config.COOKIE_TOKEN, "").canvasReady());

        Config old = Config.parse(
            "{\"canvas\":{\"baseUrl\":\"https://canvas.asu.edu\"},"
            + "\"openai\":{\"provider\":\"claude\",\"refreshToken\":\"r\"}}");
        check.same("an old model login is ignored", "https://canvas.asu.edu", old.get(Config.CANVAS_URL));
        check.ok("and not written back", !old.toJson().contains("openai"));

        check.fails("plain http refused", () -> {
            Config.blank().with(Config.CANVAS_URL, "http://canvas.asu.edu");
        });
        check.fails("a path refused", () -> {
            Config.blank().with(Config.CANVAS_URL, "https://canvas.asu.edu/courses");
        });
        check.fails("cookie url must end in /cookie", () -> {
            Config.blank().with(Config.COOKIE_URL, "https://edit.example.org/api/");
        });
        check.fails("short token refused", () -> {
            Config.blank().with(Config.COOKIE_TOKEN, "short");
        });
        check.fails("bad timezone refused", () -> {
            Config.blank().with(Config.TIMEZONE, "Mars/Olympus");
        });
        check.same("timezone is used", "America/Phoenix",
            Config.blank().with(Config.TIMEZONE, "America/Phoenix").zone().getId());
    }

    private static void origins(final Check check) {
        final URI base = URI.create("https://canvas.asu.edu/");
        check.run("pagination guard", () -> {
            Canvas.validatePage(base, URI.create("https://canvas.asu.edu/api/v1/courses?page=2"));
        });
        check.fails("another host refused", () -> {
            Canvas.validatePage(base, URI.create("https://evil.example.com/api/v1/courses"));
        });
        check.fails("plain http refused", () -> {
            Canvas.validatePage(base, URI.create("http://canvas.asu.edu/api/v1/courses"));
        });
        check.fails("outside the api refused", () -> {
            Canvas.validatePage(base, URI.create("https://canvas.asu.edu/login"));
        });
        check.fails("credentials in the url refused", () -> {
            Canvas.validatePage(base, URI.create("https://a:b@canvas.asu.edu/api/v1/courses"));
        });
        check.fails("escaped traversal refused", () -> {
            Canvas.validatePage(base, URI.create("https://canvas.asu.edu/api/v1/%2e%2e/login"));
        });
        check.fails("unnormalized refused", () -> {
            Canvas.validatePage(base, URI.create("https://canvas.asu.edu/api/v1/../api/v1/courses"));
        });
    }

    // ------------------------------------------------------------------

    private static final String COOKIE_BODY = "{\"cookie\":\"canvas_session=abc; _csrf=d\"}";

    private static void cookieService(Check check) throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong clock = new AtomicLong();
        TestServer server = new TestServer(request -> {
            calls.incrementAndGet();
            return TestServer.Reply.json(COOKIE_BODY);
        });
        try {
            RemoteCookies cookies = new RemoteCookies(server.uri("/cookie"), TOKEN, new Http(),
                () -> clock.get());
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
        return () -> {
            TestServer server = new TestServer(request -> {
                return reply;
            });
            try {
                new RemoteCookies(server.uri("/cookie"), TOKEN, new Http(), nowNanos()).fetch();
            } finally {
                server.close();
            }
        };
    }

    private static LongSupplier nowNanos() {
        return () -> System.nanoTime();
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
            new Http(), milliseconds -> {
                // The retry schedule is under test, not the wall clock.
            });
    }

    private static Canvas canvas(TestServer server) {
        return canvasFor(server.uri("/"), server.uri("/cookie"));
    }

    private static void paging(Check check) throws Exception {
        final AtomicInteger pages = new AtomicInteger();
        TestServer server = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
            if (request.path.contains("page=2")) {
                pages.incrementAndGet();
                return TestServer.Reply.json("[{\"id\":2,\"name\":\"Chem\",\"secret\":\"drop me\"}]");
            }
            pages.incrementAndGet();
            return TestServer.Reply.json("[{\"id\":1,\"name\":\"Bio\"}]")
                .header("Link: <" + request.path.split("\\?")[0] + "?page=2>; rel=\"next\"");
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

        TestServer cycle = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
            return TestServer.Reply.json("[]").header("Link: <" + request.path + ">; rel=\"next\"");
        });
        try {
            final Canvas client = canvas(cycle);
            check.fails("a Link cycle is caught", "cycle", () -> client.courses(true));
        } finally {
            cycle.close();
        }

        TestServer away = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
            return TestServer.Reply.json("[]").header("Link: <https://evil.example.com/api/v1/x>; rel=\"next\"");
        });
        try {
            final Canvas client = canvas(away);
            check.fails("a Link off-origin is refused", "outside the configured", () -> client.courses(true));
        } finally {
            away.close();
        }
    }

    private static void auth(Check check) throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        TestServer recovering = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
            if (attempts.incrementAndGet() == 1) {
                return TestServer.Reply.of(401, "application/json", "{\"status\":\"unauthenticated\"}");
            }
            return TestServer.Reply.json("[{\"id\":1,\"name\":\"Bio\"}]");
        });
        try {
            check.same("a stale cookie is retried through", 1, canvas(recovering).courses(true).size());
            check.same("it took two attempts", 2, attempts.get());
        } finally {
            recovering.close();
        }

        TestServer expired = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
            return TestServer.Reply.of(401, "application/json", "{}");
        });
        try {
            final Canvas client = canvas(expired);
            check.fails("it gives up rather than hanging", "would not accept the session", () -> {
                client.courses(true);
            });
        } finally {
            expired.close();
        }

        TestServer login = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
            return TestServer.Reply.of(200, "text/html", "<html>Log in</html>");
        });
        try {
            final Canvas client = canvas(login);
            check.fails("a login page is not data", "login page", () -> client.courses(true));
        } finally {
            login.close();
        }

        TestServer forbidden = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE_BODY);
            return TestServer.Reply.of(403, "application/json", "{\"secret\":\"do not echo me\"}");
        });
        try {
            final Canvas client = canvas(forbidden);
            check.fails("403 is explained without its body", "not permitted", () -> client.courses(true));
        } finally {
            forbidden.close();
        }
    }

    private static void due(Check check) throws Exception {
        TestServer server = new TestServer(request -> {
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

    private static void ids(Check check) {
        Map<String, Object> args = Json.object(Json.parse("{\"a\":12,\"b\":0,\"c\":\"x\",\"d\":1.5}"));
        check.same("an id passes through", "12", Canvas.id(args, "a"));
        check.fails("zero is not an id", () -> {
            Canvas.id(Json.object(Json.parse("{\"a\":0}")), "a");
        });
        check.fails("a word is not an id", () -> {
            Canvas.id(Json.object(Json.parse("{\"a\":\"1\"}")), "a");
        });
        check.fails("a fraction is not an id", () -> {
            Canvas.id(Json.object(Json.parse("{\"a\":1.5}")), "a");
        });
    }
}
