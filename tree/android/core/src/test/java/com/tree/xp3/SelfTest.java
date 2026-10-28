package com.tree.xp3;

import com.tree.xp3.qa.Center;
import com.tree.xp3.qa.Finding;
import com.tree.xp3.qa.Qa;
import com.tree.xp3.qa.Words;
import com.xp3.common.Check;
import com.xp3.common.Json;
import com.xp3.common.TestServer;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Everything in :core, checked on a desktop JVM: the QA as the command
 * center sends it, how the app words it, and the client against a local
 * server standing in for the command center.
 */
public final class SelfTest {

    private static final TimeZone PHOENIX = TimeZone.getTimeZone("America/Phoenix");
    /** 2026-10-28 08:00 in Phoenix (15:00 UTC). */
    private static final long NOW = at("2026-10-28T15:00:00Z");

    static final String RUN = "{\"id\":\"r_2\",\"trigger\":\"manual\",\"all\":true,\"status\":\"running\",\"stage\":\"apps\","
        + "\"created\":\"2026-10-28T14:00:00Z\",\"apps\":["
        + "{\"app\":\"sonim/list\",\"repo\":\"sonim\",\"dir\":\"list\",\"phone\":true,\"spec\":true,\"status\":\"done\",\"why\":\"\"},"
        + "{\"app\":\"sonim/mail\",\"repo\":\"sonim\",\"dir\":\"mail\",\"phone\":true,\"spec\":true,\"status\":\"running\",\"why\":\"\"},"
        + "{\"app\":\"tablet/flashcards\",\"repo\":\"tablet\",\"dir\":\"flashcards\",\"phone\":false,\"spec\":false,\"status\":\"skipped\",\"why\":\"no emulator\"},"
        + "{\"app\":\"tablet/notes\",\"repo\":\"tablet\",\"dir\":\"notes\",\"phone\":false,\"spec\":false,\"status\":\"waiting\",\"why\":\"\"}],"
        + "\"jobs\":[{\"id\":\"j1\",\"app\":\"sonim/list\",\"repo\":\"sonim\",\"role\":\"explore\",\"status\":\"done\","
        + "\"created\":\"2026-10-28T14:01:00Z\",\"ended\":\"2026-10-28T14:20:00Z\",\"summary\":\"3 findings\"},"
        + "{\"id\":\"j2\",\"app\":\"sonim/mail\",\"repo\":\"sonim\",\"role\":\"fix\",\"status\":\"running\",\"created\":\"2026-10-28T14:30:00Z\"}],"
        + "\"summary\":\"\",\"error\":\"\",\"devices\":true}";

    static final String QA = "{\"settings\":{\"enabled\":true,\"hour\":3,\"time_zone\":\"America/Phoenix\"},"
        + "\"apps\":["
        + "{\"id\":\"sonim/list\",\"repo\":\"sonim\",\"dir\":\"list\",\"phone\":true,\"spec\":true,\"grow\":true,"
        + "\"explored\":\"2026-10-28T10:20:00Z\",\"open\":2,\"passed\":1,\"grown\":1,"
        + "\"last\":{\"app\":\"sonim/list\",\"repo\":\"sonim\",\"dir\":\"list\",\"phone\":true,\"spec\":true,\"status\":\"done\",\"why\":\"\"}},"
        + "{\"id\":\"sonim/mail\",\"repo\":\"sonim\",\"dir\":\"mail\",\"phone\":true,\"spec\":true,\"grow\":false,\"open\":0,\"passed\":0,\"grown\":0},"
        + "{\"id\":\"tablet/flashcards\",\"repo\":\"tablet\",\"dir\":\"flashcards\",\"phone\":false,\"spec\":false,\"grow\":false,\"open\":0,\"passed\":0,\"grown\":0},"
        + "{\"repo\":\"tablet\",\"dir\":\"broken\"}"
        + "],"
        + "\"running\":" + RUN + ","
        + "\"runs\":["
        + "{\"id\":\"r_2\",\"trigger\":\"manual\",\"status\":\"running\",\"stage\":\"apps\",\"created\":\"2026-10-28T14:00:00Z\",\"summary\":\"\"},"
        + "{\"id\":\"r_1\",\"trigger\":\"schedule\",\"status\":\"done\",\"stage\":\"done\",\"created\":\"2026-10-28T10:00:00Z\","
        + "\"ended\":\"2026-10-28T10:41:00Z\",\"summary\":\"Fixed 3 bugs in List.\\nMail: one problem left for you.\",\"error\":\"\"}],"
        + "\"devices\":true}";

