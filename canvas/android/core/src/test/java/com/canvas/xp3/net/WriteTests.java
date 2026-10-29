package com.canvas.xp3.net;

import com.xp3.common.Check;
import com.xp3.common.TestServer;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The student's own writes, on the wire: each carries Canvas's CSRF token,
 * goes to its fixed route with a form or JSON body, and is never sent twice
 * when its connection fails. Uploads keep the cookie off other hosts.
 */
public final class WriteTests {

    private WriteTests() {}

    private static final String COOKIE = "{\"cookie\":\"_csrf_token=abc%2Bdef%3D%3D; canvas_session=s\"}";

    public static void run(final Check check) {
        check.run("canvas writes", () -> writes(check));
        check.run("canvas upload", () -> upload(check));
        check.run("canvas download", () -> download(check));
        check.run("canvas write guards", () -> guards(check));
        check.run("parallel sync", () -> parallel(check));
    }

    private static Canvas canvas(TestServer server) {
        return NetTests.canvasFor(server.uri("/"), server.uri("/cookie"));
    }

    private static void writes(Check check) throws Exception {
        TestServer server = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE);
            if (request.path.contains("/quizzes/5/submissions") && !request.path.contains("complete")) {
                return TestServer.Reply.json("{\"quiz_submissions\":[{\"id\":77,\"attempt\":1,"
                    + "\"validation_token\":\"tok\",\"workflow_state\":\"untaken\"}]}");
            }
            return TestServer.Reply.json("{\"id\":1}");
        });
        try {
            Canvas canvas = canvas(server);
            canvas.submitText("12", "34", "<p>Hi & bye</p>", "for you");
            TestServer.Request submit = server.seen.get(1);
            check.same("submit is a POST", "POST", submit.method);
            check.same("to the fixed route", "/api/v1/courses/12/assignments/34/submissions", submit.path);
            check.same("with the CSRF token decoded", "abc+def==", submit.header("X-CSRF-Token"));
            check.ok("as a form", submit.header("Content-Type").startsWith("application/x-www-form-urlencoded"));
            check.ok("typed work is a text entry",
                submit.body.contains("submission%5Bsubmission_type%5D=online_text_entry"));
            check.ok("the body is encoded", submit.body.contains("submission%5Bbody%5D=%3Cp%3EHi+%26+bye%3C%2Fp%3E"));
            check.ok("a note goes as a comment", submit.body.contains("comment%5Btext_comment%5D=for+you"));

            canvas.submitFiles("12", "34", Arrays.asList("901", "902"), null);
            String files = server.seen.get(server.seen.size() - 1).body;
            check.ok("files are named by id", files.contains("submission%5Bfile_ids%5D%5B%5D=901")
                && files.contains("submission%5Bfile_ids%5D%5B%5D=902"));

            canvas.comment("12", "34", "A question");
            TestServer.Request comment = server.seen.get(server.seen.size() - 1);
            check.same("a comment is a PUT on the student's own submission", "PUT", comment.method);
            check.same("route", "/api/v1/courses/12/assignments/34/submissions/self", comment.path);

            canvas.reply("12", "56", "<p>Yes</p>");
            check.same("a reply goes to the topic's entries", "/api/v1/courses/12/discussion_topics/56/entries",
                server.seen.get(server.seen.size() - 1).path);

            canvas.markDone("34", null, true);
            TestServer.Request mark = server.seen.get(server.seen.size() - 1);
            check.same("a new check mark is created", "/api/v1/planner/overrides", mark.path);
            check.ok("for this assignment", mark.body.contains("plannable_id=34")
                && mark.body.contains("marked_complete=true"));
            canvas.markDone("34", "8", false);
            check.same("an existing one is updated", "PUT", server.seen.get(server.seen.size() - 1).method);

            Map<String, Object> started = canvas.startQuiz("12", "5");
            check.same("starting a quiz returns the attempt", "tok", started.get("validation_token"));
            canvas.answer("77", 1, "tok", "300", Long.valueOf(4));
            TestServer.Request answer = server.seen.get(server.seen.size() - 1);
            check.same("answers go to the attempt", "/api/v1/quiz_submissions/77/questions", answer.path);
            check.ok("as JSON with the token", answer.header("Content-Type").startsWith("application/json")
                && answer.body.contains("\"validation_token\":\"tok\"") && answer.body.contains("\"answer\":4"));
            canvas.finishQuiz("12", "5", "77", 1, "tok");
            check.same("turning a quiz in", "/api/v1/courses/12/quizzes/5/submissions/77/complete",
                server.seen.get(server.seen.size() - 1).path);
        } finally {
            server.close();
        }
    }

    private static void upload(Check check) throws Exception {
        final List<String> storageCookies = new ArrayList<>();
        final TestServer storage = new TestServer(request -> {
            storageCookies.add(request.header("Cookie"));
            return TestServer.Reply.of(201, "application/json", "{\"id\":555}");
        });
        TestServer server = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE);
            return TestServer.Reply.json("{\"upload_url\":\"" + storage.uri("/up") + "\",\"file_param\":\"file\","
                + "\"upload_params\":{\"key\":\"k1\"}}");
        });
        try {
            Canvas canvas = canvas(server);
            final List<Long> progress = new ArrayList<>();
            // The test servers are http on loopback; the https rule is
            // checked separately below.
            try {
                canvas.upload("12", "34", "notes.txt", 5, "text/plain", new ByteArrayInputStream("hello".getBytes()),
                    (done, total) -> progress.add(Long.valueOf(done)));
                check.ok("an http storage host is refused", false);
            } catch (java.io.IOException e) {
                check.ok("an http storage host is refused", e.getMessage().contains("https"));
            }
            TestServer.Request ticket = server.seen.get(1);
            check.same("the ticket comes from the student's own files route",
                "/api/v1/courses/12/assignments/34/submissions/self/files", ticket.path);
            check.ok("it names the file and its size", ticket.body.contains("name=notes.txt")
                && ticket.body.contains("size=5"));
            check.same("nothing reached the storage host", 0, storageCookies.size());
        } finally {
            server.close();
            storage.close();
        }
    }

    private static void download(Check check) {
        URI base = URI.create("https://canvas.asu.edu/");
        check.ok("Canvas's download path gets the cookie",
            Canvas.canvasDownload(base, URI.create("https://canvas.asu.edu/files/12/download?verifier=x")));
        check.ok("so does a course file's",
            Canvas.canvasDownload(base, URI.create("https://canvas.asu.edu/courses/3/files/12/download")));
        check.ok("a storage host does not",
            !Canvas.canvasDownload(base, URI.create("https://inst-fs.example.net/files/12/download")));
        check.ok("nor another Canvas path",
            !Canvas.canvasDownload(base, URI.create("https://canvas.asu.edu/login")));
        check.ok("nor an escaped one",
            !Canvas.canvasDownload(base, URI.create("https://canvas.asu.edu/files/12/%2e%2e/download")));
    }

    private static void guards(Check check) throws Exception {
        final AtomicInteger posts = new AtomicInteger();
        TestServer dropping = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE);
            posts.incrementAndGet();
            throw new java.io.IOException("drop");
        });
        try {
            final Canvas canvas = canvas(dropping);
            check.fails("a failed write is reported", () -> canvas.submitUrl("12", "34", "https://x.org", null));
            check.same("and never sent a second time", 1, posts.get());
        } finally {
            dropping.close();
        }

        final AtomicInteger tries = new AtomicInteger();
        TestServer stale = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE);
            if (tries.incrementAndGet() == 1) return TestServer.Reply.of(401, "application/json", "{}");
            return TestServer.Reply.json("{\"id\":1}");
        });
        try {
            canvas(stale).comment("12", "34", "hi");
            check.same("a 401 write is retried, since Canvas did nothing", 2, tries.get());
        } finally {
            stale.close();
        }

        TestServer noToken = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json("{\"cookie\":\"canvas_session=s\"}");
            return TestServer.Reply.json("{}");
        });
        try {
            final Canvas canvas = canvas(noToken);
            check.fails("a session with no CSRF token can't write", "can read but not make changes",
                () -> canvas.comment("12", "34", "hi"));
            check.same("and nothing was sent to Canvas", 1, noToken.seen.size());
        } finally {
            noToken.close();
        }

        check.fails("a word is not an id", () -> Canvas.number("abc"));
        check.fails("a path is not an id", () -> Canvas.number("1/../2"));
        check.same("a number is", "42", Canvas.number("42"));
        check.same("forms are encoded", "a=b+c&d=%26", Canvas.form("a", "b c", "d", "&"));
        check.fails("a link must be a web address", () -> canvas(noToken).submitUrl("1", "2", "javascript:x", null));
    }

    private static void parallel(Check check) throws Exception {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        TestServer server = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) return TestServer.Reply.json(COOKIE);
            if (request.path.contains("/assignments")) {
                int now = inFlight.incrementAndGet();
                most.accumulateAndGet(now, Math::max);
                Thread.sleep(400);
                inFlight.decrementAndGet();
                if (request.path.contains("/courses/3/")) return TestServer.Reply.of(403, "application/json", "{}");
                return TestServer.Reply.json("[{\"id\":11,\"name\":\"Lab\"}]");
            }
            if (request.path.contains("planner")) return TestServer.Reply.json("[]");
            return TestServer.Reply.json("[{\"id\":1,\"name\":\"A\"},{\"id\":2,\"name\":\"B\"},"
                + "{\"id\":3,\"name\":\"C\"},{\"id\":4,\"name\":\"D\"}]");
        });
        try {
            long start = System.currentTimeMillis();
            com.canvas.xp3.data.Sync.Result result = new com.canvas.xp3.data.Sync(canvas(server)).run();
            long took = System.currentTimeMillis() - start;
            check.ok("classes are fetched at the same time", most.get() >= 3);
            check.ok("so four slow classes take about one's time", took < 1200);
            check.same("every readable class's work", 3, result.work.size());
            check.same("in the classes' order", "1", result.work.get(0).courseId);
            check.same("the unreadable one is named", "C", result.unavailable.get(0));
        } finally {
            server.close();
        }
    }
}
