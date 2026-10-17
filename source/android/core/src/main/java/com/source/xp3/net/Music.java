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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The command center's music routes, the same ones `delta music` uses
 * (cmd/delta/music.go and cmd/delta-cc/music_run.go in thooton/delta):
 *
 *   POST   /music-search      {"mode": "song"|"album", "query": "..."}; streams
 *                             JSON lines: {"stdout"}, {"stderr"}, {} keepalives
 *                             and last {"done": {"error", "results"}}.
 *   POST   /music             {"link": "slsk://..."}, one of those results;
 *                             streams the same way, ending {"done": {"error",
 *                             "job", "files"}}.
 *   GET    /music-jobs/{id}   The job's files, as a tar.
 *   DELETE /music-jobs/{id}   Removes the job.
 *   POST   /music-login       Gives the command center a new account.
 *
 * A search finds every result that meets the command center's music config,
 * best first by its preferences (a broader second try when there are none),
 * and the user picks one to download. Unlike `delta music`, Source sees the
 * engine's log as it is, Soulseek and all. Dropping the connection while a
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

    /** One file of a result, as it is shared. Numbers the sharer's client didn't give are 0. */
    public static final class Track {
        public final String path;
        public final long size;
        public final int seconds;
        public final int bitrate;
        public final int sampleRate;
        public final int bitDepth;

        Track(Map<String, Object> source) {
            path = text(source, "path");
            size = (long) count(source, "size");
            seconds = (int) count(source, "length");
            bitrate = (int) count(source, "bitrate");
            sampleRate = (int) count(source, "samplerate");
            bitDepth = (int) count(source, "bitdepth");
        }

        /** The file's name, after the last backslash. */
        public String name() {
            return path.substring(path.lastIndexOf('\\') + 1);
        }

        String format() {
            int dot = name().lastIndexOf('.');
            return dot < 0 ? "" : name().substring(dot + 1).toUpperCase(Locale.US);
        }

        /** The file's name without its extension, which its format tag shows. */
        public String title() {
            String name = name();
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
    }

    /** A search result: one file for a song, one shared folder for an album. */
    public static final class Hit {
        public final String link;
        public final String user;
        /** The sharer's upload speed, in MB/s. */
        public final double speed;
        /** Whether the sharer has an upload slot free, or would queue the download. */
        public final boolean free;
        /** An album's folder, as shared; "" for a song. */
        public final String folder;
        public final List<Track> tracks;

        Hit(Map<String, Object> source) {
            link = text(source, "link");
            user = text(source, "user");
            Double mbs = Json.number(source, "speed");
            speed = mbs == null ? 0 : mbs;
            free = Json.flag(source, "free");
            folder = text(source, "folder");
            List<Track> list = new ArrayList<Track>();
            for (Object value : Json.children(source, "files")) {
                if (value instanceof Map) list.add(new Track(Json.object(value)));
            }
            tracks = Collections.unmodifiableList(list);
        }

        public boolean album() {
            return folder.length() > 0;
        }

        /** A song's file name, or an album's folder with the one above it ("Artist / Album"). */
        public String title() {
            if (!album()) return tracks.isEmpty() ? "" : tracks.get(0).name();
            String[] parts = folder.split("\\\\");
            String last = parts[parts.length - 1];
            return parts.length > 1 ? parts[parts.length - 2] + " / " + last : last;
        }

        /** A song's file name without its extension, or an album's own folder. */
        public String name() {
            if (album()) {
                String[] parts = folder.split("\\\\");
                return parts[parts.length - 1];
            }
            return tracks.isEmpty() ? "" : tracks.get(0).title();
        }

        /**
         * The folders it sits in, as the sharer named them, joined with " / ":
         * a song's whole folder, an album's folders above its own. Shown with
         * every result, since the folders often say more than the file does.
         */
        public String folders() {
            String path = album() ? folder : where();
            if (album()) {
                int slash = path.lastIndexOf('\\');
                path = slash < 0 ? "" : path.substring(0, slash);
            }
            return path.replace("\\", " / ");
        }

        /** "MP3", "FLAC/MP3": every format in it, as its format tag. */
        public String formats() {
            Set<String> formats = new LinkedHashSet<String>();
            for (Track t : tracks) if (t.format().length() > 0) formats.add(t.format());
            return join(formats, "/");
        }

        /** Every byte of it. */
        public long bytes() {
            long bytes = 0;
            for (Track t : tracks) bytes += t.size;
            return bytes;
        }

        /** As meta(), without the formats, which a tag beside it shows. */
        public String specs() {
            return describe(false);
        }

        /** "MP3 · 320 kbps · 4:12 · 8.1 MB"; an album leads with its file count. Unknowns are left out. */
        public String meta() {
            return describe(true);
        }

        private String describe(boolean withFormats) {
            Set<String> formats = new LinkedHashSet<String>();
            int low = Integer.MAX_VALUE;
            int high = 0;
            long seconds = 0;
            long bytes = 0;
            int depth = 0;
            int rate = 0;
            for (Track t : tracks) {
                if (t.format().length() > 0) formats.add(t.format());
                if (t.bitrate > 0) {
                    low = Math.min(low, t.bitrate);
                    high = Math.max(high, t.bitrate);
                }
                seconds += t.seconds;
                bytes += t.size;
                depth = Math.max(depth, t.bitDepth);
                rate = Math.max(rate, t.sampleRate);
            }
            List<String> parts = new ArrayList<String>();
            if (album()) parts.add(tracks.size() + (tracks.size() == 1 ? " file" : " files"));
            if (withFormats && !formats.isEmpty()) parts.add(join(formats, "/"));
            if (high > 0) parts.add((low == high ? "" : low + "-") + high + " kbps");
            if (depth > 0 && rate > 0) parts.add(depth + "/" + trim(rate / 1000.0) + " kHz");
            if (seconds > 0) parts.add(clock(seconds));
            if (bytes > 0) parts.add(human(bytes));
            return join(parts, " · ");
        }

        /** Who shares it and how fast: "bob · 1.2 MB/s", "· queued" without a free slot. */
        public String source() {
            String out = user + " · " + String.format(Locale.US, "%.1f MB/s", speed);
            return free ? out : out + " · queued";
        }

        /** Where it is in the sharer's files: a song's folder, an album's own. */
        public String where() {
            if (album()) return folder;
            String path = tracks.isEmpty() ? "" : tracks.get(0).path;
            int slash = path.lastIndexOf('\\');
            return slash < 0 ? "" : path.substring(0, slash);
        }
    }

    /** A finished search: the results, best first, and why there are none, if there aren't. */
    public static final class Found {
        public final String mode;
        public final String query;
        public final String error;
        public final List<Hit> hits;

        Found(String mode, String query, String error, List<Hit> hits) {
            this.mode = mode;
            this.query = query;
            this.error = error;
            this.hits = Collections.unmodifiableList(hits);
        }
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

    /** Searches on the command center, passing on its log, for the results to choose from. */
    public Found search(String mode, String query, Listener listener) throws IOException, InterruptedException {
        if (!SONG.equals(mode) && !ALBUM.equals(mode)) throw new IllegalArgumentException("Unknown mode");
        String trimmed = query.trim();
        Map<String, Object> done = stream("/music-search", Json.map("mode", mode, "query", trimmed), listener, "search");
        List<Hit> hits = new ArrayList<Hit>();
        for (Object value : Json.children(done, "results")) {
            if (!(value instanceof Map)) continue;
            Hit hit = new Hit(Json.object(value));
            if (hit.link.length() > 0 && !hit.tracks.isEmpty()) hits.add(hit);
        }
        String error = Json.text(done, "error");
        return new Found(mode, trimmed, hits.isEmpty() && error != null && error.length() > 0 ? error : null, hits);
    }

    /** Downloads one search result on the command center, passing on its log, until it is done. */
    public Result download(Hit hit, Listener listener) throws IOException, InterruptedException {
        return result(stream("/music", Json.map("link", hit.link), listener, "download"));
    }

    /** Posts `body` and passes on the run's log, returning its "done". */
    private Map<String, Object> stream(String path, Map<String, Object> body, Listener listener, String what)
            throws IOException, InterruptedException {
        Http.Stream stream = open(request(path).post(Json.write(body)).header("Content-Type", "application/json").identity());
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
                    return Json.object(event.get("done"));
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
        throw new IOException("Lost the connection to the command center before the " + what + " finished.");
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

    private static String text(Map<String, Object> source, String key) {
        String value = Json.text(source, key);
        return value == null ? "" : value;
    }

    private static double count(Map<String, Object> source, String key) {
        Double value = Json.number(source, key);
        return value == null || value < 0 ? 0 : value;
    }

    static String join(Iterable<String> parts, String between) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(between);
            out.append(part);
        }
        return out.toString();
    }

    /** 44.1 or 48, without a needless ".0". */
    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.format(Locale.US, "%.1f", value);
    }

    /** 4:05, or 1:02:05 past an hour. */
    public static String clock(long seconds) {
        if (seconds >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        char[] buffer = new char[4096];
        for (int count; (count = reader.read(buffer)) >= 0 && out.length() < 65536; ) out.append(buffer, 0, count);
        return out.toString();
    }
}
