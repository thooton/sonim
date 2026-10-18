package com.tablet.xp3;

import com.tablet.xp3.data.Doc;
import com.tablet.xp3.data.Job;
import com.tablet.xp3.data.Jobs;
import com.tablet.xp3.data.Library;
import com.tablet.xp3.data.Viewport;
import com.tablet.xp3.data.When;
import com.tablet.xp3.net.Center;
import com.xp3.common.Check;
import com.xp3.common.Json;
import com.xp3.common.TestServer;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Everything in :core, checked on a desktop JVM: the tablet's listing and
 * the transform jobs as the command center sends them, the client against a
 * local server, the wording of times, and the preview's zoom and pan.
 */
public final class SelfTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    /** 2026-10-17 15:00 UTC. */
    private static final long NOW = 1792249200000L;

    /** Two folders (one in the trash), and documents at the top, in a folder, in the trash and in a trashed folder. */
    static final String LISTING = "["
        + "{\"id\":\"f1\",\"name\":\"School\",\"type\":\"folder\",\"parent\":\"\",\"size\":0,\"modified\":\"2026-10-01T00:00:00Z\"},"
        + "{\"id\":\"f2\",\"name\":\"History 210\",\"type\":\"folder\",\"parent\":\"f1\",\"size\":0,\"modified\":\"2026-10-01T00:00:00Z\"},"
        + "{\"id\":\"f3\",\"name\":\"Old\",\"type\":\"folder\",\"parent\":\"trash\",\"size\":0,\"modified\":\"2026-10-01T00:00:00Z\"},"
        + "{\"id\":\"d1\",\"name\":\"Essay draft\",\"type\":\"notebook\",\"parent\":\"f2\",\"pages\":12,\"size\":1900000,\"modified\":\"2026-10-17T13:00:00Z\"},"
        + "{\"id\":\"d2\",\"name\":\"Lease agreement\",\"type\":\"pdf\",\"parent\":\"\",\"pages\":1,\"size\":640000,\"modified\":\"2026-10-09T10:00:00Z\"},"
        + "{\"id\":\"d3\",\"name\":\"Invisible Cities\",\"type\":\"epub\",\"parent\":\"\",\"size\":1100000,\"modified\":\"2026-10-16T09:00:00Z\"},"
        + "{\"id\":\"d4\",\"name\":\"Gone\",\"type\":\"pdf\",\"parent\":\"trash\",\"pages\":3,\"size\":1,\"modified\":\"2026-10-17T14:00:00Z\"},"
        + "{\"id\":\"d5\",\"name\":\"Inside old\",\"type\":\"pdf\",\"parent\":\"f3\",\"pages\":3,\"size\":1,\"modified\":\"2026-10-17T14:30:00Z\"}"
        + "]";

    static final String JOBS = "["
        + "{\"id\":\"t1\",\"document\":\"d1\",\"name\":\"Essay draft\",\"status\":\"running\",\"created\":\"2026-10-17T14:50:00Z\","
        + "\"updated\":\"2026-10-17T14:58:00Z\",\"title\":\"MLA paper\",\"progress\":\"Transcribing page 9 of 12\",\"percent\":58},"
        + "{\"id\":\"t2\",\"document\":\"d2\",\"name\":\"Lease agreement\",\"status\":\"done\",\"created\":\"2026-10-17T10:30:00Z\","
        + "\"updated\":\"2026-10-17T10:42:00Z\",\"title\":\"Lease summary\",\"percent\":100,\"result\":\"Lease summary\","
        + "\"tablet\":\"d9\",\"files\":[\"Lease summary.pdf\",\"Lease summary.docx\"]},"
        + "{\"id\":\"t3\",\"document\":\"d3\",\"name\":\"Invisible Cities\",\"status\":\"failed\",\"created\":\"2026-10-17T12:00:00Z\","
        + "\"updated\":\"2026-10-17T12:01:00Z\",\"percent\":0,\"error\":\"Couldn't start it: no account has room left\"},"
        + "{\"id\":\"t4\",\"document\":\"d2\",\"name\":\"Lease agreement\",\"status\":\"running\",\"created\":\"2026-10-17T14:59:00Z\",\"percent\":0}"
        + "]";

    public static void main(String[] args) {
        Check check = new Check();
        check.run("when", () -> when(check));
        check.run("library", () -> library(check));
        check.run("jobs", () -> jobs(check));
        check.run("client", () -> client(check));
        check.run("viewport", () -> viewport(check));
        if (!check.report()) System.exit(1);
    }

    private static void when(Check check) {
        check.same("seconds ago", "just now", When.ago(NOW - 20000, NOW, UTC));
        check.same("minutes", "5m ago", When.ago(NOW - 5 * 60000, NOW, UTC));
        check.same("hours today", "2h ago", When.ago(NOW - 2 * 3600000, NOW, UTC));
        check.same("yesterday", "yesterday", When.ago(NOW - 20 * 3600000L, NOW, UTC));
        check.same("this year", "Oct 9", When.ago(NOW - 8 * 24 * 3600000L, NOW, UTC));
        check.same("another year", "Oct 17, 2025", When.ago(NOW - 365 * 24 * 3600000L, NOW, UTC));
        check.same("24-hour clock", "14:05", When.clock(NOW - 55 * 60000, UTC, true));
        check.same("12-hour clock", "2:05", When.clock(NOW - 55 * 60000, UTC, false));
        check.same("noon on the 12-hour clock", "12:00", When.clock(NOW - 3 * 3600000, UTC, false));
        check.same("a day today is its time", "10:42", When.day(NOW - (4 * 60 + 18) * 60000L, NOW, UTC, true));
        check.same("a day before that", "Oct 9", When.day(NOW - 8 * 24 * 3600000L, NOW, UTC, true));
        check.same("in the phone's own zone", "11:00", When.clock(NOW, TimeZone.getTimeZone("America/New_York"), false));
    }

    private static void library(Check check) {
        Library lib = Library.parse(LISTING);
        check.same("documents, none from the trash", 3, lib.docs.size());
        check.same("newest first", "d1", lib.docs.get(0).id);
        check.same("then", "d3", lib.docs.get(1).id);
        Doc essay = lib.find("d1");
        check.same("folder path", "School / History 210", essay.folder);
        check.same("top", Library.TOP, lib.find("d2").folder);
        check.same("meta", "Notebook · 12 pages · edited 2h ago", essay.meta(NOW, UTC));
        check.same("one page", "PDF · 1 page · edited Oct 9", lib.find("d2").meta(NOW, UTC));
        check.same("an ePub without a page count", "ePub · edited yesterday", lib.find("d3").meta(NOW, UTC));
        check.same("size", "1.9 MB", essay.sizeText());
        check.same("small size", "640 KB", lib.find("d2").sizeText());
        check.same("folders to move to, top first, none from the trash", 3, lib.folders.size());
        check.same("top first", "", lib.folders.get(0).id);
        check.same("then by path", "School", lib.folders.get(1).path);
        check.same("nested", "School / History 210", lib.folders.get(2).path);
        check.same("index", 2, lib.indexOf("d2"));
        check.same("missing", -1, lib.indexOf("nope"));

        Library none = Library.parse("[]");
        check.ok("an empty tablet", none.docs.isEmpty());
        check.same("still has the top to move to", 1, none.folders.size());
        check.fails("not a listing", () -> Library.parse("{\"error\":\"x\"}"));

        // A folder that is its own parent doesn't hang the walk.
        Library loop = Library.parse("[{\"id\":\"a\",\"name\":\"A\",\"type\":\"folder\",\"parent\":\"a\"},"
            + "{\"id\":\"x\",\"name\":\"\",\"type\":\"notebook\",\"parent\":\"a\"}]");
        check.same("named anyway", "Untitled", loop.docs.get(0).name);
        check.ok("a looping path ends", loop.docs.get(0).folder.startsWith("A / A"));
    }

    private static void jobs(Check check) {
        Jobs jobs = Jobs.parse(JOBS);
        List<Job> list = jobs.ordered();
        check.same("two running", 2, jobs.active());
        check.same("running first, newest first", "t4", list.get(0).id);
        check.same("then the other running", "t1", list.get(1).id);
        check.same("then finished, newest first", "t3", list.get(2).id);
        check.same("the drawer's job is the newest running", "t4", jobs.running().id);

        Job essay = jobs.find("t1");
        check.same("the agent's title", "MLA paper", essay.heading());
        check.same("from", "From Essay draft", essay.from());
        check.same("summary", "Transcribing page 9 of 12 · 58%", essay.summary(NOW, UTC, true));
        Job fresh = jobs.find("t4");
        check.same("before the agent reports, the document", "Lease agreement", fresh.heading());
        check.same("and no from line", "", fresh.from());
        check.same("starting", "Starting · 0%", fresh.summary(NOW, UTC, true));
        Job done = jobs.find("t2");
        check.same("done", Job.DONE, done.status);
        check.same("done summary", "On your tablet since 10:42", done.summary(NOW, UTC, true));
        check.same("result on the tablet", "d9", done.tablet);
        check.same("both files", 2, done.files.size());
        Job failed = jobs.find("t3");
        check.same("failed says why", "Couldn't start it: no account has room left", failed.summary(NOW, UTC, true));
        check.same("state", "Failed", failed.stateLabel());

        check.same("running on a document", "t1", jobs.runningOn("d1").id);
        check.ok("nothing running on another", jobs.runningOn("d3") == null);

        Jobs fewer = jobs.without("t1");
        check.same("forgotten", 1, fewer.active());
        check.same("the original is untouched", 2, jobs.active());

        // The next listing: t1 finished.
        Jobs next = Jobs.parse(JOBS.replace("\"status\":\"running\",\"created\":\"2026-10-17T14:50:00Z\"",
            "\"status\":\"done\",\"created\":\"2026-10-17T14:50:00Z\""));
        List<Job> finished = next.finishedSince(jobs);
        check.same("one finished since", 1, finished.size());
        check.same("which", "t1", finished.get(0).id);
        check.ok("nothing finished against itself", next.finishedSince(next).isEmpty());
        check.ok("a job not seen before isn't news", next.finishedSince(Jobs.empty()).isEmpty());

        check.same("percent kept in range", 100, Jobs.parse("[{\"id\":\"x\",\"status\":\"running\",\"percent\":250}]").find("x").percent);
        check.fails("a job without an id", () -> Jobs.parse("[{\"status\":\"running\"}]"));
    }

    private static void client(Check check) throws Exception {
        final String[] lastBody = {""};
        TestServer server = new TestServer(request -> {
            if (!request.header("Authorization").equals("Bearer tok")) {
                return TestServer.Reply.of(401, "application/json", "{\"error\":\"unauthorized\"}");
            }
            String path = request.path;
            if (request.method.equals("GET") && path.equals("/remarkable")) return TestServer.Reply.json(LISTING);
            if (request.method.equals("GET") && path.equals("/transforms")) return TestServer.Reply.json(JOBS);
            if (request.method.equals("POST") && path.equals("/transforms")) {
                lastBody[0] = request.body;
                Map<String, Object> asked = Json.object(Json.parse(request.body));
                if ("d4".equals(asked.get("document"))) {
                    return TestServer.Reply.of(400, "application/json", "{\"error\":\"that document is in the trash\"}");
                }
                return TestServer.Reply.of(201, "application/json", "{\"id\":\"t9\",\"document\":\"" + asked.get("document")
                    + "\",\"name\":\"Essay draft\",\"status\":\"running\",\"created\":\"2026-10-17T15:00:00Z\",\"percent\":0}");
            }
            if (request.method.equals("DELETE") && path.equals("/transforms/t1")) return TestServer.Reply.json("{\"deleted\":\"t1\"}");
            if (request.method.equals("POST") && path.equals("/remarkable/d1")) {
                lastBody[0] = request.body;
                return TestServer.Reply.json("{}");
            }
            if (path.equals("/remarkable/d1/pages/1")) {
                return TestServer.Reply.of(200, "application/pdf", "%PDF-1.4 page one").header("X-Pages: 12");
            }
            if (path.equals("/remarkable/d1/pages/13")) {
                return TestServer.Reply.of(404, "application/json", "{\"error\":\"it has 12 pages\"}").header("X-Pages: 12");
            }
            if (path.equals("/remarkable/d3/pages/1")) {
                return TestServer.Reply.of(404, "application/json", "{\"error\":\"nothing is written on it\"}").header("X-Pages: 0");
            }
            return TestServer.Reply.of(404, "application/json", "{\"error\":\"no such document on the reMarkable\"}");
        });
        try {
            String base = server.uri("").toString();
            Center tablet = new Center(base, "tok");
            check.same("library", 3, Library.parse(tablet.library()).docs.size());
            check.same("jobs", 4, Jobs.parse(tablet.jobs()).ordered().size());

            Job started = tablet.start("d1");
            check.same("started", "t9", started.id);
            check.ok("running", started.active());
            check.same("tells the job where the command center is", base,
                Json.text(Json.object(Json.parse(lastBody[0])), "server"));
            check.fails("the command center's own words", "Couldn't start it: That document is in the trash.",
                () -> tablet.start("d4"));

            tablet.forget("t1");
            tablet.rename("d1", "Essay, final");
            check.same("rename sends the name", "Essay, final", Json.text(Json.object(Json.parse(lastBody[0])), "name"));
            tablet.move("d1", "");
            check.same("move to the top", "", Json.text(Json.object(Json.parse(lastBody[0])), "parent"));
            check.fails("a missing document, in the app's words", "No such document on the tablet.", () -> tablet.rename("nope", "x"));

            File dir = Files.createTempDirectory("tablet-test").toFile();
            File page = new File(dir, "p1.pdf");
            check.same("page count", 12, tablet.page("d1", 1, page));
            check.same("the page saved", "%PDF-1.4 page one", new String(Files.readAllBytes(page.toPath()), "UTF-8"));
            check.ok("no part file left", !new File(dir, "p1.pdf.part").exists());
            try {
                tablet.page("d1", 13, new File(dir, "p13.pdf"));
                check.ok("past the end throws", false);
            } catch (Center.NoPage e) {
                check.same("past the end says how many", 12, e.pages);
            }
            try {
                tablet.page("d3", 1, new File(dir, "e.pdf"));
                check.ok("an ePub with nothing written throws", false);
            } catch (Center.NoPage e) {
                check.same("nothing to show", 0, e.pages);
            }
            check.fails("a missing document's page", "No such document", () -> tablet.page("nope", 1, new File(dir, "x.pdf")));
            check.ok("nothing saved for it", !new File(dir, "x.pdf").exists());

            check.fails("a wrong token", "refused the token", () -> new Center(base, "bad").library());
        } finally {
            server.close();
        }
    }

    private static void viewport(Check check) {
        Viewport v = new Viewport(240, 296);
        check.close("100% fills the width", 0.8, v.scale(), 1e-6);
        check.same("percent", 100, v.percent());
        check.close("no sideways room at 100%", 0, v.maxX(), 1e-6);
        check.close("room to scroll down", 30, v.maxY(), 1e-3);

        v.zoom = 2;
        v.x = 4;
        v.y = 56;
        v.clamp();
        check.same("200%", 200, v.percent());
        check.close("150 units across", 150, v.viewW(), 1e-3);
        float cx = v.x + v.viewW() / 2;
        check.ok("zoom in", v.zoomIn());
        check.close("keeps the centre", cx, v.x + v.viewW() / 2, 1e-3);
        check.ok("no further than 300%", !v.zoomIn());

        v.x = 1000;
        v.y = -50;
        v.clamp();
        check.close("clamped right", v.maxX(), v.x, 1e-3);
        check.close("clamped top", 0, v.y, 1e-3);
        check.ok("can't go right", !v.canRight());
        check.ok("can go left", v.canLeft());

        float before = v.x;
        v.pan(-1, 0);
        check.close("a quarter screen per press", 240f / 4 / v.scale(), before - v.x, 1e-3);

        v.fit();
        check.same("fit is 100%", 100, v.percent());
        check.ok("fit is at the top", v.x == 0 && v.y == 0);
        check.ok("no zoom below 100%", !v.zoomOut());

        // A Letter page is a little shorter than the tablet's; a scrolled notebook page much longer.
        v.page(612, 792);
        check.close("Letter", 388.2, v.pageH, 0.1);
        check.close("less to scroll", 388.2 - 296 / 0.8, v.maxY(), 0.1);
        v.page(1404, 3744);
        check.close("twice the tablet's height", 800, v.pageH, 1e-3);
        v.y = 10000;
        v.clamp();
        check.close("scrolls to its foot", 800 - 296 / 0.8, v.y, 0.1);
        v.page(0, 0);
        check.close("an unknown shape is the tablet's", Viewport.PAGE_H, v.pageH, 1e-3);
    }
}
