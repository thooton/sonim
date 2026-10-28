package com.canvas.xp3.net;

import com.xp3.common.Http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches what a teacher linked to outside Canvas: a handout on Google Drive,
 * a PDF on a course website, an article.
 *
 * This is read only and anonymous by construction: a plain GET carrying no
 * cookie, token or Canvas header of any kind, so nothing of the student's
 * session can reach whoever runs the far end. Redirects are followed by hand,
 * like Canvas's own downloads, so that every hop has to be https and there
 * can only be a few of them.
 */
public final class Web {

    /** What came back: its MIME type ("" if unsaid) and the name the server gave it, if any. */
    public static final class Got {
        public final String type;
        public final String name;
        public final URI at;

        Got(String type, String name, URI at) {
            this.type = type;
            this.name = name;
            this.at = at;
        }

        /** A web page rather than a file, to be read as text. */
        public boolean page() {
            return type.toLowerCase(Locale.US).startsWith("text/html")
                || type.toLowerCase(Locale.US).startsWith("application/xhtml");
        }

        /** The page's declared character set, or UTF-8. */
        public String charset() {
            Matcher m = CHARSET.matcher(type);
            return m.find() ? m.group(1) : "UTF-8";
        }
    }

    private static final Pattern CHARSET = Pattern.compile("(?i)charset=\"?([A-Za-z0-9._-]+)");
    private static final Pattern NAME_STAR = Pattern.compile("(?i)filename\\*\\s*=\\s*[A-Za-z0-9_-]*'[^']*'([^;]+)");
    private static final Pattern NAME = Pattern.compile("(?i)filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))");
    // Some sites turn away a client that does not look like a browser.
    private static final String AGENT = "Mozilla/5.0 (Linux; Android 8.1.0) AppleWebKit/537.36 (KHTML, like Gecko) Mobile";

    private final Http http;
    /** The only scheme fetched; tests swap in http for a loopback server. */
    String scheme = "https";

    public Web(Http http) {
        this.http = http;
    }

    public Got fetch(URI uri, OutputStream out, long limit, Http.Sending progress)
        throws IOException, InterruptedException {
        for (int hop = 0; hop < 6; hop++) {
            if (!scheme.equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw new IOException("Refused a link that is not https");
            }
            Http.Request request = Http.Request.to(uri).timeout(300000).identity()
                .header("User-Agent", AGENT).header("Accept", "*/*");
            Http.Stream stream = http.stream(request);
            try {
                int status = stream.status();
                if (status >= 300 && status < 400) {
                    String next = stream.header("Location");
                    if (next.length() == 0) throw new IOException("The link went nowhere");
                    try {
                        uri = uri.resolve(next.trim().replace(" ", "%20"));
                    } catch (IllegalArgumentException e) {
                        throw new IOException("The link went nowhere");
                    }
                    continue;
                }
                if (status == 401 || status == 403) throw new IOException("That link needs a login");
                if (status == 404 || status == 410) throw new IOException("That link is gone");
                Http.requireSuccess(status, "The link");
                stream.copy(out, limit, "This file is too large for the phone.", progress);
                return new Got(stream.contentType(), name(stream.header("Content-Disposition"), uri), uri);
            } finally {
                stream.close();
            }
        }
        throw new IOException("The link redirected too many times");
    }

    /** The server's name for the file, else the last part of its address, else null. */
    static String name(String disposition, URI at) {
        String found = null;
        Matcher star = NAME_STAR.matcher(disposition);
        Matcher plain = NAME.matcher(disposition);
        if (star.find()) found = decode(star.group(1).trim());
        else if (plain.find()) found = plain.group(1) != null ? plain.group(1) : plain.group(2).trim();
        if (found == null || found.trim().length() == 0) {
            String path = at.getRawPath() == null ? "" : at.getRawPath();
            int slash = path.lastIndexOf('/');
            String last = decode(path.substring(slash + 1));
            found = last.contains(".") ? last : null;
        }
        if (found == null) return null;
        // Only the name: a path in it is somebody else's directory.
        found = found.replace('\\', '/');
        found = found.substring(found.lastIndexOf('/') + 1).trim();
        return found.length() == 0 ? null : found;
    }

    /**
     * The name to keep it under: the server's, else the link's own words.
     * The viewer goes by the extension, so one that the type implies is
     * added when the name lacks it, and a page is always .html.
     */
    public static String fileName(Got got, String label) {
        String type = got.type.toLowerCase(Locale.US);
        String ext = got.page() ? "html"
            : type.startsWith("application/pdf") ? "pdf"
            : type.startsWith("image/jpeg") ? "jpg"
            : type.startsWith("image/png") ? "png"
            : type.startsWith("image/gif") ? "gif"
            : type.startsWith("image/webp") ? "webp"
            : type.startsWith("text/plain") ? "txt"
            : type.startsWith("text/csv") ? "csv"
            : type.contains("wordprocessingml") ? "docx"
            : type.contains("presentationml") ? "pptx"
            : type.contains("spreadsheetml") ? "xlsx"
            : null;
        String name = got.name;
        if (name == null || got.page()) {
            name = label == null ? "" : label.trim().replaceAll("[\\/:*?\"<>|]+", " ").trim();
            if (name.length() > 60) name = name.substring(0, 60).trim();
            if (name.length() == 0) name = got.page() ? "page" : "file";
        }
        int dot = name.lastIndexOf('.');
        String has = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        if (ext != null && !has.equals(ext) && !(ext.equals("jpg") && has.equals("jpeg"))) name = name + "." + ext;
        return name;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
