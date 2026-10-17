package com.updater.xp3.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The rolling `latest` release of github.com/thooton/sonim, where CI puts every
 * app's APK beside `versions.txt` and `packages.txt`.
 *
 * Release files redirect from github.com to GitHub's file host, which
 * HttpURLConnection follows by itself (both are https). Nothing is cached:
 * the point of asking is to hear about a release made a minute ago.
 */
public final class Releases {

    public static final String LATEST = "https://github.com/thooton/sonim/releases/download/latest/";
    /** The repository's newest commits, where the release notes come from (see Notes). */
    public static final String COMMITS = "https://api.github.com/repos/thooton/sonim/commits?per_page=100";

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;
    /** versions.txt and packages.txt are a line per app. */
    private static final int MAX_TEXT = 256 * 1024;
    /** A hundred commits as GitHub lists them, with room to spare. */
    private static final int MAX_COMMITS = 2 * 1024 * 1024;
    private static final long MAX_APK = 200L * 1024 * 1024;

    /** Told how much of a download has arrived; `total` is -1 when unknown. */
    public interface Progress {
        void update(long done, long total);
    }

    private final String base;
    private final String commits;

    public Releases() {
        this(LATEST, COMMITS);
    }

    /** For the tests, which serve a release and commits of their own. */
    public Releases(String base, String commits) {
        this.base = base.endsWith("/") ? base : base + "/";
        this.commits = commits;
    }

    /** The apps in the release, sorted by name. */
    public List<Catalog.Entry> catalog() throws IOException {
        return Catalog.join(Catalog.parse(text("versions.txt")), Catalog.parse(text("packages.txt")));
    }

    /** What's new in each of `apps`, newest first (see Notes). */
    public Map<String, List<Notes.Note>> notes(Collection<String> apps) throws IOException {
        String json = fetch(commits, "the commits", MAX_COMMITS);
        try {
            return Notes.parse(json, apps);
        } catch (IllegalArgumentException e) {
            throw new IOException("GitHub's commits were unreadable.");
        }
    }

    /** A small text file from the release. */
    public String text(String file) throws IOException {
        return fetch(base + file, file, MAX_TEXT);
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
     * Downloads a release file to `to`, through `to.part` so a cut-off
     * download never looks finished. `to` is replaced if it exists.
     */
    public void download(String file, File to, Progress progress) throws IOException {
        File part = new File(to.getPath() + ".part");
        HttpURLConnection connection = open(base + file);
        try {
            InputStream in = body(connection, file);
            long total = connection.getContentLengthLong();
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
            if (to.exists() && !to.delete()) throw new IOException("Can't replace the old download.");
            if (!part.renameTo(to)) throw new IOException("Can't save the download.");
        } catch (IOException e) {
            part.delete();
            throw friendly(e);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
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
