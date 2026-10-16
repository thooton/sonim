package com.source.xp3;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A local HTTP server the tests point the real client at.
 *
 * The point of testing against a socket rather than a mocked client is that
 * everything between the call and the wire is then the code that ships: header
 * casing, Link-header pagination, an error body arriving on the error stream, a
 * response with no Content-Type. Those are exactly the places a hand-written
 * HttpURLConnection layer goes wrong, and a fake that returns prepared objects
 * would agree with the bug.
 *
 * It is deliberately tiny: one connection per thread, Content-Length only, and
 * Connection: close so nothing is reused between assertions.
 */
public final class TestServer implements Closeable {

    public interface Handler {
        Reply handle(Request request) throws Exception;
    }

    public static final class Request {

        public final String method;
        public final String path;
        public final String body;
        public final Map<String, String> headers;

        Request(String method, String path, String body, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = headers;
        }

        public String header(String name) {
            String value = headers.get(name.toLowerCase(Locale.US));
            return value == null ? "" : value;
        }
    }

    public static final class Reply {

        public int status = 200;
        public String contentType = "application/json";
        public String body = "";
        final List<String> extra = new ArrayList<>();

        public static Reply json(String body) {
            Reply reply = new Reply();
            reply.body = body;
            return reply;
        }

        public static Reply of(int status, String contentType, String body) {
            Reply reply = new Reply();
            reply.status = status;
            reply.contentType = contentType;
            reply.body = body;
            return reply;
        }

        public Reply header(String line) {
            extra.add(line);
            return this;
        }
    }

    private final ServerSocket socket;
    private final Thread thread;
    private volatile boolean running = true;
    /** Every request that arrived, for assertions about what was and was not sent. */
    public final List<Request> seen = java.util.Collections.synchronizedList(new ArrayList<Request>());

    public TestServer(final Handler handler) throws IOException {
        socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    final Socket connection;
                    try {
                        connection = socket.accept();
                    } catch (IOException e) {
                        return;
                    }
                    Thread worker = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            serve(connection, handler);
                        }
                    }, "test-server-connection");
                    worker.setDaemon(true);
                    worker.start();
                }
            }
        }, "test-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve(Socket connection, Handler handler) {
        try {
            InputStream in = connection.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] start = lines[0].split(" ");
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
                }
            }
            String body = "";
            String length = headers.get("content-length");
            if (length != null) {
                int want = Integer.parseInt(length.trim());
                byte[] buffer = new byte[want];
                int read = 0;
                while (read < want) {
                    int count = in.read(buffer, read, want - read);
                    if (count < 0) break;
                    read += count;
                }
                body = new String(buffer, 0, read, "UTF-8");
            }
            Request request = new Request(start[0], start.length > 1 ? start[1] : "/", body, headers);
            seen.add(request);

            Reply reply;
            try {
                reply = handler.handle(request);
            } catch (Exception e) {
                reply = Reply.of(500, "text/plain", "handler failed: " + e);
            }
            byte[] payload = reply.body.getBytes("UTF-8");
            StringBuilder out = new StringBuilder();
            out.append("HTTP/1.1 ").append(reply.status).append(" X\r\n");
            if (reply.contentType != null) out.append("Content-Type: ").append(reply.contentType).append("\r\n");
            for (String line : reply.extra) out.append(line).append("\r\n");
            out.append("Content-Length: ").append(payload.length).append("\r\n");
            out.append("Connection: close\r\n\r\n");
            OutputStream stream = connection.getOutputStream();
            stream.write(out.toString().getBytes("UTF-8"));
            stream.write(payload);
            stream.flush();
        } catch (IOException ignored) {
            // A test closing a connection early is a case under test, not a fault.
        } finally {
            try {
                connection.close();
            } catch (IOException ignored) {
                // Nothing left to do about it.
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int match = 0;
        while (match < 4) {
            int c = in.read();
            if (c < 0) return head.size() == 0 ? null : head.toString("UTF-8");
            head.write(c);
            char expected = "\r\n\r\n".charAt(match);
            match = c == expected ? match + 1 : (c == '\r' ? 1 : 0);
        }
        String text = head.toString("UTF-8");
        return text.substring(0, text.length() - 4);
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + socket.getLocalPort() + path);
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Shutting down.
        }
    }
}
