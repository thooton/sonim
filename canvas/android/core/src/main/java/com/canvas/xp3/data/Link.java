package com.canvas.xp3.data;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a link in a teacher's text goes, and what the phone can do with it.
 *
 * There is no browser worth opening on this phone, so a link is worth
 * something only if what it points at can be fetched and read here: a Canvas
 * file through the app's own download, an assignment the app already has,
 * or a file or page on the web fetched with no login at all. Teachers mostly
 * share handouts through Google Drive and Dropbox, whose links lead to a
 * viewer page rather than the file, so those are pointed at the file itself.
 */
public final class Link {

    /** Nothing the phone can fetch: mail, phone numbers, anchors, malformed addresses. */
    public static final int NONE = 0;
    /** A Canvas file, read through Canvas's file route with the session. */
    public static final int FILE = 1;
    /** An assignment, opened in the app. */
    public static final int ASSIGNMENT = 2;
    /** Any other Canvas page: it needs the session and a browser. */
    public static final int CANVAS = 3;
    /** Anywhere else, fetched without a cookie. */
    public static final int WEB = 4;

    private static final Pattern CANVAS_FILE =
        Pattern.compile("(?:/(?:courses|users|groups)/\\d{1,18})?/files/(\\d{1,18})(?:/.*)?");
    private static final Pattern CANVAS_FILES = Pattern.compile("/courses/\\d{1,18}/files/?");
    private static final Pattern PREVIEW = Pattern.compile("(?:^|&)preview=(\\d{1,18})(?:&|$)");
    private static final Pattern CANVAS_ASSIGNMENT =
        Pattern.compile("/courses/(\\d{1,18})/assignments/(\\d{1,18})(?:[/?#].*)?");
    private static final Pattern DRIVE_FILE =
        Pattern.compile("(?:/u/\\d+)?/file(?:/u/\\d+)?/d/([A-Za-z0-9_-]{10,})(?:/.*)?");
    private static final Pattern DRIVE_ID = Pattern.compile("(?:^|&)id=([A-Za-z0-9_-]{10,})(?:&|$)");
    private static final Pattern DOCS =
        Pattern.compile("/(document|presentation|spreadsheets|drawings)(?:/u/\\d+)?/d/([A-Za-z0-9_-]{10,})(?:/.*)?");

    public final int kind;
    /** The address the link names, made absolute; null for NONE. */
    public final URI address;
    /** Where the bytes are fetched from: the address, or the file behind a viewer page. */
    public final URI fetch;
    /** The file's or assignment's id; null otherwise. */
    public final String id;
    /** The assignment's class; null otherwise. */
    public final String course;

    private Link(int kind, URI address, URI fetch, String id, String course) {
        this.kind = kind;
        this.address = address;
        this.fetch = fetch;
        this.id = id;
        this.course = course;
    }

    /**
     * Reads a link's target. `base` is the address of the text it was found
     * in, against which a relative link is read; `canvas` is the Canvas
     * origin, or null when none is set up.
     */
    public static Link of(String target, URI base, URI canvas) {
        Link none = new Link(NONE, null, null, null, null);
        if (target == null) return none;
        String written = target.trim().replace(" ", "%20");
        if (written.length() == 0 || written.startsWith("#")) return none;
        URI uri;
        try {
            uri = new URI(written);
            if (base != null) uri = base.resolve(uri);
        } catch (Exception e) {
            // URISyntaxException, or IllegalArgumentException from resolve: either way not a link.
            return none;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (!scheme.equals("https") && !scheme.equals("http")) return none;
        if (uri.getHost() == null || uri.getRawUserInfo() != null) return none;
        String host = uri.getHost().toLowerCase(Locale.US);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();

        if (canvas != null && host.equals(canvas.getHost().toLowerCase(Locale.US))) {
            Matcher file = CANVAS_FILE.matcher(path);
            if (file.matches()) return new Link(FILE, uri, null, file.group(1), null);
            Matcher preview = PREVIEW.matcher(query);
            if (CANVAS_FILES.matcher(path).matches() && preview.find()) {
                return new Link(FILE, uri, null, preview.group(1), null);
            }
            Matcher work = CANVAS_ASSIGNMENT.matcher(path);
            if (work.matches()) return new Link(ASSIGNMENT, uri, null, work.group(2), work.group(1));
            return new Link(CANVAS, uri, null, null, null);
        }
        return new Link(WEB, uri, behind(host, path, query, https(uri)), null, null);
    }

    /** The phone refuses cleartext, so an http link is tried over https; most sites answer both. */
    private static URI https(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) return uri;
        try {
            return new URI("https", null, uri.getHost(), uri.getPort() == 80 ? -1 : uri.getPort(), uri.getPath(),
                uri.getQuery(), null);
        } catch (Exception e) {
            return uri;
        }
    }

    /** The file behind a sharing site's viewer page, or the address unchanged. */
    private static URI behind(String host, String path, String query, URI plain) {
        if (host.equals("drive.google.com")) {
            Matcher file = DRIVE_FILE.matcher(path);
            Matcher id = DRIVE_ID.matcher(query);
            String found = file.matches() ? file.group(1)
                : (path.equals("/open") || path.equals("/uc")) && id.find() ? id.group(1) : null;
            // confirm=t skips the "can't scan this file for viruses" page Drive
            // puts in front of anything large.
            if (found != null) {
                return URI.create("https://drive.usercontent.google.com/download?id=" + found
                    + "&export=download&confirm=t");
            }
        }
        if (host.equals("docs.google.com")) {
            Matcher doc = DOCS.matcher(path);
            if (doc.matches()) {
                String type = doc.group(1);
                String export = type.equals("document") || type.equals("spreadsheets") ? "/export?format=pdf"
                    : "/export/pdf";
                return URI.create("https://docs.google.com/" + type + "/d/" + doc.group(2) + export);
            }
        }
        if ((host.equals("www.dropbox.com") || host.equals("dropbox.com"))
            && (path.startsWith("/s/") || path.startsWith("/scl/"))) {
            String kept = query.replaceAll("(?:^|&)(?:dl|raw)=[^&]*", "").replaceFirst("^&", "");
            return URI.create("https://www.dropbox.com" + path + "?" + (kept.length() > 0 ? kept + "&" : "") + "dl=1");
        }
        return plain;
    }

    /** "drive.google.com", "nytimes.com": who a link belongs to, short enough for a menu row. */
    public String site() {
        if (address == null) return "";
        String host = address.getHost().toLowerCase(Locale.US);
        return host.startsWith("www.") ? host.substring(4) : host;
    }
}