    static final String FINDINGS = "["
        + "{\"id\":\"f_ab12cd34ef\",\"app\":\"sonim/list\",\"run\":\"r_1\",\"job\":\"j1\",\"kind\":\"feature\",\"title\":\"Undo a ticked task\","
        + "\"detail\":\"Pressing 0 after ticking a task\\nbrings it back.\",\"shots\":[\"f_ab12cd34ef-1.png\",\"../secret\",\"f_ab12cd34ef-2.png\"],"
        + "\"status\":\"grown\",\"reason\":\"Built it: 0 undoes the last tick\",\"commit\":\"abc1234\",\"found\":\"2026-10-28T10:10:00Z\","
        + "\"updated\":\"2026-10-28T10:30:00Z\",\"triaged_in\":\"r_1\"},"
        + "{\"id\":\"f_2\",\"app\":\"sonim/list\",\"run\":\"r_1\",\"kind\":\"bug\",\"title\":\"Crash on empty list\",\"detail\":\"\","
        + "\"shots\":[],\"status\":\"fixed\",\"reason\":\"Guarded the empty case\",\"verdict\":\"up\",\"found\":\"2026-10-27T10:10:00Z\"},"
        + "{\"app\":\"sonim/list\",\"title\":\"No id\"}"
        + "]";

    public static void main(String[] args) {
        Check check = new Check();
        check.run("qa", () -> qa(check));
        check.run("findings", () -> findings(check));
        check.run("words", () -> words(check));
        check.run("client", () -> client(check));
        if (!check.report()) System.exit(1);
    }

    private static long at(String rfc3339) {
        return java.time.OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli();
    }

    private static void qa(Check check) {
        Qa qa = Qa.parse(QA);
        check.ok("nightly on", qa.settings.enabled);
        check.same("hour", 3, qa.settings.hour);
        check.same("time zone", "America/Phoenix", qa.settings.timeZone);
        check.same("apps without an id are left out", 3, qa.apps.size());
        Qa.App list = qa.apps.get(0);
        check.same("id", "sonim/list", list.id);
        check.same("name", "List", list.name());
        check.ok("phone", list.phone);
        check.ok("grows", list.grow);
        check.ok("spec", list.spec);
        check.same("explored", at("2026-10-28T10:20:00Z"), list.explored);
        check.same("open", 2, list.open);
        check.same("passed", 1, list.passed);
        check.same("grown", 1, list.grown);
        check.same("last", "done", list.last.status);
        Qa.App mail = qa.app("sonim/mail");
        check.ok("found by id", mail != null && !mail.grow);
        check.same("no last", null, mail.last);
        check.same("never explored", 0L, mail.explored);
        check.ok("tablet app", !qa.app("tablet/flashcards").phone && !qa.app("tablet/flashcards").spec);
        check.same("unknown app", null, qa.app("sonim/nothing"));

        check.ok("running", qa.running != null && qa.running.isRunning());
        check.same("run apps", 4, qa.running.apps.size());
        check.same("finished counts done and skipped", 2, qa.running.finished());
        check.same("current", "sonim/mail", qa.running.current().app);
        check.same("skipped why", "no emulator", qa.running.progress("tablet/flashcards").why);
        check.same("jobs", 2, qa.running.jobs.size());
        check.same("job role", "explore", qa.running.jobs.get(0).role);
        check.same("job summary", "3 findings", qa.running.jobs.get(0).summary);
        check.same("progress for an app", "running", qa.progress("sonim/mail").status);
        check.same("runs", 2, qa.runs.size());
        check.same("latest is the running one", "r_2", qa.latest().id);
        check.same("last summary skips the empty one", "r_1", qa.lastSummary().id);
        check.ok("summary has its lines", qa.lastSummary().summary.contains("\nMail"));
        check.ok("devices", qa.devices);
        check.same("no error", "", qa.error);

        Qa grown = qa.withGrow("sonim/mail", true);
        check.ok("grow switched", grown.app("sonim/mail").grow);
        check.ok("others kept", grown.app("sonim/list").grow && grown.apps.size() == 3);
        check.ok("original untouched", !qa.app("sonim/mail").grow);

        Qa.Run cancelled = Qa.Run.parse(RUN.replace("\"status\":\"running\",\"stage\"", "\"status\":\"cancelled\",\"stage\""));
        Qa after = qa.withRunning(cancelled);
        check.same("cancelled run is not running", null, after.running);
        check.same("its entry in runs updated", "cancelled", after.runs.get(0).status);
        check.same("latest then is it", "r_2", after.latest().id);

        Qa bare = Qa.parse("{\"settings\":{},\"apps\":[],\"running\":null,\"runs\":[],\"error\":\"GitHub said no\"}");
        check.same("no running", null, bare.running);
        check.same("no latest", null, bare.latest());
        check.same("no summary", null, bare.lastSummary());
        check.ok("nightly off by default", !bare.settings.enabled);
        check.same("error", "GitHub said no", bare.error);
        check.same("hour clamped", 23, Qa.parse("{\"settings\":{\"hour\":99}}").settings.hour);
        check.fails("not JSON", () -> Qa.parse("<html>"));

        Qa idOnly = Qa.parse("{\"apps\":[{\"id\":\"sonim/weather\"}]}");
        check.same("repo from the id", "sonim", idOnly.apps.get(0).repo);
        check.same("dir from the id", "weather", idOnly.apps.get(0).dir);
    }

