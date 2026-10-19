package com.tablet.xp3.net;

import com.tablet.xp3.data.Job;
import com.tablet.xp3.data.Jobs;
import com.xp3.common.Http;
import com.xp3.common.Json;
import com.xp3.common.Login;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Map;

/**
 * The reMarkable and its transform jobs, through the command center
 * (cmd/delta-cc/remarkable.go and transform.go in thooton/delta), signed in
 * with the delta token as Source and Places are.
 *
 * The command center reads the tablet's files from rmfakecloud, which the
 * tablet syncs with, so what this sees is what the tablet last synced, and
 * a change made here reaches the tablet at its next sync (which the command
 * center asks for straight away).
 */
public final class Center {

    /** More than one page of any PDF the tablet would show, scans included. */
    static final long MAX_PAGE = 40000000;
    /** More than any document a transform makes. */
    static final long MAX_FILE = 200000000;
    private static final int QUICK = 30000;
    /** A page of a long PDF takes the command center a moment to draw. */
    private static final int SLOW = 120000;
    /** Time for the command center to file a large PDF once it has it all. */
    private static final int UPLOAD = 300000;

    /** There is no page n; `pages` is how many there are (0: nothing to show). */
    public static final class NoPage extends IOException {
        public final int pages;

        NoPage(int pages, String message) {
            super(message);
            this.pages = pages;
        }
    }

    private final String server;
    private final String token;
    private final Http http = new Http();

    public Center(Login login) {
        this(login.server(), login.token());
        if (!login.ready()) throw new IllegalArgumentException("Not signed in");
    }

    /** Any address, for the tests' plain-http server; the app goes through Login, which takes https only. */
    public Center(String server, String token) {
        this.server = server;
        this.token = token;
    }

    /** The listing Library.parse reads. */
    public String library() throws IOException, InterruptedException {
        return check(http.send(request("/remarkable", QUICK)), "read the tablet").body();
    }

    /** The listing Jobs.parse reads. */
    public String jobs() throws IOException, InterruptedException {
        return check(http.send(request("/transforms", QUICK)), "read the transforms").body();
    }

    /**
     * Starts a transform job on document `id`. The command center starts a
     * session for it, which takes a few seconds; `server` tells that session
     * where to reach the command center, which is where this reaches it.
     */
    public Job start(String id) throws IOException, InterruptedException {
        String body = Json.write(Json.map("document", id, "server", server));
        return Jobs.job(check(http.send(request("/transforms", SLOW).post(body)), "start it").body());
    }

    /** Forgets a job; a running one's agent can no longer submit anything. */
    public void forget(String job) throws IOException, InterruptedException {
        check(http.send(request("/transforms/" + segment(job), QUICK).method("DELETE")), "do that");
    }

    public void rename(String id, String name) throws IOException, InterruptedException {
        change(id, Json.map("name", name));
    }

    /** Into folder `parent`, "" for the top. */
    public void move(String id, String parent) throws IOException, InterruptedException {
        change(id, Json.map("parent", parent));
    }

    // PATCH is the route's own method, but HttpURLConnection can't send it,
    // and the command center takes POST the same.
    private void change(String id, Map<String, Object> fields) throws IOException, InterruptedException {
        check(http.send(request("/remarkable/" + segment(id), QUICK).post(Json.write(fields))), "change it");
    }

    /**
     * Saves page `n` (from 1) of document `id`, as the tablet shows it with
     * what is written on it, to `dest` as a one-page PDF, and returns how
     * many pages there are. Throws NoPage when there is no page n.
     */
    public int page(String id, int n, File dest) throws IOException, InterruptedException {
        Http.Stream stream = http.stream(request("/remarkable/" + segment(id) + "/pages/" + n, SLOW));
        try {
            int status = stream.status();
            int pages = number(stream.header("X-Pages"));
            if (status == 404 && stream.header("X-Pages").length() > 0) {
                throw new NoPage(pages, pages == 0 ? "No pages to show"
                    : pages + (pages == 1 ? " page" : " pages"));
            }
            if (status < 200 || status >= 300) fail(status, small(stream.stream()), "show the page");
            save(stream.stream(), dest, MAX_PAGE, "Page too big to show");
            return Math.max(pages, n);
        } finally {
            stream.close();
        }
    }

