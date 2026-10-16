package com.source.xp3.net;

import com.xp3.common.Http;
import com.xp3.common.Json;
import com.xp3.common.Login;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The command center's music routes, the same ones `delta music` uses
 * (cmd/delta/music.go and cmd/delta-cc/music_run.go in thooton/delta):
 *
 *   POST   /music             {"mode": "song"|"album", "query": "..."}; streams
 *                             JSON lines: {"stdout"}, {"stderr"}, {} keepalives
 *                             and last {"done": {"error", "job", "files"}}.
 *   GET    /music-jobs/{id}   The job's files, as a tar.
 *   DELETE /music-jobs/{id}   Removes the job.
 *   POST   /music-login       Gives the command center a new account.
 *
 * The command center makes every choice (best match, a broader second try),
 * so a download is just a mode and a name. Dropping the connection while the
 * run streams stops it on the command center too.
 */
public final class Music {

    public static final String SONG = "song";
    public static final String ALBUM = "album";

    /** A log line from the run, as it arrives. */
    public interface Listener {
        void line(String text, boolean error);
    }

    /** Takes each fetched file; the stream ends at the end of that file. */
    public interface Sink {
        void file(String path, long size, InputStream body) throws IOException, InterruptedException;
    }

    /** A finished run: the job holding its files, if any, and why it failed, if it did. */
    public static final class Result {
        public final String error;
        public final String job;
        public final List<String> paths;
        public final long bytes;

        Result(String error, String job, List<String> paths, long bytes) {
            this.error = error;
            this.job = job;
            this.paths = paths;
            this.bytes = bytes;
        }
    }

    private final Http http = new Http();
    private final Login login;
    private volatile Http.Stream current;
    private volatile boolean cancelled;

    public Music(Login login) {
        if (!login.ready()) throw new IllegalArgumentException("There is no command center login");
        this.login = login;
    }

    /** Stops whatever is streaming, from any thread. */
    public void cancel() {
        cancelled = true;
        Http.Stream stream = current;
        if (stream != null) stream.close();
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** Runs a download on the command center, passing on its log, until it is done. */
    public Result run(String mode, String query, Listener listener) throws IOException, InterruptedException {
        if (!SONG.equals(mode) && !ALBUM.equals(mode)) throw new IllegalArgumentException("Unknown mode");
        String body = Json.write(Json.map("mode", mode, "query", query.trim()));
        Http.Stream stream = open(request("/music").post(body).header("Content-Type", "application/json").identity());
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream.stream(), "UTF-8"));
            for (String line; (line = reader.readLine()) != null; ) {
                if (cancelled) throw new InterruptedException();
                Map<String, Object> event;
                try {
                    event = Json.object(Json.parse(line));
                } catch (RuntimeException e) {
                    continue;
                }
                if (event.get("stdout") instanceof String) {
                    listener.line((String) event.get("stdout"), false);
                } else if (event.get("stderr") instanceof String) {
                    listener.line((String) event.get("stderr"), true);
                } else if (event.get("done") instanceof Map) {
                    return result(Json.object(event.get("done")));
                }
            }
        } catch (IOException e) {
            if (cancelled) throw new InterruptedException();
            throw e;
        } finally {
            stream.close();
            current = null;
        }
        if (cancelled) throw new InterruptedException();
        throw new IOException("Lost the connection to the command center before the download finished.");
    }

    static Result result(Map<String, Object> done) {
        List<String> paths = new ArrayList<String>();
        long bytes = 0;
        for (Object value : Json.children(done, "files")) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> file = Json.object(value);
            String path = Json.text(file, "path");
            if (path == null) continue;
            paths.add(path);
            Double size = Json.number(file, "size");
            if (size != null) bytes += size.longValue();
        }
        String error = Json.text(done, "error");
        String job = Json.text(done, "job");
        return new Result(error == null || error.length() == 0 ? null : error,
            job == null || job.length() == 0 ? null : job, paths, bytes);
    }

    /** Hands each of a finished job's files to `sink`, then removes the job. */
    public int fetch(String job, Sink sink) throws IOException, InterruptedException {
        Http.Stream stream = open(request("/music-jobs/" + segment(job)).identity());
        int count = 0;
        try {
            Tar tar = new Tar(stream.stream());
            for (Tar.Entry entry; (entry = tar.next()) != null; ) {
                if (cancelled) throw new InterruptedException();
                sink.file(entry.name, entry.size, tar.body());
                count++;
            }
        } catch (IOException e) {
            if (cancelled) throw new InterruptedException();
            throw e;
        } finally {
            stream.close();
            current = null;
        }
        return count;
    }

    /** Removes a job from the command center; one already gone counts as removed. */
    public void delete(String job) throws IOException, InterruptedException {
        Http.Response response = http.send(request("/music-jobs/" + segment(job)).method("DELETE"));
        if (response.status() == 404) return;
        require(response.status(), response.body());
    }

    /** Gives the command center a new music account. Not for routine use. */
    public void relogin() throws IOException, InterruptedException {
        Http.Response response = http.send(request("/music-login").post(""));
        require(response.status(), response.body());
    }

    private Http.Stream open(Http.Request request) throws IOException, InterruptedException {
        if (cancelled) throw new InterruptedException();
        Http.Stream stream = http.stream(request);
        current = stream;
        if (cancelled) {
            stream.close();
            throw new InterruptedException();
        }
        if (stream.status() / 100 != 2) {
            String text;
            try {
                text = readAll(stream.stream());
            } catch (IOException e) {
                text = "";
            } finally {
                stream.close();
                current = null;
            }
            require(stream.status(), text);
        }
        return stream;
    }

    private Http.Request request(String path) {
        return Http.Request.to(URI.create(login.server() + path))
            .header("Authorization", "Bearer " + login.token())
            .header("Accept", "application/json")
            .timeout(60000);
    }

    /** An error status as a sentence: the command center's own message when it sent one. */
    static void require(int status, String body) throws IOException {
        if (status / 100 == 2) return;
        String message = null;
        try {
            message = Json.text(Json.object(Json.parse(body)), "error");
        } catch (RuntimeException ignored) {
            // Not JSON; fall back to the status.
        }
        if (status == 401) throw new IOException("The command center refused the token.");
        if (message != null && message.length() > 0) throw new IOException(message);
        throw new IOException("The command center answered " + status + ".");
    }

    private static String segment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        char[] buffer = new char[4096];
        for (int count; (count = reader.read(buffer)) >= 0 && out.length() < 65536; ) out.append(buffer, 0, count);
        return out.toString();
    }
}
