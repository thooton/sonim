package com.list.xp3.net;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Fixed deadlines, bounded bodies, and no credential-bearing redirects.
 *
 * This is HttpURLConnection rather than java.net.http because the phone this
 * app runs on does not have the latter: java.net.http arrived in Java 11 and
 * Android has never shipped it. What that costs is the overall request deadline
 * the newer client gives you for free, so it is kept by hand here: every read
 * checks the clock, and a body that arrives slowly enough to outlast the
 * deadline fails the same way one that never arrives does.
 *
 * Content encoding is asked for and handled explicitly rather than left to the
 * platform. Android transparently gzips a request that does not set
 * Accept-Encoding itself and a desktop JVM does not, so stating it is the only
 * way the same code behaves the same in the tests and on the handset.
 */
public final class Http {

    /** The ceiling on any single response body, streamed or not. */
    private static final int MAX_BODY = 8000000;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    /** Per-read, not overall: the overall deadline is Request.timeoutMs. */
    private static final int READ_TIMEOUT_MS = 60000;

    public Response send(Request request) throws IOException, InterruptedException {
        HttpURLConnection connection = open(request);
        try {
            long deadline = System.currentTimeMillis() + request.timeoutMs;
            int status = connection.getResponseCode();
            String body = read(body(connection, status), connection.getContentEncoding(), deadline);
            return new Response(status, body, headers(connection));
        } catch (IOException e) {
            throw friendly(e);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * The handful of network failures worth naming.
     *
     * Everything here ends up on a 240px screen in front of somebody who wants
     * to know whether to try again or to go and fix something, and
     * "java.net.UnknownHostException: Unable to resolve host" answers neither.
     * Anything already phrased for a person passes through untouched.
     */
    private static IOException friendly(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return new IOException("The connection timed out. Try again.");
        }
        if (e instanceof java.net.UnknownHostException) {
            return new IOException("No network, or that address does not exist.");
        }
        if (e instanceof java.net.ConnectException) {
            return new IOException("Could not connect. Check the network.");
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return new IOException("The secure connection failed.");
        }
        return e;
    }

    /**
     * Opens a response for reading as it arrives, which is what lets an answer
     * appear in the chat a word at a time instead of all at once after a
     * minute of nothing. The caller closes it, and closing it disconnects,
     * which is also how a turn is cancelled from the other thread.
     */
    public Stream stream(Request request) throws IOException, InterruptedException {
        HttpURLConnection connection = open(request);
        try {
            int status = connection.getResponseCode();
            InputStream raw = body(connection, status);
            InputStream decoded = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                ? new GZIPInputStream(raw)
                : raw;
            String type = connection.getHeaderField("Content-Type");
            return new Stream(connection, status, type == null ? "" : type, decoded);
        } catch (IOException e) {
            connection.disconnect();
            throw friendly(e);
        }
    }

    private HttpURLConnection open(Request request) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        HttpURLConnection connection = (HttpURLConnection) request.uri.toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        // A redirect is refused rather than followed: every request here carries
        // a credential, and the whole point of pinning an origin is lost the
        // moment the client will re-send that credential wherever it is sent.
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestMethod(request.body == null ? "GET" : "POST");
        for (Map.Entry<String, String> header : request.headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        connection.setRequestProperty("Accept-Encoding", request.gzip ? "gzip" : "identity");
        if (request.body != null) {
            try {
                byte[] payload = request.body.getBytes("UTF-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            } catch (IOException e) {
                // Sending the body is where a connect timeout or a TLS failure
                // actually surfaces, and the caller has nothing to close yet.
                connection.disconnect();
                throw e;
            }
        }
        return connection;
    }

    /** An error status has its body on the error stream, and may have none at all. */
    private static InputStream body(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        return stream == null ? new java.io.ByteArrayInputStream(new byte[0]) : stream;
    }

    private static String read(InputStream raw, String encoding, long deadline)
        throws IOException, InterruptedException {
        InputStream in = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(raw) : raw;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (System.currentTimeMillis() > deadline) throw new IOException("Request timed out; try again");
                int count = in.read(buffer);
                if (count < 0) break;
                if (out.size() + count > MAX_BODY) throw new IOException("Response exceeds 8 MB");
                out.write(buffer, 0, count);
            }
        } finally {
            in.close();
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    /**
     * Header values in the order they arrived, keyed case-insensitively.
     *
     * getHeaderFields would be shorter, but its map is only case-insensitive on
     * some implementations.
     */
    private static Map<String, List<String>> headers(HttpURLConnection connection) {
        Map<String, List<String>> all = new LinkedHashMap<String, List<String>>();
        for (int i = 0; ; i++) {
            String key = connection.getHeaderFieldKey(i);
            String value = connection.getHeaderField(i);
            if (key == null && value == null) break;
            if (key == null) continue; // The status line, which has no name.
            String lower = key.toLowerCase(Locale.US);
            List<String> values = all.get(lower);
            if (values == null) {
                values = new ArrayList<String>();
                all.put(lower, values);
            }
            values.add(value);
        }
        return all;
    }

    /**
     * Turns a status into an error a person can act on, without ever putting
     * the response body in it: bodies here can carry credentials or private data, and an error message is the one string in the app
     * guaranteed to be shown on screen.
     */
    public static void requireSuccess(int status, String service) throws IOException {
        if (status >= 200 && status < 300) return;
        String hint;
        if (status == 400) hint = "request rejected";
        else if (status == 401) hint = "login expired or invalid";
        else if (status == 403) hint = "this account is not permitted to do that";
        else if (status == 404) hint = "the model was not found";
        else if (status == 429) hint = "rate or usage limit reached; wait before retrying";
        else if (status >= 300 && status < 400) hint = "redirect refused";
        else hint = "service request failed";
        throw new IOException(service + " HTTP " + status + ": " + hint);
    }

    /** One request, built up and then sent. Immutable once handed to send. */
    public static final class Request {

        final URI uri;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        String body;
        int timeoutMs = 180000;
        boolean gzip = true;

        private Request(URI uri) {
            this.uri = uri;
        }

        public static Request to(URI uri) {
            return new Request(uri);
        }

        public Request header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Request post(String value) {
            body = value;
            return this;
        }

        public Request timeout(int milliseconds) {
            timeoutMs = milliseconds;
            return this;
        }

        /**
         * Compression off, for a response read as it arrives. A gzip stream
         * hands nothing back until it has a block to inflate, which is the
         * difference between an answer that types itself out and one that
         * appears in paragraphs.
         */
        public Request identity() {
            gzip = false;
            return this;
        }
    }

    public static final class Response {

        private final int status;
        private final String body;
        private final Map<String, List<String>> headers;

        Response(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.US));
            return values == null || values.isEmpty() ? "" : values.get(0);
        }

        public List<String> all(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.US));
            return values == null ? new ArrayList<String>() : values;
        }
    }

    /** A response being read as it arrives. Closing it ends the exchange. */
    public static final class Stream implements Closeable {

        private final HttpURLConnection connection;
        private final int status;
        private final String contentType;
        private final InputStream stream;

        Stream(HttpURLConnection connection, int status, String contentType, InputStream stream) {
            this.connection = connection;
            this.status = status;
            this.contentType = contentType;
            this.stream = stream;
        }

        public int status() {
            return status;
        }

        public String contentType() {
            return contentType;
        }

        public InputStream stream() {
            return stream;
        }

        @Override
        public void close() {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Closing a stream that already failed is not a second failure.
            }
            connection.disconnect();
        }
    }
}
