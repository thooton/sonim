package com.serve.xp3.net;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * A static file server for one folder, after `npx serve`: a folder with an
 * index.html is that page, any other folder is a listing (see {@link Listing}),
 * and a file is sent as it is, with byte ranges so audio and video can seek.
 *
 * GET and HEAD only, HTTP/1.1 with keep-alive, a thread per connection. Paths
 * are resolved under the root and can never leave it, symlinks included.
 */
public final class Server {

    /**
     * The one port, so the address on the screen is the same every time and
     * can be bookmarked. Above 1024, so it needs no root; below 32768, where
     * Android's ephemeral ports start, so an outgoing connection never holds
     * it; and clear of the ports dev servers and phone apps reach for (3000,
     * 5000, 8000, 8080, 8888, 4747 and the like). Unassigned at IANA.
     */
    public static final int PORT = 28413;

    private static final int IDLE_MS = 15000;
    private static final int MAX_HEAD = 16 * 1024;
    private static final Set<String> UNLISTED = new HashSet<String>();
    static {
        // As serve-handler leaves out by default.
        UNLISTED.add(".git");
        UNLISTED.add(".DS_Store");
    }

    private final File root;
    private final String rootPath;
    private final int port;
    private final Set<Socket> open = Collections.synchronizedSet(new HashSet<Socket>());
    private ServerSocket listener;
    private volatile boolean stopped;

    public Server(File root, int port) throws IOException {
        this.root = root.getCanonicalFile();
        this.rootPath = this.root.getPath();
        this.port = port;
    }

    public File root() {
        return root;
    }