    /**
     * Saves document `id` whole, as the tablet shows it with what is written
     * on it, to `dest` as one PDF. An ePub with nothing written on it has
     * none, and the command center says so.
     */
    public void pdf(String id, File dest) throws IOException, InterruptedException {
        Http.Stream stream = http.stream(request("/remarkable/" + segment(id) + "/pdf", SLOW));
        try {
            int status = stream.status();
            if (status < 200 || status >= 300) fail(status, small(stream.stream()), "download it");
            save(stream.stream(), dest, MAX_FILE, "File too big");
        } finally {
            stream.close();
        }
    }

    /**
     * Puts a PDF or ePub from the phone on the tablet, as `name`, in folder
     * `parent` ("" for the top), streamed rather than read into memory, and
     * returns its id. `sending` hears how much has gone.
     */
    public String upload(File file, String name, String parent, Http.Sending sending) throws IOException, InterruptedException {
        String query = "?name=" + URLEncoder.encode(name, "UTF-8") + "&parent=" + URLEncoder.encode(parent, "UTF-8");
        InputStream in = new FileInputStream(file);
        try {
            Http.Response sent = check(http.upload(request("/remarkable/documents" + query, UPLOAD).method("POST")
                .header("Content-Type", "application/octet-stream"), in, file.length(), sending), "upload it");
            String id = Json.text(Json.object(Json.parse(sent.body())), "id");
            return id == null ? "" : id;
        } catch (IllegalArgumentException e) {
            return "";
        } finally {
            in.close();
        }
    }

    /**
     * Saves `name` from Delta's Files (a transform's result: its PDF, or the
     * Word document it was made from) to `dest`.
     */
    public void download(String name, File dest) throws IOException, InterruptedException {
        Http.Stream stream = http.stream(request("/files/" + segment(name), SLOW));
        try {
            int status = stream.status();
            if (status == 404) throw new IOException("File not found");
            if (status < 200 || status >= 300) fail(status, small(stream.stream()), "download it");
            save(stream.stream(), dest, MAX_FILE, "File too big");
        } finally {
            stream.close();
        }
    }

    /** Writes `in` to `dest` through a part file, so a broken download leaves nothing behind. */
    private static void save(InputStream in, File dest, long max, String tooBig) throws IOException, InterruptedException {
        File part = new File(dest.getPath() + ".part");
        long total = 0;
        boolean saved = false;
        OutputStream out = new FileOutputStream(part);
        try {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = in.read(buffer)) != -1; ) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                total += count;
                if (total > max) throw new IOException(tooBig);
                out.write(buffer, 0, count);
            }
            out.close();
            saved = part.renameTo(dest);
            if (!saved) throw new IOException("Couldn't save the file");
        } finally {
            out.close();
            if (!saved) part.delete();
        }
    }

    private Http.Request request(String path, int timeout) {
        return Http.Request.to(URI.create(server + path))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .timeout(timeout);
    }

    private static Http.Response check(Http.Response response, String what) throws IOException {
        if (response.status() < 200 || response.status() >= 300) fail(response.status(), response.body(), what);
        return response;
    }

    /**
     * The command center's own words for what went wrong ({"error": ...}),
     * which name the problem ("that document is in the trash"); they are
     * this deployment's, and never carry a credential.
     */
    static void fail(int status, String body, String what) throws IOException {
        if (status == 401) throw new IOException("Token refused");
        String said = null;
        try {
            said = Json.text(Json.object(Json.parse(body)), "error");
        } catch (RuntimeException ignored) {
            // Not the command center's JSON: a proxy's page, or nothing.
        }
        if (said != null && said.trim().length() > 0) {
            // The app calls it the tablet, whatever brand it is.
            String text = said.trim().replace("the reMarkable", "the tablet");
            text = text.substring(0, 1).toUpperCase(Locale.US) + text.substring(1);
            throw new IOException("Couldn't " + what + ": " + (text.endsWith(".") ? text : text + "."));
        }
        Http.requireSuccess(status, "The command center");
    }

    private static String small(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; out.size() < 65536 && (count = in.read(buffer)) != -1; ) out.write(buffer, 0, count);
        return out.toString("UTF-8");
    }

    private static int number(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** One path segment: ids are the tablet's UUIDs, but nothing here counts on it. */
    static String segment(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
                out.append((char) c);
            } else {
                out.append(String.format(Locale.US, "%%%02X", c));
            }
        }
        return out.toString();
    }
}
