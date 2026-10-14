package com.delta.xp3.net;

import com.delta.xp3.data.RemoteFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Delta command center: the same few routes the `delta` CLI uses, from
 * the phone's side.
 *
 * An agent pushes a file with the CLI; this lists it, fetches it, and deletes
 * it. The phone can push one too, for an agent to `delta pull`. Every request
 * carries the bearer token `delta-cc gen-token` printed.
 */
public final class Center {

    /** Reported while a download runs, and asked whether to stop. */
    public interface Progress {
        boolean cancelled();

        /** `total` is -1 when the server did not say how big the file is. */
        void progress(long done, long total);
    }

    /** Thrown out of `download` once Progress reports a cancel. */
    public static final class Cancelled extends IOException {
        public Cancelled() {
            super("Cancelled");
        }
    }

    private static final String SERVICE = "Command center";

    private final Http http;
    private final String server;
    private final String token;

    /** `server` is an address with no trailing slash, as Config keeps it. */
    public Center(Http http, String server, String token) {
        this.http = http;
        this.server = server;
        this.token = token;
    }

    /** Whether the server answers and takes the token, as `delta set-auth` checks. */
    public void check() throws IOException, InterruptedException {
        call(request("/auth"));
    }

    /** Every file, newest first, as the command center orders them. */
    public List<RemoteFile> list() throws IOException, InterruptedException {
        String body = call(request("/files"));
        List<RemoteFile> files = new ArrayList<RemoteFile>();
        List<Object> listed;
        try {
            listed = Json.array(Json.parse(body));
        } catch (RuntimeException e) {
            throw new IOException("The command center sent an unreadable file list");
        }
        for (Object value : listed) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> entry = Json.object(value);
            String name = Json.text(entry, "name");
            Double size = Json.number(entry, "size");
            if (!RemoteFile.validName(name) || size == null) continue;
            files.add(new RemoteFile(name, size.longValue(), Json.instant(entry, "modified")));
        }
        return files;
    }

    /** Deletes a file. One that is already gone counts as deleted. */
    public void delete(String name) throws IOException, InterruptedException {
        Http.Response response = http.send(request("/files/" + segment(name)).method("DELETE"));
        if (response.status() == 404) return;
        require(response.status());
    }

    /**
     * Writes a file's bytes to `out` as they arrive. The caller owns `out`
     * and decides what a partial file is worth; a short body is an error here.
     */
    public void download(String name, OutputStream out, Progress progress)
        throws IOException, InterruptedException {
        Http.Stream stream = http.stream(request("/files/" + segment(name)).identity());
        try {
            require(stream.status());
            long total = stream.length();
            long done = 0;
            progress.progress(0, total);
            InputStream in = stream.stream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (progress.cancelled()) throw new Cancelled();
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                out.write(buffer, 0, count);
                done += count;
                progress.progress(done, total);
            }
            if (total >= 0 && done != total) throw new IOException("The download stopped part way.");
        } finally {
            stream.close();
        }
    }

    /**
     * Sends `length` bytes from `in` as a new file called `name`, as
     * `delta push` does. Returns false, having stored nothing, when the
     * command center already has a file of that name: it never replaces one.
     */
    public boolean upload(String name, InputStream in, long length, final Progress progress)
        throws IOException, InterruptedException {
        Http.Request request = request("/files/" + segment(name))
            .method("PUT")
            .header("Content-Type", "application/octet-stream")
            .timeout(120000);
        Http.Response response = http.upload(request, in, length, new Http.Sending() {
            @Override
            public void sent(long done, long total) throws IOException {
                if (progress.cancelled()) throw new Cancelled();
                progress.progress(done, total);
            }
        });
        if (response.status() == 409) return false;
        if (response.status() == 413) throw new IOException("That file is too big for the command center.");
        if (response.status() == 400) throw new IOException("The command center refused that name.");
        require(response.status());
        return true;
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private Http.Request request(String path) {
        return Http.Request.to(URI.create(server + path))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .timeout(60000);
    }

    private String call(Http.Request request) throws IOException, InterruptedException {
        Http.Response response = http.send(request);
        require(response.status());
        return response.body();
    }

    private static void require(int status) throws IOException {
        if (status == 401) throw new IOException("The command center refused the token.");
        if (status == 404) throw new IOException("That file is no longer on the command center.");
        Http.requireSuccess(status, SERVICE);
    }

    /**
     * A file name as one URL path segment. Everything but the unreserved
     * characters is escaped, so a space or a '+' reaches the server as itself
     * rather than as each other.
     */
    static String segment(String name) {
        StringBuilder out = new StringBuilder();
        byte[] bytes;
        try {
            bytes = name.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        for (byte b : bytes) {
            int c = b & 0xff;
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
            if (plain) out.append((char) c);
            else out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return out.toString();
    }
}