    /** Binds every interface on the port and starts answering. */
    public synchronized void start() throws IOException {
        if (listener != null) return;
        if (!root.isDirectory()) throw new IOException(root + " is not a folder");
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            socket.close();
            throw new IOException("Port " + port + " is in use", e);
        }
        listener = socket;
        Thread accepting = new Thread(new Runnable() {
            @Override
            public void run() {
                accept();
            }
        }, "serve-accept");
        accepting.setDaemon(true);
        accepting.start();
    }

    /** The bound port: {@link #PORT}, or the one picked for port 0. */
    public synchronized int port() {
        return listener == null ? port : listener.getLocalPort();
    }

    /** Stops listening and drops every open connection, a download included. */
    public void stop() {
        stopped = true;
        ServerSocket socket;
        synchronized (this) {
            socket = listener;
        }
        closeQuietly(socket);
        List<Socket> clients;
        synchronized (open) {
            clients = new ArrayList<Socket>(open);
        }
        for (Socket each : clients) closeQuietly(each);
    }

    private void accept() {
        ServerSocket socket;
        synchronized (this) {
            socket = listener;
        }
        while (!stopped) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                return;
            }
            open.add(client);
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        connection(client);
                    } finally {
                        open.remove(client);
                        closeQuietly(client);
                    }
                }
            }, "serve-client");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void connection(Socket client) {
        try {
            client.setSoTimeout(IDLE_MS);
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), 32 * 1024);
            while (!stopped) {
                Request request = Request.read(in, MAX_HEAD);
                if (request == null) return;
                boolean again = respond(request, out);
                out.flush();
                if (!again) return;
            }
        } catch (IOException e) {
            // Gone, timed out, or stopped: nothing more to say to it.
        }
    }

    /** Answers one request; false when the connection should close after it. */
    boolean respond(Request request, OutputStream out) throws IOException {
        boolean keepAlive = request.keepAlive();
        if (request.bad) {
            page(out, 400, "Bad Request", false, false);
            return false;
        }
        boolean head = "HEAD".equals(request.method);
        if (!head && !"GET".equals(request.method)) {
            Map<String, String> allow = new HashMap<String, String>();
            allow.put("Allow", "GET, HEAD");
            send(out, 405, "Method Not Allowed", "text/plain; charset=utf-8",
                bytes("Method Not Allowed"), allow, head, keepAlive);
            return keepAlive;
        }

        List<String> parts = Paths.segments(request.path);
        if (parts == null) {
            page(out, 400, "Bad Request", head, keepAlive);
            return keepAlive;
        }
        File target = root;
        for (String part : parts) target = new File(target, part);
        if (!inside(target) || unlisted(parts) || !target.exists()) {
            page(out, 404, "Not Found", head, keepAlive);
            return keepAlive;
        }

        if (target.isDirectory()) {
            String path = Paths.join(parts, true);
            if (!request.path.endsWith("/")) {
                Map<String, String> location = new HashMap<String, String>();
                location.put("Location", Paths.href(path) + request.query);
                send(out, 301, "Moved Permanently", "text/plain; charset=utf-8",
                    bytes("Redirecting to " + path), location, head, keepAlive);
                return keepAlive;
            }
            File index = new File(target, "index.html");
            if (index.isFile()) {
                file(out, index, request, head, keepAlive);
            } else {
                byte[] body = bytes(Listing.render(path, entries(target)));
                send(out, 200, "OK", "text/html; charset=utf-8", body, null, head, keepAlive);
            }
            return keepAlive;
        }
        if (!target.isFile() || !target.canRead()) {
            page(out, 404, "Not Found", head, keepAlive);
            return keepAlive;
        }
        file(out, target, request, head, keepAlive);
        return keepAlive;
    }

    private List<Listing.Entry> entries(File folder) {
        List<Listing.Entry> entries = new ArrayList<Listing.Entry>();
        String[] names = folder.list();
        if (names == null) return entries;
        for (String name : names) {
            if (UNLISTED.contains(name)) continue;
            File each = new File(folder, name);
            if (!inside(each)) continue;
            entries.add(new Listing.Entry(name, each.isDirectory()));
        }
        return entries;
    }

    private boolean inside(File file) {
        try {
            String path = file.getCanonicalPath();
            return path.equals(rootPath) || path.startsWith(rootPath.endsWith(File.separator)
                ? rootPath : rootPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean unlisted(List<String> parts) {
        for (String part : parts) if (UNLISTED.contains(part)) return true;
        return false;
    }

    private void file(OutputStream out, File file, Request request, boolean head, boolean keepAlive)
            throws IOException {
        long length = file.length();
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Last-Modified", httpDate(file.lastModified()));

        long start = 0;
        long end = length - 1;
        int status = 200;
        String reason = "OK";
        String range = request.headers.get("range");
        if (range != null) {
            long[] span = Ranges.parse(range, length);
            if (span == null) {
                headers.put("Content-Range", "bytes */" + length);
                send(out, 416, "Range Not Satisfiable", "text/plain; charset=utf-8",
                    bytes("Range Not Satisfiable"), headers, head, keepAlive);
                return;
            }
            if (span.length == 2) {
                start = span[0];
                end = span[1];
                status = 206;
                reason = "Partial Content";
                headers.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
            }
        }

        long count = end - start + 1;
        status(out, status, reason, Mime.of(file.getName()), count, headers, keepAlive);
        if (head || count <= 0) return;
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            input.seek(start);
            byte[] buffer = new byte[32 * 1024];
            long left = count;
            while (left > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) throw new IOException("File shrank while it was being sent");
                out.write(buffer, 0, read);
                left -= read;
            }
        } finally {
            input.close();
        }
    }

    private static void page(OutputStream out, int status, String reason, boolean head, boolean keepAlive)
            throws IOException {
        send(out, status, reason, "text/html; charset=utf-8",
            bytes(Listing.error(status, reason)), null, head, keepAlive);
    }

    private static void send(OutputStream out, int status, String reason, String type, byte[] body,
            Map<String, String> headers, boolean head, boolean keepAlive) throws IOException {
        status(out, status, reason, type, body.length, headers, keepAlive);
        if (!head) out.write(body);
    }

    private static void status(OutputStream out, int status, String reason, String type, long length,
            Map<String, String> headers, boolean keepAlive) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        head.append("Date: ").append(httpDate(System.currentTimeMillis())).append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> each : headers.entrySet()) {
                head.append(each.getKey()).append(": ").append(each.getValue()).append("\r\n");
            }
        }
        head.append("\r\n");
        out.write(head.toString().getBytes("ISO-8859-1"));
    }

    private static String httpDate(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date(millis));
    }

    private static byte[] bytes(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException e) {
            // Already gone.
        }
    }

    /** One request's line and headers; the body, if any, is never read. */
    static final class Request {
        String method = "";
        String path = "/";
        String query = "";
        String version = "HTTP/1.0";
        boolean bad;
        final Map<String, String> headers = new HashMap<String, String>();

        boolean keepAlive() {
            if (bad) return false;
            String connection = headers.get("connection");
            connection = connection == null ? "" : connection.toLowerCase(Locale.US);
            if ("HTTP/1.1".equals(version)) return !connection.contains("close");
            return connection.contains("keep-alive");
        }

        /** Null when the connection closed before a request began. */
        static Request read(InputStream in, int limit) throws IOException {
            Request request = new Request();
            String line = line(in, limit);
            while (line != null && line.length() == 0) line = line(in, limit); // Stray CRLFs.
            if (line == null) return null;
            String[] words = line.split(" ");
            if (words.length != 3 || !words[1].startsWith("/")) {
                request.bad = true;
            } else {
                request.method = words[0];
                request.version = words[2];
                String target = words[1];
                int hash = target.indexOf('#');
                if (hash >= 0) target = target.substring(0, hash);
                int question = target.indexOf('?');
                if (question >= 0) {
                    request.query = target.substring(question);
                    target = target.substring(0, question);
                }
                request.path = target;
            }
            int used = line.length();
            while (true) {
                String header = line(in, limit);
                if (header == null) throw new SocketException("Closed mid-request");
                if (header.length() == 0) break;
                used += header.length();
                if (used > limit) {
                    request.bad = true;
                    break;
                }
                int colon = header.indexOf(':');
                if (colon <= 0) continue;
                request.headers.put(header.substring(0, colon).trim().toLowerCase(Locale.US),
                    header.substring(colon + 1).trim());
            }
            return request;
        }

        private static String line(InputStream in, int limit) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b < 0) {
                    if (bytes.size() == 0) return null;
                    throw new SocketException("Closed mid-line");
                }
                if (b == '\n') break;
                if (b != '\r') bytes.write(b);
                if (bytes.size() > limit) throw new SocketException("Header too long");
            }
            return bytes.toString("ISO-8859-1");
        }
    }
}