    private static void findings(Check check) {
        List<Finding> list = Finding.list(FINDINGS);
        check.same("findings without an id are left out", 2, list.size());
        Finding grown = list.get(0);
        check.same("kind", "feature", grown.kind);
        check.same("title", "Undo a ticked task", grown.title);
        check.ok("detail lines", grown.detail.contains("\nbrings"));
        check.same("unsafe shot names dropped", Arrays.asList("f_ab12cd34ef-1.png", "f_ab12cd34ef-2.png"), grown.shots);
        check.same("absent verdict", "", grown.verdict);
        check.ok("grown", grown.grown());
        check.ok("awaiting a verdict", grown.awaiting());
        check.same("found", at("2026-10-28T10:10:00Z"), grown.found);
        check.same("commit", "abc1234", grown.commit);
        Finding fixed = list.get(1);
        check.ok("fixed isn't grown", !fixed.grown() && !fixed.awaiting());
        check.same("verdict", "up", fixed.verdict);
        Finding again = Finding.parse(grown.toJson());
        check.same("round trip", grown.reason, again.reason);
        check.same("round trip shots", grown.shots, again.shots);
        check.same("odd verdict is none", "", Finding.parse("{\"id\":\"x\",\"verdict\":\"sideways\"}").verdict);
        check.ok("judged grown no longer awaits",
            !Finding.parse("{\"id\":\"x\",\"status\":\"grown\",\"verdict\":\"down\"}").awaiting());
    }

    private static void words(Check check) {
        check.same("name", "List", Words.name("list"));
        check.same("name of one word", "Newyorker", Words.name("newyorker"));
        check.same("no name", "?", Words.name(""));
        check.same("phone", "phone", Words.tag("sonim"));
        check.same("tablet", "tablet", Words.tag("tablet"));
        check.same("kind", "Inconsistency", Words.kind("inconsistency"));
        check.same("passed", "Left for you", Words.status("passed"));
        check.same("reverted", "Reverted", Words.status("reverted"));
        check.same("unknown status", "Pending", Words.status("pending"));
        check.same("progress", "Skipped", Words.progress("skipped"));
        check.same("up", "👍", Words.verdict("up"));
        check.same("none", "", Words.verdict(""));

        Qa qa = Qa.parse(QA);
        check.same("app line", "Grows · 2 open · 1 left for you · 1 grown", Words.app(qa.app("sonim/list")));
        check.same("nothing open", "Nothing open", Words.app(qa.app("sonim/mail")));
        check.same("no spec", "No spec yet", Words.app(qa.app("tablet/flashcards")));

        check.same("midnight", "12 AM", Words.hour(0));
        check.same("morning", "3 AM", Words.hour(3));
        check.same("noon", "12 PM", Words.hour(12));
        check.same("evening", "11 PM", Words.hour(23));
        check.same("schedule", "Daily at 3 AM", Words.schedule(qa.settings));
        check.same("schedule off", "Daily run off", Words.schedule(new Qa.Settings(false, 3, "")));

        check.same("today", "3:41 AM", Words.when(at("2026-10-28T10:41:00Z"), NOW, PHOENIX));
        check.same("yesterday", "Yesterday 11:30 PM", Words.when(at("2026-10-28T06:30:00Z"), NOW, PHOENIX));
        check.same("this year", "Oct 20", Words.when(at("2026-10-20T12:00:00Z"), NOW, PHOENIX));
        check.same("another year", "Oct 20, 2025", Words.when(at("2025-10-20T12:00:00Z"), NOW, PHOENIX));
        check.same("never", "", Words.when(0, NOW, PHOENIX));

        check.same("running", "Running · 2 of 4", Words.run(qa.running, NOW, PHOENIX));
        check.same("done", "Done 3:41 AM", Words.run(qa.runs.get(1), NOW, PHOENIX));
        check.same("no runs", "No runs yet", Words.run(null, NOW, PHOENIX));
        Qa.Run refactor = Qa.Run.parse("{\"id\":\"r\",\"status\":\"running\",\"stage\":\"refactor\"}");
        check.same("refactoring", "Running · refactoring", Words.run(refactor, NOW, PHOENIX));
        Qa.Run cancelled = Qa.Run.parse("{\"id\":\"r\",\"status\":\"cancelled\",\"created\":\"2026-10-28T09:10:00Z\"}");
        check.same("cancelled", "Cancelled 2:10 AM", Words.run(cancelled, NOW, PHOENIX));
        check.same("trigger", "Scheduled", Words.trigger("schedule"));
        check.same("manual", "Run by you", Words.trigger("manual"));
        check.same("role", "Refactor", Words.role("refactor"));

        check.ok("shot name", Words.safeName("f_ab12cd34ef-1.png"));
        check.ok("no paths", !Words.safeName("../x.png") && !Words.safeName("a/b.png") && !Words.safeName(".hidden"));
    }

