package com.print.xp3.ipp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * IPP over HTTP/1.1 to one printer: a POST of the message and any document,
 * with a Content-Length (every printer takes that; not all take chunked
 * bodies), on a fresh connection each time.
 *
 * Over TLS (ipps), any certificate is taken. A printer's certificate is
 * self-signed, made by the printer for itself, so there is nothing to check
 * it against; TLS here only keeps the document from being read off the air.
 */
public final class IppClient {

    /** Told how much of the document has gone, as it goes. */
    public interface Progress {
        void sent(long done, long total);
    }

    /** Raised for an HTTP answer that is not IPP: 401, 404, 500 and the rest. */
    public static final class HttpException extends IOException {
        public final int status;

        HttpException(int status, String reason) {
            super(describe(status, reason));
            this.status = status;
        }

        private static String describe(int status, String reason) {
            if (status == 401 || status == 403) return "The printer wants a password";
            if (status == 404) return "No printer at that address";
            return "The printer answered " + status + (reason.isEmpty() ? "" : " " + reason);
        }
    }

    private static final Charset ASCII = StandardCharsets.US_ASCII;
    private static final int CONNECT_MILLIS = 5000;
    private static final int LIMIT = 4 << 20;

    public final String host;
    public final int port;
    public final String path;
    private boolean tls;
    private int readMillis = 30000;
    private int nextId = 1;

    public IppClient(String host, int port, String path, boolean tls) {
        this.host = host;
        this.port = port;
        this.path = path.startsWith("/") ? path : "/" + path;
        this.tls = tls;
    }

    public IppClient readTimeout(int millis) {
        readMillis = millis;
        return this;
    }

    public boolean tls() {
        return tls;
    }

    /** The printer-uri requests name, made from the address it was reached at. */
    public String uri() {
        String scheme = tls ? "ipps" : "ipp";
        String hostPart = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return scheme + "://" + hostPart + (port == 631 ? "" : ":" + port) + path;
    }

    public int id() {
        return nextId++;
    }

    public Ipp.Message send(Ipp.Message request) throws IOException {
        return send(request, null, null);
    }

    /**
     * Sends `request` and, after it, the whole of `document` (may be null),
     * and returns the printer's answer. A printer that says to upgrade
     * (426) is asked again over TLS.
     */
    public Ipp.Message send(Ipp.Message request, File document, Progress progress) throws IOException {
        try {
            return exchange(request, document, progress);
        } catch (HttpException e) {
            if (e.status != 426 || tls) throw e;
            tls = true;
            return exchange(request, document, progress);
        }
    }

    private Ipp.Message exchange(Ipp.Message request, File document, Progress progress) throws IOException {
        byte[] head = Ipp.encode(request);
        long total = document == null ? 0 : document.length();
        Socket socket = open();
        try {
            OutputStream out = socket.getOutputStream();
            String hostHeader = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
            String header = "POST " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Content-Type: application/ipp\r\n"
                + "Content-Length: " + (head.length + total) + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
            IOException failed = null;
            try {
                out.write(header.getBytes(ASCII));
                out.write(head);
                if (document != null) copy(document, out, progress);
                out.flush();
            } catch (IOException e) {
                // A printer that refuses a job may answer and close before
                // reading all of it: its answer says why, so look for it.
                failed = e;
            }
            InputStream in = socket.getInputStream();
            byte[] body;
            try {
                body = response(in);
            } catch (IOException e) {
                throw failed != null ? failed : e;
            }
            return Ipp.decode(body);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
    }

    private Socket open() throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), CONNECT_MILLIS);
        plain.setSoTimeout(readMillis);
        if (!tls) return plain;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new AnyCertificate()}, null);
            SSLSocket secure = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true);
            secure.setSoTimeout(readMillis);
            secure.startHandshake();
            return secure;
        } catch (IOException e) {
            plain.close();
            throw e;
        } catch (Exception e) {
            plain.close();
            throw new IOException("TLS: " + e.getMessage(), e);
        }
    }

    private static void copy(File file, OutputStream out, Progress progress) throws IOException {
        long total = file.length();
        long done = 0;
        byte[] buffer = new byte[64 * 1024];
        InputStream in = new FileInputStream(file);
        try {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                done += read;
                if (progress != null) progress.sent(done, total);
            }
        } finally {
            in.close();
        }
    }

    /** Reads an HTTP response and returns its body, or throws for a status other than 200. */
    static byte[] response(InputStream in) throws IOException {
        while (true) {
            String status = line(in);
            if (status == null) throw new IOException("The printer closed the connection");
            String[] parts = status.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("Not an HTTP answer");
            int code;
            try {
                code = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("Not an HTTP answer");
            }
            long length = -1;
            boolean chunked = false;
            String headerLine;
            while ((headerLine = line(in)) != null && !headerLine.isEmpty()) {
                int colon = headerLine.indexOf(':');
                if (colon < 0) continue;
                String name = headerLine.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = headerLine.substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    try {
                        length = Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                        // Read to the close instead.
                    }
                } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                    chunked = true;
                }
            }
            if (code == 100) continue;
            byte[] body = chunked ? chunked(in) : length >= 0 ? exactly(in, length) : toClose(in);
            if (code != 200) throw new HttpException(code, parts.length > 2 ? parts[2] : "");
            return body;
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') out.write(b);
            if (out.size() > 8192) throw new IOException("HTTP line too long");
        }
        if (b < 0 && out.size() == 0) return null;
        return new String(out.toByteArray(), ASCII);
    }

    private static byte[] exactly(InputStream in, long length) throws IOException {
        if (length > LIMIT) throw new IOException("The printer's answer is too large");
        byte[] out = new byte[(int) length];
        int at = 0;
        while (at < out.length) {
            int read = in.read(out, at, out.length - at);
            if (read < 0) throw new IOException("The printer's answer was cut short");
            at += read;
        }
        return out;
    }

    private static byte[] toClose(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            if (out.size() > LIMIT) throw new IOException("The printer's answer is too large");
        }
        return out.toByteArray();
    }

    private static byte[] chunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = line(in);
            if (sizeLine == null) throw new IOException("The printer's answer was cut short");
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Bad chunk in the printer's answer");
            }
            if (size == 0) {
                String trailer;
                while ((trailer = line(in)) != null && !trailer.isEmpty()) {
                    // Trailers carry nothing needed here.
                }
                return out.toByteArray();
            }
            if (out.size() + size > LIMIT) throw new IOException("The printer's answer is too large");
            out.write(exactly(in, size));
            line(in);
        }
    }

    private static final class AnyCertificate implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}

