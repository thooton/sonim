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
import java.util.List;
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
        check.run("Errors", () -> errors(check));
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

    /** A manifest as .github/manifest.py writes one, with things to skip. */
    private static final String MANIFEST = "{\"schema\": 1, \"built\": \"2026-10-17T10:00:00Z\", \"apps\": {"
        + "\"list\": {\"version\": \"1.0.1\", \"versionCode\": 3, \"package\": \"com.list.xp3\", \"label\": \"List\","
        + " \"apk\": \"list.apk\", \"size\": SIZE, \"sha256\": \"SHA\", \"notes\": ["
        + "{\"commit\": \"a\", \"time\": \"2026-10-14T10:00:00+00:00\", \"text\": \"Older.\"},"
        + "{\"commit\": \"b\", \"time\": \"2026-10-16T10:00:00+00:00\", \"text\": \"Drop the bar.\", \"version\": \"1.0.1\"},"
        + "{\"commit\": \"c\", \"text\": \"  \"}]},"
        + "\"new\": {\"version\": \"2.0.0\", \"package\": \"com.new.xp3\", \"sha256\": \"not hex\"},"
        + "\"AppDrawer\": {\"version\": \"1.0\", \"package\": \"not a package\"},"
        + "\"../x\": {\"version\": \"1.0\", \"package\": \"com.x.xp3\"},"
        + "\"nover\": {\"package\": \"com.nover.xp3\"}"
        + "}}";

    private static String manifest(long size, String sha) {
        return MANIFEST.replace("SIZE", String.valueOf(size)).replace("SHA", sha);
    }

    private static void catalog(Check check) {
        String sha = "AB" + new String(new char[62]).replace('\0', '0');
        List<Catalog.Entry> entries = Catalog.parse(manifest(1234, sha));
        check.same("only valid apps", 2, entries.size());
        Catalog.Entry list = entries.get(0);
        check.same("sorted", "list", list.name);
        check.same("version", "1.0.1", list.version);
        check.same("package", "com.list.xp3", list.packageName);
        check.same("label", "List", list.label);
        check.same("size", Long.valueOf(1234), Long.valueOf(list.size));
        check.same("sha256 lowercased", "ab" + sha.substring(2), list.sha256);
        check.same("apk", "list.apk", list.apk());
        check.same("blank notes dropped", 2, list.notes.size());
        check.same("newest first", "Drop the bar.", list.notes.get(0).text);
        check.same("note version", "1.0.1", list.notes.get(0).version);
        check.same("note time", Long.valueOf(java.time.Instant.parse("2026-10-14T10:00:00Z").toEpochMilli()),
            Long.valueOf(list.notes.get(1).time));
        check.same("no version", null, list.notes.get(1).version);
        Catalog.Entry fresh = entries.get(1);
        check.same("label from name", "New", fresh.label);
        check.same("size unknown", Long.valueOf(-1), Long.valueOf(fresh.size));
        check.same("bad sha256 dropped", null, fresh.sha256);
        check.same("no notes", 0, fresh.notes.size());
        check.fails("other schema", "schema", () -> Catalog.parse("{\"schema\": 2, \"apps\": {}}"));
        check.fails("not JSON", "", () -> Catalog.parse("list = 1.0.0"));
    }

    private static void status(Check check) {
        check.same("missing", Status.INSTALL, Status.of(false, null, "1.0.0"));
        check.same("older", Status.UPDATE, Status.of(true, "1.0.0", "1.0.1"));
        check.same("same", Status.CURRENT, Status.of(true, "1.0", "1.0.0"));
        check.same("newer", Status.NEWER, Status.of(true, "1.2.0", "1.1.0"));
        check.same("no versionName", Status.UPDATE, Status.of(true, null, "1.0.0"));
    }

    private static void notes(Check check) {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long now = java.time.Instant.parse("2026-10-17T15:00:00Z").toEpochMilli();
        check.same("today", "Today", Notes.when(now - 3600000L, now, utc));
        check.same("yesterday", "Yesterday", Notes.when(now - 20 * 3600000L, now, utc));
        check.same("a date", "Oct 14, 2026", Notes.when(now - 3 * 86400000L, now, utc));
    }

    private static void errors(Check check) {
        String cut = "The download was cut short.";
        check.same("dropped mid-read", cut, Releases.friendly(
            new javax.net.ssl.SSLException("Read error: I/O error during system call, Connection reset by peer"), cut).getMessage());
        check.same("handshake", "The secure connection failed.", Releases.friendly(
            new javax.net.ssl.SSLHandshakeException("bad certificate"), cut).getMessage());
        check.same("unverified", "The secure connection failed.", Releases.friendly(
            new javax.net.ssl.SSLPeerUnverifiedException("no peer"), cut).getMessage());
        check.same("no network", "No network.", Releases.friendly(
            new java.net.UnknownHostException("github.com"), cut).getMessage());
    }

    private static void releases(Check check) throws Exception {
        final byte[] apk = new byte[300 * 1024];
        for (int i = 0; i < apk.length; i++) apk[i] = (byte) i;
        StringBuilder hex = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(apk)) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        final String manifest = manifest(apk.length, hex.toString());
        final List<String> asked = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/latest/manifest.json")) {
                asked.add(exchange.getRequestURI().getRawQuery() + " "
                    + exchange.getRequestHeaders().getFirst("Connection"));
                send(exchange, 200, manifest.getBytes("UTF-8"));
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
            final Releases releases = new Releases(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
            List<Catalog.Entry> entries = releases.catalog();
            check.same("catalog size", 2, entries.size());
            check.same("catalog version", "1.0.1", entries.get(0).version);
            check.same("catalog package", "com.new.xp3", entries.get(1).packageName);
            // Check Again: a URL never asked for, on a connection of its own.
            releases.catalog();
            check.same("asked twice", 2, asked.size());
            check.ok("never pooled", asked.get(0).endsWith(" close") && asked.get(1).endsWith(" close"));
            check.ok("a new URL each check", asked.get(0).startsWith("t=")
                && !asked.get(0).equals(asked.get(1)));
            check.same("notes came with it", "Drop the bar.", entries.get(0).notes.get(0).text);

            final File to = new File(dir, "list.apk");
            final long[] last = {-1, -1};
            releases.download(entries.get(0), to, (done, total) -> {
                last[0] = done;
                last[1] = total;
            });
            check.ok("downloaded through the redirect", java.util.Arrays.equals(apk, Files.readAllBytes(to.toPath())));
            check.same("progress reached the end", Long.valueOf(apk.length), Long.valueOf(last[0]));
            check.same("progress knew the total", Long.valueOf(apk.length), Long.valueOf(last[1]));
            check.ok("no part file left", !new File(dir, "list.apk.part").exists());

            check.fails("wrong SHA-256", "doesn't match", () -> {
                releases.download("list.apk", apk.length, hex.toString().replace('0', '1').replace('a', '0'),
                    new File(to.getParentFile(), "bad.apk"), null);
            });
            check.ok("mismatch leaves nothing", !new File(dir, "bad.apk").exists()
                && !new File(dir, "bad.apk.part").exists());
            check.fails("wrong size", "size", () -> {
                releases.download("list.apk", apk.length + 1, null, new File(to.getParentFile(), "bad.apk"), null);
            });
            check.fails("missing file", "has no gone.apk", () -> {
                releases.download("gone.apk", -1, null, new File(to.getParentFile(), "gone.apk"), null);
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
