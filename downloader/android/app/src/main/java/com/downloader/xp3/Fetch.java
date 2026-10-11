package com.downloader.xp3;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads one address to a file, following redirects across http and https. */
final class Fetch {

    private Fetch() {}

    interface Listener {
        boolean cancelled();

        /** {@code total} is -1 when the server did not say how big the file is. */
        void progress(long done, long total);
    }

    /** Thrown out of {@link #to} once the listener reports a cancel. */
    static final class Cancelled extends IOException {
        Cancelled() {
            super("Cancelled");
        }
    }

    private static final int MAX_REDIRECTS = 8;
    private static final String USER_AGENT = "Downloader/1.0 (Android; Sonim XP3)";

    static void to(String address, File out, Listener listener) throws IOException {
        URL url = new URL(address);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String protocol = url.getProtocol();
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                throw new IOException("Only http and https addresses work.");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            // Followed by hand: HttpURLConnection will not cross from http to
            // https on its own, and download links very often do.
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            // So Content-Length is the size that lands on disk.
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("The server redirected to nowhere (HTTP " + code + ").");
                    url = new URL(url, location);
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("The server said HTTP " + code + ".");
                }
                save(connection, out, listener);
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects.");
    }

    private static void save(HttpURLConnection connection, File out, Listener listener) throws IOException {
        long total = connection.getContentLengthLong();
        long done = 0;
        byte[] buffer = new byte[64 * 1024];
        InputStream in = connection.getInputStream();
        OutputStream file = new FileOutputStream(out);
        try {
            listener.progress(0, total);
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (listener.cancelled()) throw new Cancelled();
                file.write(buffer, 0, read);
                done += read;
                listener.progress(done, total);
            }
            if (total >= 0 && done != total) throw new IOException("The download stopped part way.");
        } finally {
            file.close();
            in.close();
        }
    }
}
