package com.updater.xp3.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The rolling `latest` release of github.com/thooton/sonim, where CI puts every
 * app's APK beside `manifest.json`, which describes them all (see Catalog).
 *
 * Release files redirect from github.com to GitHub's file host, which
 * HttpURLConnection follows by itself (both are https). Nothing is cached:
 * the point of asking is to hear about a release made a minute ago. So every
 * request also opens a connection of its own (Android otherwise keeps one
 * open per process and reuses it, which kept Check Again on the old release
 * until the app was closed) and asks for its release file by a URL never
 * asked for before, so nothing between here and GitHub can answer from a copy.
 */
public final class Releases {

    public static final String LATEST = "https://github.com/thooton/sonim/releases/download/latest/";

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;
    /** The manifest: a few kilobytes per app, with room to spare. */
    private static final int MAX_TEXT = 2 * 1024 * 1024;
    private static final long MAX_APK = 200L * 1024 * 1024;

    /** Told how much of a download has arrived; `total` is -1 when unknown. */
    public interface Progress {
        void update(long done, long total);
    }

    private final String base;
    /** Makes each release URL one never asked for before (see fresh). */
    private final AtomicLong asked = new AtomicLong();

    public Releases() {
        this(LATEST);
    }

    /** For the tests, which serve a release of their own. */
    public Releases(String base) {
        this.base = base.endsWith("/") ? base : base + "/";
    }

    /** The apps in the release, sorted by name. */
    public List<Catalog.Entry> catalog() throws IOException {
        String json = text("manifest.json");
        try {
            return Catalog.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IOException("The release's manifest is unreadable.");
        }
    }

    /** A small text file from the release. */
    public String text(String file) throws IOException {
        return fetch(fresh(base + file), file, MAX_TEXT);
    }

    /**
     * `url` with a query of its own. GitHub ignores it, but it is a new
     * address to any cache on the way, so the answer is today's.
     */
    private String fresh(String url) {
        return url + (url.indexOf('?') < 0 ? "?" : "&") + "t="
            + System.currentTimeMillis() + "-" + asked.incrementAndGet();
    }

    private String fetch(String url, String file, int max) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            InputStream in = body(connection, file);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    if (out.size() > max) throw new IOException(file + " is far too large.");
                }
                return new String(out.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw friendly(e);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Downloads an app's APK to `to`, through `to.part` so a cut-off
     * download never looks finished, and checks it against the size and
     * SHA-256 the manifest gives. `to` is replaced if it exists.
     */
    public void download(Catalog.Entry entry, File to, Progress progress) throws IOException {
        download(entry.apk(), entry.size, entry.sha256, to, progress);
    }

    /** As above, for any release file; `size` -1 and `sha256` null check nothing. */
    public void download(String file, long size, String sha256, File to, Progress progress) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("No SHA-256 on this phone.");
        }
        File part = new File(to.getPath() + ".part");
        HttpURLConnection connection = open(fresh(base + file));
        try {
            InputStream in = body(connection, file);
            long total = connection.getContentLengthLong();
            if (total < 0) total = size;
            if (total > MAX_APK) throw new IOException(file + " is far too large.");
            long done = 0;
            try {
                OutputStream out = new FileOutputStream(part);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    if (progress != null) progress.update(0, total);
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        digest.update(buffer, 0, n);
                        done += n;
                        if (done > MAX_APK) throw new IOException(file + " is far too large.");
                        if (progress != null) progress.update(done, total);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (total >= 0 && done != total) throw new IOException("The download was cut short.");
            if (size >= 0 && done != size) throw new IOException("The download isn't the size the release gives.");
            if (sha256 != null && !sha256.equals(hex(digest.digest()))) {
                throw new IOException("The download doesn't match the release.");
            }
            if (to.exists() && !to.delete()) throw new IOException("Can't replace the old download.");
            if (!part.renameTo(to)) throw new IOException("Can't save the download.");
        } catch (IOException e) {
            part.delete();
            throw friendly(e);
        } finally {
            connection.disconnect();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xFF));
        return out.toString();
    }

    private HttpURLConnection open(String url) throws IOException {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Pragma", "no-cache");
            // Never pooled, so the next check starts afresh as a new process would.
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("User-Agent", "SonimUpdater");
            // Uncompressed, so the length GitHub gives is the file's.
            connection.setRequestProperty("Accept-Encoding", "identity");
            return connection;
        } catch (IOException e) {
            throw friendly(e);
        }
    }

    private static InputStream body(HttpURLConnection connection, String file) throws IOException {
        int status = connection.getResponseCode();
        if (status == 404) throw new IOException("The release has no " + file + ".");
        if (status != 200) throw new IOException("GitHub answered " + status + " for " + file + ".");
        return connection.getInputStream();
    }

    /**
     * The network failures worth naming, in words for a 240px screen.
     * Anything already phrased for a person passes through untouched.
     */
    private static IOException friendly(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return new IOException("The connection timed out.");
        }
        if (e instanceof java.net.UnknownHostException) {
            return new IOException("No network.");
        }
        if (e instanceof java.net.ConnectException) {
            return new IOException("Could not connect.");
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return new IOException("The secure connection failed.");
        }
        return e;
    }
}
