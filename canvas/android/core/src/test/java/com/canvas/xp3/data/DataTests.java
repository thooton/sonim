package com.canvas.xp3.data;

import com.xp3.common.Check;
import com.xp3.common.TestServer;
import com.canvas.xp3.net.Canvas;
import com.canvas.xp3.net.Config;
import com.xp3.common.Json;
import com.canvas.xp3.net.NetTests;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Tests for the model, the grouping rules, the HTML translation and the store. */
public final class DataTests {

    private DataTests() {}

    public static void run(final Check check) {
        check.run("dates", () -> dates(check));
        check.run("state", () -> state(check));
        check.run("sections", () -> sections(check));
        check.run("mapping", () -> mapping(check));
        check.run("html", () -> html(check));
        check.run("links", () -> links(check));
        check.run("store", () -> store(check));
        check.run("sync", () -> sync(check));
    }

    private static final long DAY = 24L * 60L * 60L * 1000L;

    /** Noon on a known Wednesday, so "this week" has room either side of it. */
    private static long wednesday() {
        Calendar calendar = Calendar.getInstance(Locale.US);
        calendar.set(2026, Calendar.SEPTEMBER, 9, 12, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static void dates(Check check) {
        long now = wednesday();
        Dates.setTwentyFourHour(false);
        check.same("weeks are calendar weeks", 1, Dates.weeksBetween(now, now + 5 * DAY));
        check.same("a fortnight is not next week", 2, Dates.weeksBetween(now, now + 13 * DAY));
        check.same("Sunday is still this week", 0, Dates.weeksBetween(now, now + 4 * DAY));
        check.same("Monday begins next week", 1, Dates.weeksBetween(now, now + 5 * DAY));
        check.same("days cross midnight not 24 hours", 1,
            Dates.daysBetween(now, now + 13 * 60 * 60 * 1000L));

        Dates.setTwentyFourHour(true);
        check.ok("a 24 hour phone gets 24 hour times", !Dates.time(now).contains("M"));
        Dates.setTwentyFourHour(false);
        check.ok("a 12 hour phone gets 12 hour times", Dates.time(now).contains("M"));
    }

    private static Assignment work(String id, Long due, boolean missing, boolean done, Double score) {
        return new Assignment("1", id, "Work " + id, "Bio", due, 10, missing, done,
            done ? Long.valueOf(0) : null, score, false);
    }

    private static void state(Check check) {
        long now = wednesday();
        check.same("future work is assigned", Assignment.ASSIGNED,
            work("1", Long.valueOf(now + DAY), false, false, null).state(now));
        check.same("past and unturned is missing", Assignment.MISSING,
            work("2", Long.valueOf(now - DAY), false, false, null).state(now));
        check.same("turned in is done", Assignment.DONE,
            work("3", Long.valueOf(now - DAY), false, true, null).state(now));
        check.same("undated work with no flag is assigned", Assignment.ASSIGNED,
            work("4", null, false, false, null).state(now));
        check.same("undated work can still be missing", Assignment.MISSING,
            work("5", null, true, false, null).state(now));

        check.same("a whole score reads whole", "9/10",
            work("8", null, false, true, Double.valueOf(9)).scoreLabel());
        check.same("a half mark is kept", "8.5/10",
            work("9", null, false, true, Double.valueOf(8.5)).scoreLabel());
        check.same("ungraded work says so", "Turned in",
            work("10", null, false, true, null).scoreLabel());
        check.same("nothing to say about unturned work", null,
            work("11", null, false, false, null).scoreLabel());
        check.same("excused is its own answer", "Excused",
            new Assignment("1", "12", "t", "Bio", null, 10, false, true, null, null, true).scoreLabel());
        check.same("floating point is not shown", "9",
            Assignment.number(9.000000000000002 - 0.000000000000002));
    }

    private static void sections(Check check) {
        long now = wednesday();
        Todos todos = new Todos();
        List<Assignment> all = new ArrayList<>();
        all.add(work("a", Long.valueOf(now + 2 * DAY), false, false, null));   // this week
        all.add(work("b", Long.valueOf(now + 5 * DAY), false, false, null));   // next week
        all.add(work("c", Long.valueOf(now + 20 * DAY), false, false, null));  // later
        all.add(work("d", null, false, false, null));                          // no due date
        all.add(work("e", Long.valueOf(now - DAY), false, false, null));       // missing this week
        all.add(work("f", Long.valueOf(now - 5 * DAY), false, false, null));   // missing last week
        all.add(work("g", Long.valueOf(now - DAY), false, true, Double.valueOf(9)));
        all.add(work("h", Long.valueOf(now + DAY), false, true, Double.valueOf(10)));
        all.add(work("i", Long.valueOf(now - 2 * DAY), false, true, null));
        todos.replace(all);

        List<Section> assigned = todos.sections(Assignment.ASSIGNED, now);
        check.same("assigned sections in order", "No due date", assigned.get(0).label);
        check.same("this week next", "This week", assigned.get(1).label);
        check.same("then next week", "Next week", assigned.get(2).label);
        check.same("then later", "Later", assigned.get(3).label);
        check.same("no empty sections", 4, assigned.size());

        List<Section> missing = todos.sections(Assignment.MISSING, now);
        check.same("missing has this week", "This week", missing.get(0).label);
        check.same("and last week", "Last week", missing.get(1).label);
        check.same("nothing else", 2, missing.size());

        List<Section> done = todos.sections(Assignment.DONE, now);
        check.same("returned work leads Done", "Returned", done.get(0).label);
        check.same("with both graded pieces", 2, done.get(0).items.size());
        check.same("then what waits for a grade", "Turned in", done.get(1).label);
        check.same("which is the ungraded one", "i", done.get(1).items.get(0).assignmentId);

        todos.check(java.util.Arrays.asList("a"));
        check.same("a planner check mark counts as done", Assignment.DONE, todos.state(all.get(0), now));
        check.ok("and leaves Assigned", !todos.inState(Assignment.ASSIGNED, now).contains(all.get(0)));
        todos.check(null);

        Todos empty = new Todos();
        empty.replace(new ArrayList<Assignment>());
        List<Section> emptyAssigned = empty.sections(Assignment.ASSIGNED, now);
        check.same("empty assigned keeps this week", 1, emptyAssigned.size());
        check.same("empty assigned section is this week", "This week", emptyAssigned.get(0).label);
        check.same("empty assigned section has a zero count", 0, emptyAssigned.get(0).items.size());

        check.same("lookup by id", "Work a", todos.byId("1:a").title);
        check.same("an unknown id is null", null, todos.byId("nope"));
    }

    private static Map<String, Object> record(String json) {
        return Json.object(Json.parse(json));
    }

    private static void mapping(Check check) {
        Assignment plain = Sync.map("7", "Bio", record("{\"id\":91,\"name\":\"Lab 3\","
            + "\"due_at\":\"2026-09-10T06:59:00Z\",\"points_possible\":20,\"html_url\":\"https://x/1\"}"));
        check.same("id pairs the course and the work", "7:91", plain.id);
        check.same("title", "Lab 3", plain.title);
        check.same("course", "Bio", plain.course);
        check.same("points", "20", Assignment.number(plain.points));
        check.ok("due date parsed", plain.due != null);
        check.ok("nothing is turned in", !plain.done && !plain.flaggedMissing);

        Assignment personal = Sync.map("7", "Bio", record("{\"id\":92,\"name\":\"Essay\","
            + "\"due_at\":\"2026-09-30T06:59:00Z\",\"submission\":{\"cached_due_date\":\"2026-09-11T06:59:00Z\"}}"));
        check.same("a personal due date wins", Sync.time("2026-09-11T06:59:00Z"), personal.due);

        Assignment noOverride = Sync.map("7", "Bio", record("{\"id\":93,\"name\":\"Essay\","
            + "\"due_at\":\"2026-09-30T06:59:00Z\",\"submission\":{\"cached_due_date\":null}}"));
        check.same("a null override falls back to the posted date",
            Sync.time("2026-09-30T06:59:00Z"), noOverride.due);

        Assignment gradedZero = Sync.map("7", "Bio", record("{\"id\":94,\"name\":\"Quiz\","
            + "\"submission\":{\"workflow_state\":\"graded\",\"score\":0,\"missing\":true}}"));
        check.ok("a graded zero on missing work is not done", !gradedZero.done);
        check.ok("it is missing", gradedZero.flaggedMissing);
        check.same("and keeps its score", Double.valueOf(0), gradedZero.score);

        Assignment onPaper = Sync.map("7", "Bio", record("{\"id\":95,\"name\":\"Midterm\","
            + "\"submission\":{\"workflow_state\":\"graded\",\"score\":88}}"));
        check.ok("work graded with no upload is still done", onPaper.done);

        Assignment excused = Sync.map("7", "Bio", record("{\"id\":96,\"name\":\"Skip\","
            + "\"submission\":{\"workflow_state\":\"unsubmitted\",\"excused\":true}}"));
        check.ok("excused work is done", excused.done);
        check.same("and says so", "Excused", excused.scoreLabel());

        Assignment submitted = Sync.map("7", "Bio", record("{\"id\":97,\"name\":\"Report\","
            + "\"submission\":{\"workflow_state\":\"submitted\",\"submitted_at\":\"2026-09-08T01:02:03Z\"}}"));
        check.ok("submitted is done", submitted.done);
        check.ok("with the time it went in", submitted.turnedIn != null);

        check.same("a draft is not shown", null,
            Sync.map("7", "Bio", record("{\"id\":98,\"name\":\"Draft\",\"published\":false}")));
        check.same("a record with no id is skipped", null,
            Sync.map("7", "Bio", record("{\"name\":\"Nameless\"}")));
        check.same("an untitled assignment still appears", "Untitled",
            Sync.map("7", "Bio", record("{\"id\":99}")).title);

        check.same("offsets are read too", Sync.time("2026-09-10T06:59:00Z"),
            Sync.time("2026-09-09T23:59:00-07:00"));
        check.same("nonsense is no date", null, Sync.time("next Thursday"));
        check.same("absent is no date", null, Sync.time(null));

        check.same("a course names itself", "Biology",
            Sync.courseName(record("{\"name\":\"Biology\",\"course_code\":\"BIO181\"}")));
        check.same("or falls back to its code", "BIO181",
            Sync.courseName(record("{\"course_code\":\"BIO181\"}")));
    }

    private static void html(Check check) {
        check.same("plain text passes through", "Read chapter 4.", Html.toMarkdown("Read chapter 4."));
        check.same("paragraphs are separated", "One.\n\nTwo.",
            Html.toMarkdown("<p>One.</p><p>Two.</p>"));
        check.same("headings", "## Instructions\n\nDo it.",
            Html.toMarkdown("<h2>Instructions</h2><p>Do it.</p>"));
        check.same("bullets", "- One\n- Two",
            Html.toMarkdown("<ul><li>One</li><li>Two</li></ul>"));
        check.same("numbers", "1. One\n2. Two",
            Html.toMarkdown("<ol><li>One</li><li>Two</li></ol>"));
        check.same("emphasis", "This is **important** and *this* too",
            Html.toMarkdown("<p>This is <strong>important</strong> and <em>this</em> too</p>"));
        check.same("links", "See [the rubric](https://x/r).",
            Html.toMarkdown("<p>See <a href=\"https://x/r\">the rubric</a>.</p>"));
        check.same("a bare link keeps its address", "[https://x/r](https://x/r)",
            Html.toMarkdown("<a href=\"https://x/r\"></a>"));
        check.same("a bracket in the label loses the link, not the words", "see [1] here",
            Html.toMarkdown("<a href=\"https://x/r\">see [1] here</a>"));
        check.same("brackets in the address are escaped", "[r](https://x/a%28b%29)",
            Html.toMarkdown("<a href=\"https://x/a(b)\">r</a>"));
        check.same("a link broken over a paragraph does not leak brackets", "One\n\nTwo",
            Html.toMarkdown("<a href=\"https://x/r\">One<p>Two</p></a>"));
        check.same("quotes", "> Cite your sources.",
            Html.toMarkdown("<blockquote><p>Cite your sources.</p></blockquote>"));
        check.same("code blocks", "```\nprint(1)\n```",
            Html.toMarkdown("<pre>print(1)</pre>"));
        check.same("inline code", "Call `main()` first.",
            Html.toMarkdown("<p>Call <code>main()</code> first.</p>"));
        check.same("rules", "---", Html.toMarkdown("<hr>"));
        check.same("breaks split lines", "One\nTwo", Html.toMarkdown("One<br>Two"));
        check.same("a break inside a bullet keeps the bullet", "- One Two",
            Html.toMarkdown("<ul><li>One<br>Two</li></ul>"));

        check.same("entities are decoded", "Tom & Jerry's \"book\"",
            Html.toMarkdown("Tom &amp; Jerry&apos;s &quot;book&quot;"));
        check.same("numeric entities too", "café", Html.toMarkdown("caf&#233;"));
        check.same("hex entities too", "café", Html.toMarkdown("caf&#xE9;"));
        check.same("an unknown entity is left alone", "&nope;", Html.toMarkdown("&nope;"));
        check.same("non breaking spaces collapse", "a b", Html.toMarkdown("a&nbsp;b"));

        check.same("scripts are dropped", "Safe.",
            Html.toMarkdown("<script>alert(1)</script><p>Safe.</p>"));
        check.same("styles are dropped", "Safe.",
            Html.toMarkdown("<style>p{color:red}</style><p>Safe.</p>"));
        check.same("javascript links are not links", "click",
            Html.toMarkdown("<a href=\"javascript:alert(1)\">click</a>"));
        check.same("images become their description", "(image: A diagram)",
            Html.toMarkdown("<img src=\"x.png\" alt=\"A diagram\">"));
        check.same("an image with no description is dropped", "", Html.toMarkdown("<img src=\"x.png\">"));

        check.same("tables degrade to rows", "- Week 1 · Chapter 1\n- Week 2 · Chapter 2",
            Html.toMarkdown("<table><tr><td>Week 1</td><td>Chapter 1</td></tr>"
                + "<tr><td>Week 2</td><td>Chapter 2</td></tr></table>"));
        check.same("empty emphasis is dropped", "Text",
            Html.toMarkdown("<p><strong></strong>Text</p>"));
        check.same("whitespace between tags collapses", "One Two",
            Html.toMarkdown("<p>One\n     \n  Two</p>"));
        check.same("nested lists flatten", "- One\n- Two",
            Html.toMarkdown("<ul><li>One<ul><li>Two</li></ul></li></ul>"));
        check.same("unknown tags are transparent", "Hello there",
            Html.toMarkdown("<span class=\"x\">Hello</span> <mark>there</mark>"));
        check.same("null is empty", "", Html.toMarkdown(null));
        check.same("an unclosed tag does not eat the text", "Hello", Html.toMarkdown("<p>Hello"));
        check.same("a stray angle bracket is a character", "a < b", Html.toMarkdown("a < b"));
    }

    private static void store(Check check) throws Exception {
        File directory = new File(System.getProperty("java.io.tmpdir"),
            "canvas-store-test-" + System.nanoTime());
        directory.mkdirs();
        try {
            Store store = new Store(directory);
            check.ok("an empty store has no settings", !store.settings().canvasReady());
            check.same("an empty store has no cache", null, store.cache());

            Config config = Config.blank()
                .with(Config.CANVAS_URL, "https://canvas.asu.edu")
                .with(Config.COOKIE_URL, "https://edit.example.org/api/canvas-cookie/cookie")
                .with(Config.COOKIE_TOKEN, "abcdefghijklmnopqrstuvwxyz0123456789");
            store.saveSettings(config);
            check.ok("settings come back", store.settings().canvasReady());
            check.same("with the token", "abcdefghijklmnopqrstuvwxyz0123456789",
                store.settings().get(Config.COOKIE_TOKEN));

            List<Assignment> work = new ArrayList<>();
            Assignment item = new Assignment("7", "91", "Lab 3", "Biology", Long.valueOf(1757000000000L),
                20, false, true, Long.valueOf(1756000000000L), Double.valueOf(18.5), false);
            work.add(item);
            work.add(new Assignment("7", "92", "Essay", "Biology", null, 0, true, false, null, null,
                false));
            store.saveCache(new Sync.Result(work, java.util.Arrays.asList("Locked course"), 1757000000123L));

            Sync.Result read = store.cache();
            check.same("both pieces of work come back", 2, read.work.size());
            check.same("when it was fetched", 1757000000123L, read.fetchedAt);
            check.ok("an unreadable course is remembered", !read.complete());
            Assignment first = read.work.get(0);
            check.same("title survives", "Lab 3", first.title);
            check.same("score survives", Double.valueOf(18.5), first.score);
            check.same("due date survives", Long.valueOf(1757000000000L), first.due);
            check.same("a null due date survives", null, read.work.get(1).due);
            check.ok("the missing flag survives", read.work.get(1).flaggedMissing);

            corrupt(directory, "assignments.json");
            check.same("a corrupt cache reads as none", null, store.cache());
            corrupt(directory, "settings.json");
            check.ok("corrupt settings read as unset", !store.settings().canvasReady());

            store.saveSettings(config);
            store.clear();
            check.ok("signing out removes the settings", !store.settings().canvasReady());

            // What a version with the chat left behind.
            write(new File(directory, "settings.json"),
                "{\"canvas\":{\"baseUrl\":\"https://canvas.asu.edu\"},"
                + "\"openai\":{\"provider\":\"claude\",\"refreshToken\":\"secret-refresh\"},"
                + "\"timezone\":\"America/Phoenix\"}");
            write(new File(directory, "chat.json"), "{\"turns\":[]}");
            write(new File(directory, "bundled-setup.txt"), "revision-1");
            store.forgetChat();
            check.ok("the old transcript is removed", !new File(directory, "chat.json").exists());
            check.ok("the old bundle marker is removed", !new File(directory, "bundled-setup.txt").exists());
            check.ok("the old model login is removed",
                !read(new File(directory, "settings.json")).contains("secret-refresh"));
            check.same("the Canvas address is kept", "https://canvas.asu.edu",
                store.settings().get(Config.CANVAS_URL));
            check.same("the time zone is kept", "America/Phoenix",
                store.settings().get(Config.TIMEZONE));
            corrupt(directory, "settings.json");
            store.forgetChat();
            check.ok("an unreadable settings file is left alone",
                read(new File(directory, "settings.json")).contains("truncated"));
        } finally {
            for (File file : directory.listFiles()) file.delete();
            directory.delete();
        }
    }

    private static void write(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        out.write(text.getBytes("UTF-8"));
        out.close();
    }

    private static String read(File file) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
    }

    private static void corrupt(File directory, String name) throws Exception {
        FileOutputStream out = new FileOutputStream(new File(directory, name));
        out.write("{\"work\": [ truncated".getBytes("UTF-8"));
        out.close();
    }

    private static void sync(Check check) throws Exception {
        TestServer server = new TestServer(request -> {
            if (request.path.startsWith("/cookie")) {
                return TestServer.Reply.json("{\"cookie\":\"canvas_session=abc\"}");
            }
            // One assignment is a longer path than the list, so it is
            // matched first: /courses/1/assignments/11 contains the other.
            if (request.path.contains("/assignments/11")) {
                return TestServer.Reply.json("{\"id\":11,\"name\":\"Lab 3\","
                    + "\"description\":\"<p>Read <strong>chapter 4</strong>.</p>\"}");
            }
            if (request.path.contains("/courses/1/assignments")) {
                return TestServer.Reply.json("["
                    + "{\"id\":11,\"name\":\"Lab 3\",\"due_at\":\"2026-09-10T06:59:00Z\","
                    + "\"points_possible\":20,\"submission\":{\"workflow_state\":\"unsubmitted\"}},"
                    + "{\"id\":12,\"name\":\"Draft\",\"published\":false}]");
            }
            if (request.path.contains("/courses/2/assignments")) {
                return TestServer.Reply.of(403, "application/json", "{}");
            }
            return TestServer.Reply.json("[{\"id\":1,\"name\":\"Bio\"},{\"id\":2,\"name\":\"Locked\"}]");
        });
        try {
            Canvas canvas = NetTests.canvasFor(server.uri("/"), server.uri("/cookie"));
            Sync sync = new Sync(canvas);
            Sync.Result result = sync.run();
            check.same("only the published work", 1, result.work.size());
            check.same("mapped", "Lab 3", result.work.get(0).title);
            check.same("with its course", "Bio", result.work.get(0).course);
            check.ok("the broken course did not empty the list", !result.complete());
            check.same("and is named", "Locked", result.unavailable.get(0));
            check.ok("the time is recorded", result.fetchedAt > 0);

            check.same("details arrive as markdown", "Read **chapter 4**.",
                sync.details(result.work.get(0)).body);
        } finally {
            server.close();
        }
    }

    private static void links(Check check) {
        java.net.URI canvas = java.net.URI.create("https://canvas.asu.edu");
        Link file = Link.of("/courses/12/files/345/download?wrap=1", canvas, canvas);
        check.same("relative canvas file", Link.FILE, file.kind);
        check.same("file id", "345", file.id);
        check.same("preview file", "77",
            Link.of("https://canvas.asu.edu/courses/12/files?preview=77", canvas, canvas).id);
        Link work = Link.of("https://canvas.asu.edu/courses/12/assignments/9", canvas, canvas);
        check.same("assignment", Link.ASSIGNMENT, work.kind);
        check.same("assignment class", "12", work.course);
        check.same("other canvas page", Link.CANVAS,
            Link.of("https://canvas.asu.edu/courses/12/pages/week-1", canvas, canvas).kind);
        check.same("mail is nothing", Link.NONE, Link.of("mailto:prof@asu.edu", canvas, canvas).kind);
        check.same("anchor is nothing", Link.NONE, Link.of("#top", canvas, canvas).kind);
        check.same("script is nothing", Link.NONE, Link.of("javascript:alert(1)", canvas, canvas).kind);
        check.same("credentials refused", Link.NONE, Link.of("https://a:b@example.com/x", canvas, canvas).kind);
        check.same("garbage is nothing", Link.NONE, Link.of("http://[bad", canvas, canvas).kind);

        Link web = Link.of("http://example.com/handout.pdf", canvas, canvas);
        check.same("web link", Link.WEB, web.kind);
        check.same("http fetched over https", "https://example.com/handout.pdf", web.fetch.toString());
        check.same("site", "example.com", Link.of("https://www.example.com/a", canvas, canvas).site());
        check.same("relative to a web page", "https://news.example/b/c.pdf",
            Link.of("c.pdf", java.net.URI.create("https://news.example/b/a.html"), canvas).fetch.toString());
        check.same("spaces survive", "https://example.com/my%20notes.pdf",
            Link.of("https://example.com/my notes.pdf", canvas, canvas).fetch.toString());
        check.same("no canvas set up: still the web", Link.WEB,
            Link.of("https://canvas.asu.edu/courses/1/files/2", null, null).kind);

        check.same("drive file", "https://drive.usercontent.google.com/download?id=1AbCdEfGhIjKlMnOp&export=download&confirm=t",
            Link.of("https://drive.google.com/file/d/1AbCdEfGhIjKlMnOp/view?usp=sharing", canvas, canvas).fetch.toString());
        check.same("drive open", "https://drive.usercontent.google.com/download?id=1AbCdEfGhIjKlMnOp&export=download&confirm=t",
            Link.of("https://drive.google.com/open?id=1AbCdEfGhIjKlMnOp", canvas, canvas).fetch.toString());
        check.same("google doc", "https://docs.google.com/document/d/1AbCdEfGhIjKlMnOp/export?format=pdf",
            Link.of("https://docs.google.com/document/d/1AbCdEfGhIjKlMnOp/edit?usp=sharing", canvas, canvas)
                .fetch.toString());
        check.same("google slides", "https://docs.google.com/presentation/d/1AbCdEfGhIjKlMnOp/export/pdf",
            Link.of("https://docs.google.com/presentation/u/1/d/1AbCdEfGhIjKlMnOp/edit#slide=id.p", canvas, canvas)
                .fetch.toString());
        check.same("google form left alone", "https://docs.google.com/forms/d/e/1AbCdEfGhIjKlMnOp/viewform",
            Link.of("https://docs.google.com/forms/d/e/1AbCdEfGhIjKlMnOp/viewform", canvas, canvas).fetch.toString());
        check.same("dropbox", "https://www.dropbox.com/scl/fi/abc/notes.pdf?rlkey=k&dl=1",
            Link.of("https://www.dropbox.com/scl/fi/abc/notes.pdf?rlkey=k&dl=0", canvas, canvas).fetch.toString());
    }
}