    private static void client(Check check) throws Exception {
        final String token = "tok-123";
        TestServer server = new TestServer(request -> {
            if (!("Bearer " + token).equals(request.header("Authorization"))) {
                return TestServer.Reply.of(401, "application/json", "{\"error\":\"unauthorized\"}");
            }
            String key = request.method + " " + request.path;
            if (key.equals("GET /qa") || key.equals("GET /qa?fresh=1")) return TestServer.Reply.json(QA);
            if (key.equals("PUT /qa/settings")) {
                Map<String, Object> body = Json.object(Json.parse(request.body));
                boolean enabled = body.containsKey("enabled") ? Json.flag(body, "enabled") : true;
                Double hour = Json.number(body, "hour");
                return TestServer.Reply.json("{\"enabled\":" + enabled + ",\"hour\":" + (hour == null ? 3 : hour.intValue())
                    + ",\"time_zone\":\"America/Phoenix\"}");
            }
            if (key.equals("PUT /qa/apps/sonim/list")) {
                return TestServer.Reply.json("{\"app\":\"sonim/list\",\"grow\":" + Json.flag(Json.object(Json.parse(request.body)), "grow") + "}");
            }
            if (key.equals("POST /qa/runs")) {
                if (request.body.contains("busy")) {
                    return TestServer.Reply.of(409, "application/json", "{\"error\":\"a run is already going\"}");
                }
                TestServer.Reply reply = TestServer.Reply.json(RUN);
                reply.status = 201;
                return reply;
            }
            if (key.equals("GET /qa/runs/r_2")) return TestServer.Reply.json(RUN);
            if (key.equals("DELETE /qa/runs/r_2")) {
                return TestServer.Reply.json(RUN.replace("\"status\":\"running\",\"stage\"", "\"status\":\"cancelled\",\"stage\""));
            }
            if (key.equals("GET /qa/findings?app=sonim%2Flist&limit=100")) return TestServer.Reply.json(FINDINGS);
            if (key.equals("PUT /qa/findings/f_ab12cd34ef/verdict")) {
                String verdict = Json.text(Json.object(Json.parse(request.body)), "verdict");
                String one = FINDINGS.substring(1, FINDINGS.indexOf("},{\"id\":\"f_2\"") + 1);
                return TestServer.Reply.json(verdict.length() == 0 ? one
                    : one.replace("\"triaged_in\"", "\"verdict\":\"" + verdict + "\",\"triaged_in\""));
            }
            if (key.equals("PUT /qa/findings/f_2/verdict")) {
                return TestServer.Reply.of(409, "application/json", "{\"error\":\"only a grown feature takes a verdict\"}");
            }
            if (key.equals("GET /qa/shots/f_ab12cd34ef-1.png")) return TestServer.Reply.of(200, "image/png", "PNG-BYTES");
            if (key.equals("GET /qa/broken")) return TestServer.Reply.json("not json");
            return TestServer.Reply.of(404, "text/plain", "404 page not found");
        });
        try {
            String base = server.uri("").toString();
            Center center = new Center(base, token);

            Qa qa = center.qa(false);
            check.same("qa", 3, qa.apps.size());
            check.same("asked for /qa", "/qa", server.seen.get(server.seen.size() - 1).path);
            check.same("bearer", "Bearer " + token, server.seen.get(0).header("Authorization"));
            center.qa(true);
            check.same("fresh", "/qa?fresh=1", server.seen.get(server.seen.size() - 1).path);

            Qa.Settings off = center.nightly(false);
            check.ok("nightly off", !off.enabled);
            check.same("enabled and the address sent", "{\"enabled\":false,\"server\":\"" + base + "\"}", last(server).body);
            Qa.Settings five = center.hour(5);
            check.same("hour", 5, five.hour);
            check.same("hour and the address sent", "{\"hour\":5,\"server\":\"" + base + "\"}", last(server).body);
            check.fails("hour out of range", () -> center.hour(24));

            check.ok("grow on", center.grow(qa.app("sonim/list"), true));
            check.same("grow body", "{\"grow\":true}", last(server).body);
            check.same("grow method", "PUT", last(server).method);
            check.ok("grow off", !center.grow(qa.app("sonim/list"), false));

            Qa.Run started = center.start(Collections.<String>emptyList());
            check.same("started", "r_2", started.id);
            check.same("every app is just the address", "{\"server\":\"" + base + "\"}", last(server).body);
            center.start(Collections.singletonList("sonim/list"));
            check.same("one app", "{\"apps\":[\"sonim/list\"],\"server\":\"" + base + "\"}", last(server).body);
            check.fails("409 says why", "A run is already going.", () -> center.start(Collections.singletonList("busy")));

            check.same("run", 4, center.run("r_2").apps.size());
            Qa.Run cancelled = center.cancel("r_2");
            check.same("cancel method", "DELETE", last(server).method);
            check.same("cancelled", "cancelled", cancelled.status);

            List<Finding> findings = center.findings("sonim/list", 100);
            check.same("findings", 2, findings.size());
            check.same("findings query", "/qa/findings?app=sonim%2Flist&limit=100", last(server).path);

            Finding up = center.verdict("f_ab12cd34ef", "up");
            check.same("verdict body", "{\"verdict\":\"up\"}", last(server).body);
            check.same("verdict given", "up", up.verdict);
            Finding cleared = center.verdict("f_ab12cd34ef", "");
            check.same("cleared body", "{\"verdict\":\"\"}", last(server).body);
            check.same("verdict cleared", "", cleared.verdict);
            check.fails("verdict on a fixed one", "Only a grown feature takes a verdict.", () -> center.verdict("f_2", "down"));
            check.fails("no such verdict", () -> center.verdict("f_2", "sideways"));

            File dir = Files.createTempDirectory("tree-test").toFile();
            File shot = new File(dir, "shot.png");
            center.shot("f_ab12cd34ef-1.png", shot);
            check.same("shot saved", "PNG-BYTES", new String(Files.readAllBytes(shot.toPath()), "UTF-8"));
            int before = server.seen.size();
            File missing = new File(dir, "missing.png");
            check.fails("missing shot", () -> center.shot("f_zz-9.png", missing));
            check.ok("nothing left of a missing shot", !missing.exists() && !new File(dir, "missing.png.part").exists());
            check.fails("unsafe shot never asked for", () -> center.shot("../../etc", new File(dir, "x")));
            check.same("only the missing one was asked for", before + 1, server.seen.size());
            shot.delete();
            dir.delete();

            check.fails("wrong token", "Token refused", () -> new Center(base, "nope").qa(false));
            check.fails("unknown route", "has no QA", () -> center.run("r_404"));
        } finally {
            server.close();
        }

        TestServer junk = new TestServer(request -> TestServer.Reply.json("<html>"));
        try {
            check.fails("unreadable", "unreadable", () -> new Center(junk.uri("").toString(), "t").qa(false));
        } finally {
            junk.close();
        }
    }

    private static TestServer.Request last(TestServer server) {
        return server.seen.get(server.seen.size() - 1);
    }
}
