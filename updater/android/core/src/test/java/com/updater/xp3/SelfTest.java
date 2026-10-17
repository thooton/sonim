package com.updater.xp3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.updater.xp3.core.Catalog;
import com.updater.xp3.core.Notes;
import com.updater.xp3.core.Releases;
import com.updater.xp3.core.Status;
import com.updater.xp3.core.Version;

import com.xp3.common.Check;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Everything in :core, checked on a desktop JVM: which version is newer,
 * reading the release's two files, and fetching from a release served here.
 */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        final Check check = new Check();
        check.run("Version", () -> versions(check));
        check.run("Catalog", () -> catalog(check));
        check.run("Status", () -> status(check));
        check.run("Notes", () -> notes(check));
        check.run("Releases", () -> releases(check));
        if (!check.report()) System.exit(1);
    }

    private static void versions(Check check) {
        check.same("equal", 0, Version.compare("1.0.0", "1.0.0"));
        check.same("patch", -1, Version.compare("1.0.2", "1.0.3"));
        check.same("minor beats patch", 1, Version.compare("1.1.0", "1.0.9"));
        check.same("numbers, not text", 1, Version.compare("1.10.0", "1.9.0"));
        check.same("missing parts are 0", 0, Version.compare("1.0", "1.0.0"));
        check.same("longer and newer", -1, Version.compare("1.0", "1.0.1"));
        check.same("leading v", 0, Version.compare("v1.2.0", "1.2.0"));
        check.same("leading zeros", 0, Version.compare("1.02", "1.2"));
        check.same("release beats pre-release", 1, Version.compare("1.0.0", "1.0.0-beta"));
        check.same("null is oldest", -1, Version.compare(null, "0.0.1"));
        check.same("empty is oldest", -1, Version.compare("", "1"));
        check.same("huge parts", 1, Version.compare("1.99999999999999999999", "1.9"));
    }

    private static void catalog(Check check) {
        Map<String, String> versions = Catalog.parse(
            "delta = 1.1.0\n# a comment\n\nlist=1.0.0\r\nbad name = 1\n../x = 2\nempty =\nnoequals\nAppDrawer = 1.0\n");
        check.same("kept", 3, versions.size());
        check.same("spaces trimmed", "1.1.0", versions.get("delta"));
        check.same("no spaces", "1.0.0", versions.get("list"));
        check.same("CRLF", "1.0.0", versions.get("list"));
        check.ok("unsafe name dropped", !versions.containsKey("../x"));
        check.ok("empty value dropped", !versions.containsKey("empty"));

        Map<String, String> packages = Catalog.parse(
            "list = com.list.xp3\ndelta = com.delta.xp3\nAppDrawer = not a package\n");
        List<Catalog.Entry> entries = Catalog.join(versions, packages);
        check.same("only apps in both, with a valid package", 2, entries.size());
        check.same("sorted", "delta", entries.get(0).name);
        check.same("version", "1.1.0", entries.get(0).version);
        check.same("package", "com.list.xp3", entries.get(1).packageName);
        check.same("apk", "list.apk", entries.get(1).apk());
        check.same("null text", 0, Catalog.parse(null).size());
    }

    private static void status(Check check) {
        check.same("missing", Status.INSTALL, Status.of(false, null, "1.0.0"));
        check.same("older", Status.UPDATE, Status.of(true, "1.0.0", "1.0.1"));
        check.same("same", Status.CURRENT, Status.of(true, "1.0", "1.0.0"));
        check.same("newer", Status.NEWER, Status.of(true, "1.2.0", "1.1.0"));
        check.same("no versionName", Status.UPDATE, Status.of(true, null, "1.0.0"));
    }

    private static final String COMMITS = "["
        + "{\"commit\":{\"message\":\"Mail: drop the bar\\n\\nLonger body.\",\"committer\":{\"date\":\"2026-10-16T10:00:00Z\"}}},"
        + "{\"commit\":{\"message\":\"Merge branch 'x'\",\"committer\":{\"date\":\"2026-10-15T10:00:00Z\"}}},"
        + "{\"commit\":{\"message\":\"List, Canvas: remove the chat!\",\"committer\":{\"date\":\"2026-10-14T10:00:00Z\"}}},"
        + "{\"commit\":{\"message\":\"Delta 1.1.0: accounts\",\"committer\":{\"date\":\"2026-10-13T10:00:00Z\"}}},"
        + "{\"commit\":{\"message\":\"Strip text from every app\",\"committer\":{\"date\":\"2026-10-12T10:00:00Z\"}}},"
        + "{\"commit\":{\"message\":\"Mail: older\",\"committer\":{\"date\":\"2026-10-11T10:00:00Z\"}}}"
        + "]";

    private static void notes(Check check) {
        check.same("one", Arrays.asList("Mail"), Notes.names("Mail: x"));
        check.same("several", Arrays.asList("List", "Canvas", "Books"), Notes.names("List, Canvas and Books: x"));
        check.same("version dropped", Arrays.asList("Delta"), Notes.names("Delta 1.0.4: upload files"));
        check.same("no colon", 0, Notes.names("Strip text from every app").size());
        check.same("capital and stop", "Remove the chat.", Notes.text("List: remove the chat"));
        check.same("own stop kept", "Done!", Notes.text("List: done!"));
        check.same("nothing after", null, Notes.text("List:"));

        Map<String, List<Notes.Note>> notes = Notes.parse(COMMITS, Arrays.asList("mail", "list", "canvas", "delta", "books"));
        check.same("mail's two", 2, notes.get("mail").size());
        check.same("subject only", "Drop the bar.", notes.get("mail").get(0).text);
        check.same("newest first", "Older.", notes.get("mail").get(1).text);
        check.same("shared commit", "Remove the chat!", notes.get("canvas").get(0).text);
        check.same("list too", 1, notes.get("list").size());
        check.same("delta", "Accounts.", notes.get("delta").get(0).text);
        check.ok("unnamed apps get nothing", !notes.containsKey("books"));

        TimeZone utc = TimeZone.getTimeZone("UTC");
        long now = java.time.Instant.parse("2026-10-17T15:00:00Z").toEpochMilli();
        check.same("today", "Today", Notes.when(now - 3600000L, now, utc));
        check.same("yesterday", "Yesterday", Notes.when(now - 20 * 3600000L, now, utc));
        check.same("a date", "Oct 14, 2026", Notes.when(now - 3 * 86400000L, now, utc));
    }

    private static void releases(Check check) throws Exception {
        final byte[] apk = new byte[300 * 1024];
        for (int i = 0; i < apk.length; i++) apk[i] = (byte) i;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/latest/versions.txt")) {
                send(exchange, 200, "list = 1.0.1\nnew = 2.0.0\n".getBytes("UTF-8"));
            } else if (path.equals("/latest/packages.txt")) {
                send(exchange, 200, "list = com.list.xp3\nnew = com.new.xp3\n".getBytes("UTF-8"));
            } else if (path.equals("/commits")) {
                send(exchange, 200, COMMITS.getBytes("UTF-8"));
            } else if (path.equals("/latest/list.apk")) {
                // As GitHub does: the file itself is somewhere else.
                exchange.getResponseHeaders().set("Location", "/files/list.apk");
                send(exchange, 302, new byte[0]);
            } else if (path.equals("/files/list.apk")) {
                send(exchange, 200, apk);
            } else {
                send(exchange, 404, "Not Found".getBytes("UTF-8"));
            }
        });
        server.start();
        File dir = Files.createTempDirectory("updater-test").toFile();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            final Releases releases = new Releases(root + "/latest", root + "/commits");
            List<Catalog.Entry> entries = releases.catalog();
            check.same("catalog size", 2, entries.size());
            check.same("catalog version", "1.0.1", entries.get(0).version);
            check.same("catalog package", "com.new.xp3", entries.get(1).packageName);
            check.same("notes fetched", "Drop the bar.",
                releases.notes(Arrays.asList("mail")).get("mail").get(0).text);

            final File to = new File(dir, "list.apk");
            final long[] last = {-1, -1};
            releases.download("list.apk", to, (done, total) -> {
                last[0] = done;
                last[1] = total;
            });
            check.ok("downloaded through the redirect", java.util.Arrays.equals(apk, Files.readAllBytes(to.toPath())));
            check.same("progress reached the end", Long.valueOf(apk.length), Long.valueOf(last[0]));
            check.same("progress knew the total", Long.valueOf(apk.length), Long.valueOf(last[1]));
            check.ok("no part file left", !new File(dir, "list.apk.part").exists());

            check.fails("missing file", "has no gone.apk", () -> {
                releases.download("gone.apk", new File(to.getParentFile(), "gone.apk"), null);
            });
            check.ok("failed download leaves nothing", !new File(dir, "gone.apk").exists()
                && !new File(dir, "gone.apk.part").exists());
        } finally {
            server.stop(0);
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }
}
