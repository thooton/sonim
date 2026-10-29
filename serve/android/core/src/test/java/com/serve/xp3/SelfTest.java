package com.serve.xp3;

import com.serve.xp3.net.Listing;
import com.serve.xp3.net.Mime;
import com.serve.xp3.net.Paths;
import com.serve.xp3.net.Ranges;
import com.serve.xp3.net.Server;
import com.xp3.common.Check;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Runs on a desktop JVM via `:core:selfTest`: the parsing, then a real server on loopback. */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        final Check check = new Check();

        check.run("paths", () -> {
            check.same("root", Arrays.<String>asList(), Paths.segments("/"));
            check.same("decoded", Arrays.asList("My Music", "a+b"), Paths.segments("/My%20Music/a+b"));
            check.same("utf-8", Arrays.asList("café"), Paths.segments("/caf%C3%A9"));
            check.same("dots climb but stop at the root", Arrays.asList("etc"), Paths.segments("/a/../../../etc"));
            check.same("an escaped slash is refused", null, Paths.segments("/a%2F..%2F..%2Fx"));
            check.same("a bad escape is refused", null, Paths.segments("/a%zz"));
            check.same("a truncated escape is refused", null, Paths.segments("/a%2"));
            check.same("a NUL is refused", null, Paths.segments("/a%00b"));
            check.same("join folder", "/a/b/", Paths.join(Arrays.asList("a", "b"), true));
            check.same("join root", "/", Paths.join(new ArrayList<>(), false));
            check.same("href", "/My%20Music/caf%C3%A9%23%3F/", Paths.href("/My Music/café#?/"));
        });

        check.run("ranges", () -> {
            check.same("open", "[10, 99]", Arrays.toString(Ranges.parse("bytes=10-", 100)));
            check.same("closed", "[0, 9]", Arrays.toString(Ranges.parse("bytes=0-9", 100)));
            check.same("clamped", "[90, 99]", Arrays.toString(Ranges.parse("bytes=90-500", 100)));
            check.same("suffix", "[80, 99]", Arrays.toString(Ranges.parse("bytes=-20", 100)));
            check.same("past the end", null, Ranges.parse("bytes=100-", 100));
            check.same("several are ignored", 0, Ranges.parse("bytes=0-1,5-6", 100).length);
            check.same("backwards is ignored", 0, Ranges.parse("bytes=9-3", 100).length);
            check.same("other units are ignored", 0, Ranges.parse("items=0-1", 100).length);
        });

        check.run("mime", () -> {
            check.same("mp3", "audio/mpeg", Mime.of("Song.MP3"));
            check.same("text", "text/plain; charset=utf-8", Mime.of("notes.txt"));
            check.same("unknown", "application/octet-stream", Mime.of("README"));
        });

        check.run("listing", () -> {
            String html = Listing.render("/a b/c/", Arrays.asList(
                new Listing.Entry("zeta.txt", false),
                new Listing.Entry("Beta", true),
                new Listing.Entry("alpha.png", false),
                new Listing.Entry("<x>", false)));
            check.ok("title", html.contains("<title>Files within /a b/c/</title>"));
            check.ok("breadcrumb", html.contains("<a href=\"/\">/</a><a href=\"/a%20b/\">a b/</a><a href=\"/a%20b/c/\">c/</a>"));
            check.ok("parent", html.contains("<a href=\"/a%20b/\" title=\"..\" class=\"folder\">..</a>"));
            check.ok("folder", html.contains("<a href=\"/a%20b/c/Beta/\" title=\"Beta\" class=\"folder\">Beta/</a>"));
            check.ok("image", html.contains("class=\"file png\">alpha.png</a>"));
            check.ok("escaped", html.contains(">&lt;x&gt;</a>") && !html.contains("<x>"));
            int parent = html.indexOf(">..</a>");
            int folder = html.indexOf(">Beta/</a>");
            int alpha = html.indexOf(">alpha.png</a>");
            int zeta = html.indexOf(">zeta.txt</a>");
            check.ok("order", parent < folder && folder < alpha && alpha < zeta);
            check.ok("no parent at the root", !Listing.render("/", new ArrayList<>()).contains(">..</a>"));
        });

        File root = new File(System.getProperty("java.io.tmpdir"), "serve-selftest-" + System.nanoTime());
        File outside = new File(root.getParentFile(), root.getName() + "-outside");
        try {
            write(new File(root, "hello.txt"), "hello, world");
            write(new File(root, "My Music/song.mp3"), "0123456789");
            write(new File(root, "site/index.html"), "<p>home</p>");
            write(new File(root, ".git/config"), "secret");
            write(new File(outside, "secret.txt"), "secret");
            new File(root, "empty").mkdirs();
            try {
                java.nio.file.Files.createSymbolicLink(new File(root, "escape").toPath(), outside.toPath());
            } catch (Exception e) {
                // No symlinks here; that case is then not tested.
            }

            final Server server = new Server(root, 0);
            server.start();
            try {
                final int port = server.port();
                check.run("server", () -> {
                    Response list = get(port, "GET / HTTP/1.1");
                    check.same("listing status", 200, list.status);
                    check.same("listing type", "text/html; charset=utf-8", list.headers.get("content-type"));
                    check.ok("lists a file", list.body.contains("href=\"/hello.txt\""));
                    check.ok("lists a folder", list.body.contains("href=\"/My%20Music/\""));
                    check.ok("hides .git", !list.body.contains(".git"));

                    Response file = get(port, "GET /hello.txt HTTP/1.1");
                    check.same("file", "hello, world", file.body);
                    check.same("file type", "text/plain; charset=utf-8", file.headers.get("content-type"));
                    check.same("accepts ranges", "bytes", file.headers.get("accept-ranges"));

                    Response part = get(port, "GET /My%20Music/song.mp3 HTTP/1.1\r\nRange: bytes=3-5");
                    check.same("range status", 206, part.status);
                    check.same("range body", "345", part.body);
                    check.same("content-range", "bytes 3-5/10", part.headers.get("content-range"));

                    Response miss = get(port, "GET /hello.txt HTTP/1.1\r\nRange: bytes=50-");
                    check.same("unsatisfiable", 416, miss.status);

                    Response head = get(port, "HEAD /hello.txt HTTP/1.1");
                    check.same("head length", "12", head.headers.get("content-length"));
                    check.same("head body", "", head.body);

                    Response bare = get(port, "GET /My%20Music?x=1 HTTP/1.1");
                    check.same("folder without a slash", 301, bare.status);
                    check.same("redirect", "/My%20Music/?x=1", bare.headers.get("location"));

                    check.same("index.html", "<p>home</p>", get(port, "GET /site/ HTTP/1.1").body);
                    check.same("not found", 404, get(port, "GET /nope HTTP/1.1").status);
                    check.ok("404 page", get(port, "GET /nope HTTP/1.1").body.contains("could not be found"));
                    check.same(".git is not served", 404, get(port, "GET /.git/config HTTP/1.1").status);
                    check.same("dots stay inside", 404,
                        get(port, "GET /../" + outsideName(root) + "/secret.txt HTTP/1.1").status);
                    check.same("symlinks stay inside", 404, get(port, "GET /escape/secret.txt HTTP/1.1").status);
                    check.same("post", 405, get(port, "POST / HTTP/1.1\r\nContent-Length: 0").status);
                    check.same("garbage", 400, get(port, "WHAT").status);
                    check.ok("empty folder", get(port, "GET /empty/ HTTP/1.1").body.contains(">..</a>"));

                    // Two requests down one connection.
                    Socket socket = new Socket("127.0.0.1", port);
                    try {
                        OutputStream out = socket.getOutputStream();
                        InputStream in = socket.getInputStream();
                        out.write("GET /hello.txt HTTP/1.1\r\nHost: x\r\n\r\n".getBytes("UTF-8"));
                        check.same("keep-alive first", "hello, world", Response.read(in).body);
                        out.write("GET /My%20Music/song.mp3 HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
                        Response second = Response.read(in);
                        check.same("keep-alive second", "0123456789", second.body);
                        check.same("then closes", "close", second.headers.get("connection"));
                    } finally {
                        socket.close();
                    }
                });
            } finally {
                server.stop();
            }

            check.run("stop", () -> {
                final Server again = new Server(new File(System.getProperty("java.io.tmpdir")), 0);
                again.start();
                int port = again.port();
                again.stop();
                check.fails("refuses after stopping", () -> {
                    Thread.sleep(50);
                    get(port, "GET / HTTP/1.1");
                });
            });

            check.run("rebinds the same port straight after stopping", () -> {
                Server first = new Server(root, 0);
                first.start();
                int port = first.port();
                first.stop();
                Server next = new Server(root, port);
                next.start();
                try {
                    check.same("served again", 200, get(port, "GET /hello.txt HTTP/1.1").status);
                } finally {
                    next.stop();
                }
            });
        } finally {
            delete(root);
            delete(outside);
        }

        if (!check.report()) System.exit(1);
    }

    private static String outsideName(File root) {
        return root.getName() + "-outside";
    }

    private static Response get(int port, String head) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(5000);
            String request = head + "\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            if (!head.contains("HTTP/")) request = head + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes("UTF-8"));
            return Response.read(socket.getInputStream());
        } finally {
            socket.close();
        }
    }

    private static final class Response {
        int status;
        final Map<String, String> headers = new HashMap<>();
        String body = "";

        static Response read(InputStream in) throws IOException {
            Response response = new Response();
            String status = line(in);
            response.status = Integer.parseInt(status.split(" ")[1]);
            for (String header = line(in); header.length() > 0; header = line(in)) {
                int colon = header.indexOf(':');
                response.headers.put(header.substring(0, colon).trim().toLowerCase(Locale.US),
                    header.substring(colon + 1).trim());
            }
            String length = response.headers.get("content-length");
            int left = length == null ? 0 : Integer.parseInt(length);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            // A HEAD answer states the length of the body it leaves out, so
            // the end of the connection ends the read too.
            byte[] buffer = new byte[4096];
            while (left > 0) {
                int n = in.read(buffer, 0, Math.min(buffer.length, left));
                if (n < 0) break;
                body.write(buffer, 0, n);
                left -= n;
            }
            response.body = body.toString("UTF-8");
            return response;
        }

        private static String line(InputStream in) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int b = in.read(); b != '\n'; b = in.read()) {
                if (b < 0) throw new IOException("closed");
                if (b != '\r') bytes.write(b);
            }
            return bytes.toString("ISO-8859-1");
        }
    }

    private static void write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static void delete(File file) {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            file.delete();
            return;
        }
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
