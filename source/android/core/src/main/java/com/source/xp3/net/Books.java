package com.source.xp3.net;

import com.xp3.common.Http;
import com.xp3.common.Json;
import com.xp3.common.Login;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Ebooks, the way the delta CLI's former `delta books` got them
 * (cmd/delta/books.go and books_source.go in thooton/delta's history): the
 * book source's account lives on the command center (GET /books-login,
 * behind the delta token), the phone logs in to the source with it only
 * when it has to, keeps the session it gets, and then searches and
 * downloads from the source directly. Logins are
 * rate-limited by the source (about ten an hour), so the session is reused for
 * as long as it works, and a call that fails on a stored session logs in again
 * once and retries.
 *
 * The source is spoken to as the CLI speaks to it: the same endpoints, form
 * bodies, headers, cookies and domain selection, HTTP/1.1, redirects followed
 * with the session cookies sent on every hop.
 */
public final class Books {

    /** Tried in order when logging in; the source then advertises its own. */
    public static final List<String> DOMAINS = Arrays.asList("z-library.ec", "z-library.sk", "1lib.sk");

    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    /** Downloads go out with python-httpx's default headers, as the reference's do. */
    private static final String HTTPX_USER_AGENT = "python-httpx/0.28.1";
    private static final int MAX_REDIRECTS = 20;
    private static final int MAX_JSON = 8000000;

    /** One search result. Every field is text, empty when the source left it out. */
    public static final class Book {
        public final String id;
        public final String hash;
        public final String title;
        public final String author;
        public final String year;
        public final String language;
        public final String extension;
        public final String size;
        final long bytes;

        Book(Map<String, Object> raw) {
            id = flex(raw.get("id"));
            hash = flex(raw.get("hash"));
            title = flex(raw.get("title"));
            author = flex(raw.get("author"));
            year = nonZero(flex(raw.get("year")));
            language = flex(raw.get("language"));
            extension = flex(raw.get("extension")).toLowerCase(Locale.US);
            long n = 0;
            try {
                n = Long.parseLong(flex(raw.get("filesize")));
            } catch (NumberFormatException ignored) {
                // Left unknown.
            }
            bytes = n;
            String said = flex(raw.get("filesizeString"));
            size = said.length() > 0 ? said : n > 0 ? human(n) : nonZero(flex(raw.get("filesize")));
        }

        /** "Author · 2001 · english · EPUB · 1.2 MB", leaving out what is unknown. */
        public String meta() {
            StringBuilder out = new StringBuilder();
            for (String part : new String[] {author, year, language, extension.toUpperCase(Locale.US), size}) {
                if (part.length() == 0) continue;
                if (out.length() > 0) out.append(" · ");
                out.append(part);
            }
            return out.toString();
        }
    }

    /**
     * The results on `page` that are `book` in another format: the same
     * title by the same author, in the page's order, `book` among them.
     */
    public static List<Book> formats(Page page, Book book) {
        List<Book> out = new ArrayList<Book>();
        String work = work(book);
        for (Book other : page.books) {
            if (other == book || work.length() > 1 && work.equals(work(other))) out.add(other);
        }
        if (!out.contains(book)) out.add(0, book);
        return out;
    }

    private static String work(Book book) {
        return (book.title.trim() + "\n" + book.author.trim()).toLowerCase(Locale.ROOT);
    }

    /** A page of results. */
    public static final class Page {
        public final String query;
        /** The format searched for, or null for any. */
        public final String format;
        public final int page;
        public final int pages;
        public final List<Book> books;

        Page(String query, String format, int page, int pages, List<Book> books) {
            this.query = query;
            this.format = format;
            this.page = page;
            this.pages = pages;
            this.books = books;
        }
    }

    /** The logged-in session with the source, kept between runs. */
    public static final class Session {
        final String email;
        final String domain;
        final String userId;
        final String userKey;

        Session(String email, String domain, String userId, String userKey) {
            this.email = email;
            this.domain = domain;
            this.userId = userId;
            this.userKey = userKey;
        }

        public static Session none() {
            return new Session("", "", "", "");
        }

        public boolean ready() {
            return domain.length() > 0 && userId.length() > 0 && userKey.length() > 0;
        }

        /** The account it belongs to, for showing; empty when there is none. */
        public String email() {
            return email;
        }

        public String toJson() {
            return Json.write(Json.map("email", email, "domain", domain, "remix_userid", userId, "remix_userkey", userKey));
        }

        /** Anything unreadable is no session. */
        public static Session parse(String json) {
            if (json == null || json.trim().length() == 0) return none();
            try {
                Map<String, Object> root = Json.object(Json.parse(json));
                return new Session(flex(root.get("email")), flex(root.get("domain")),
                    flex(root.get("remix_userid")), flex(root.get("remix_userkey")));
            } catch (RuntimeException e) {
                return none();
            }
        }
    }

    /** Told whenever there is a new session, so it can be kept for next time. */
    public interface Keeper {
        void keep(Session session);
    }

    /** How much of a download has arrived; total is -1 when unknown. */
    public interface Progress {
        void saved(long done, long total) throws InterruptedException;
    }

    /** A downloaded book: where it is, and whether it was already there. */
    public static final class Saved {
        public final File file;
        public final boolean kept;
        public final long bytes;

        Saved(File file, boolean kept, long bytes) {
            this.file = file;
            this.kept = kept;
            this.bytes = bytes;
        }
    }

    /** Failing to reach the source at all, rather than a request failing: not worth a new login. */
    static final class Unreachable extends IOException {
        Unreachable(String message) {
            super(message);
        }
    }

    private final Http http = new Http();
    private final Login center;
    private final Keeper keeper;
    private final String scheme;
    private final List<String> domains;
    private Session session;
    /** True once this run has logged in itself, so a failure isn't an expired session. */
    private boolean fresh;
    private volatile boolean cancelled;
    private volatile HttpURLConnection current;

    public Books(Login center, Session session, Keeper keeper) {
        this(center, session, keeper, "https", DOMAINS);
    }

    /** With another scheme and domains: for tests against a local server. */
    public Books(Login center, Session session, Keeper keeper, String scheme, List<String> domains) {
        if (!center.ready()) throw new IllegalArgumentException("There is no command center login");
        this.center = center;
        this.session = session == null ? Session.none() : session;
        this.keeper = keeper;
        this.scheme = scheme;
        this.domains = domains;
    }

    /** Stops whatever is under way, from any thread. */
    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = current;
        if (connection != null) connection.disconnect();
    }

    public boolean cancelled() {
        return cancelled;
    }

    // ---- Searching and downloading.

    /** Searches for `query`; `format` ("epub") limits the results to one format, null allows any. */
    public Page search(final String query, final int page, final String format) throws IOException, InterruptedException {
        final List<String[]> form = new ArrayList<String[]>();
        form.add(new String[] {"message", query.trim()});
        form.add(new String[] {"limit", "20"});
        form.add(new String[] {"page", String.valueOf(page)});
        if (format != null) form.add(new String[] {"extensions[]", format.toUpperCase(Locale.US)});
        Map<String, Object> result = attempt(() -> {
            Map<String, Object> r = api("POST", "/eapi/book/search", form);
            if (!ok(r.get("success"))) {
                throw new IOException(first(flex(r.get("error")), "The search failed."));
            }
            return r;
        });
        List<Book> books = new ArrayList<Book>();
        for (Object each : Json.children(result, "books")) {
            if (each instanceof Map) books.add(new Book(Json.object(each)));
        }
        int pages = number(flex(result.get("totalPages")));
        if (pages == 0 && result.get("pagination") instanceof Map) {
            pages = number(flex(Json.object(result.get("pagination")).get("total_pages")));
        }
        return new Page(query.trim(), format, page, Math.max(pages, 1), books);
    }

    /**
     * Downloads `book` into `folder` as "Author - Title.ext". The same book
     * already there (same name, same size) is kept rather than saved twice; a
     * different one with that name gets " (2)".
     */
    public Saved download(final Book book, final File folder, final Progress progress) throws IOException, InterruptedException {
        return download(book, folder, Store.PLAIN, progress);
    }

    /** As download(), writing through `store`. */
    public Saved download(final Book book, final File folder, final Store store, final Progress progress) throws IOException, InterruptedException {
        return attempt(() -> fetch(book, folder, store, progress));
    }

    private Saved fetch(Book book, File folder, Store store, Progress progress) throws IOException, InterruptedException {
        Map<String, Object> r = api("GET", "/eapi/book/" + segment(book.id) + "/" + segment(book.hash) + "/file", null);
        String link = null;
        if (r.get("file") instanceof Map) link = flex(Json.object(r.get("file")).get("downloadLink"));
        link = first(link, flex(r.get("downloadLink")), flex(r.get("url")), flex(r.get("link")));
        if (link.length() == 0) {
            throw new IOException("Couldn't get the download link: " + first(flex(r.get("error")), "the source gave none") + ".");
        }
        if (link.startsWith("/")) link = base() + link;

        store.mkdirs(folder);
        Reply reply = exchange("GET", link, null, true, 30000, 60000, true);
        File part = new File(folder, ".source-" + Long.toHexString(System.nanoTime()) + ".part");
        long n = 0;
        try {
            if (reply.status >= 400) throw new IOException("The download failed (HTTP " + reply.status + ").");
            if (reply.type.toLowerCase(Locale.US).contains("text/html")) {
                throw new IOException("Got a web page instead of the book: the account's daily download limit may be used up.");
            }
            long total = reply.encoded ? -1 : reply.length;
            if (total <= 0 && book.bytes > 0) total = book.bytes;
            OutputStream out = store.create(part);
            try {
                byte[] buffer = new byte[64 * 1024];
                long last = 0;
                progress.saved(0, total);
                while (true) {
                    int count;
                    try {
                        count = reply.body.read(buffer);
                    } catch (IOException e) {
                        // Read errors can carry the server's address.
                        if (cancelled) throw new InterruptedException();
                        if (e instanceof java.net.SocketTimeoutException) throw new IOException("The download stalled.");
                        throw new IOException("The download was interrupted.");
                    }
                    if (count < 0) break;
                    if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buffer, 0, count);
                    n += count;
                    long now = System.currentTimeMillis();
                    if (now - last > 500) {
                        last = now;
                        progress.saved(n, total);
                    }
                }
            } finally {
                out.close();
            }
            if (n == 0) throw new IOException("The download was empty.");
        } catch (IOException | InterruptedException | RuntimeException e) {
            store.delete(part);
            throw e;
        } finally {
            reply.close();
        }

        String ext = extension(book.extension);
        if (ext.length() == 0) ext = extension(suffix(responseName(reply.disposition, reply.url)));
        for (int i = 1; ; i++) {
            File dest = new File(folder, filename(book, ext, i));
            if (dest.exists()) {
                if (dest.isFile() && dest.length() == n) {
                    store.delete(part);
                    return new Saved(dest, true, n); // The same book, already downloaded.
                }
                continue; // Another book has this name.
            }
            try {
                dest = store.rename(part, dest);
            } catch (IOException | RuntimeException e) {
                store.delete(part);
                throw e;
            }
            return new Saved(dest, false, n);
        }
    }

    // ---- The session.

    private interface Call<T> {
        T run() throws IOException, InterruptedException;
    }

    /**
     * Runs `call` with a session, logging in first when there is none, and
     * logging in again and retrying once when it fails on a stored session
     * that may have expired.
     */
    private <T> T attempt(Call<T> call) throws IOException, InterruptedException {
        if (!session.ready()) login();
        try {
            return call.run();
        } catch (IOException e) {
            if (cancelled) throw new InterruptedException();
            if (fresh || e instanceof Unreachable) throw e;
            try {
                login();
            } catch (IOException again) {
                throw new IOException(e.getMessage() + " (Logging in again also failed: " + again.getMessage() + ")");
            }
            return call.run();
        }
    }

    /** Fetches the account from the command center and logs in to a healthy domain. */
    private void login() throws IOException, InterruptedException {
        String[] account = account();
        String domain = resolve();
        session = new Session(account[0], domain, "", "");
        List<String[]> form = new ArrayList<String[]>();
        form.add(new String[] {"email", account[0]});
        form.add(new String[] {"password", account[1]});
        Map<String, Object> r = api("POST", "/eapi/user/login", form);
        Map<String, Object> user = r.get("user") instanceof Map ? Json.object(r.get("user")) : null;
        if (!ok(r.get("success")) || user == null) {
            throw new IOException("Logging in to the book source failed: " + first(flex(r.get("error")), "unknown error"));
        }
        session = new Session(account[0], domain, flex(user.get("id")), flex(user.get("remix_userkey")));
        try {
            Map<String, Object> advertised = api("GET", "/eapi/info/domains", null);
            String target = advertisedDomain(Json.children(advertised, "domains"), domain);
            if (target.length() > 0 && !target.equals(domain)) {
                session = new Session(session.email, target, session.userId, session.userKey);
            }
        } catch (IOException ignored) {
            // The domain that took the login works.
        }
        fresh = true;
        if (keeper != null) keeper.keep(session);
    }

    /** {email, password} from the command center. */
    private String[] account() throws IOException, InterruptedException {
        Http.Response response = http.send(Http.Request.to(URI.create(center.server() + "/books-login"))
            .header("Authorization", "Bearer " + center.token())
            .header("Accept", "application/json")
            .timeout(30000));
        if (response.status() == 404) {
            throw new IOException("No book account is set on the command center: put one there with PUT /books-login.");
        }
        Music.require(response.status(), response.body());
        Map<String, Object> root;
        try {
            root = Json.object(Json.parse(response.body()));
        } catch (RuntimeException e) {
            throw new IOException("The command center sent a book login that can't be read.");
        }
        String email = flex(root.get("email"));
        String password = flex(root.get("password"));
        if (email.length() == 0 || password.length() == 0) throw new IOException("The book account on the command center is incomplete.");
        return new String[] {email, password};
    }

    /** The first domain that answers like the source, else the first of all. */
    private String resolve() throws InterruptedException {
        for (String domain : domains) {
            if (probe(domain)) return domain;
        }
        return domains.get(0);
    }

    /** A domain is healthy when its unauthenticated domain list comes back as JSON. */
    private boolean probe(String domain) throws InterruptedException {
        try {
            Reply reply = exchange("GET", scheme + "://" + domain + "/eapi/info/domains", null, false, 8000, 8000, false);
            try {
                if (reply.status != 200) return false;
                String body = text(reply);
                if (body.toLowerCase(Locale.US).contains("diamwall")) return false;
                return Json.object(Json.parse(body)).containsKey("domains");
            } finally {
                reply.close();
            }
        } catch (IOException | RuntimeException e) {
            if (cancelled) throw new InterruptedException();
            return false;
        }
    }

    /** The first usable advertised domain, keeping `current` if it comes before any healthy alternative. */
    private String advertisedDomain(List<Object> advertised, String current) throws InterruptedException {
        for (Object raw : advertised) {
            String domain = raw instanceof Map ? flex(Json.object(raw).get("domain")) : flex(raw);
            if (domain.length() == 0) continue;
            if (domain.equals(current) || probe(domain)) return domain;
        }
        return "";
    }

    // ---- The wire.

    private String base() {
        return scheme + "://" + session.domain;
    }

    /** A JSON call to the source, with anti-bot walls told apart from other failures. */
    private Map<String, Object> api(String method, String path, List<String[]> form) throws IOException, InterruptedException {
        Reply reply = exchange(method, base() + path, form, false, 10000, 30000, true);
        String body;
        try {
            body = text(reply);
        } finally {
            reply.close();
        }
        Object parsed = null;
        if (reply.status == 200) {
            try {
                parsed = Json.parse(body);
            } catch (RuntimeException ignored) {
                // Classified below.
            }
        }
        if (parsed instanceof Map) return Json.object(parsed);
        int s = reply.status;
        if (body.toLowerCase(Locale.US).contains("diamwall") || s == 307 || s == 403 || s == 513 || s == 517) {
            throw new Unreachable("The book source is blocking requests right now.");
        }
        if (s >= 400) throw new IOException("The book source returned HTTP " + s + ".");
        throw new IOException("The book source returned something unexpected (HTTP " + s + ").");
    }

    private static final class Reply {
        HttpURLConnection connection;
        int status;
        String type = "";
        String disposition = "";
        URL url;
        long length = -1;
        boolean encoded;
        InputStream body;

        void close() {
            connection.disconnect();
            try {
                body.close();
            } catch (IOException | RuntimeException ignored) {
                // Already cut off.
            }
        }
    }

    /**
     * One request, following redirects (when `follow`) with the session
     * cookies, and any cookies set along the way, sent on every hop.
     * `download` sends httpx's headers instead of a browser's.
     */
    private Reply exchange(String method, String address, List<String[]> form, boolean download,
        int connectMs, int readMs, boolean follow) throws IOException, InterruptedException {
        Map<String, Map<String, String>> jar = new HashMap<String, Map<String, String>>();
        URL url = new URL(address);
        String body = form == null ? null : encode(form);
        for (int hop = 0; ; hop++) {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            current = c;
            try {
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setRequestMethod(method);
                c.setRequestProperty("Accept", download ? "*/*" : "application/json, text/plain, */*");
                c.setRequestProperty("Accept-Encoding", "gzip, deflate");
                c.setRequestProperty("Connection", "keep-alive");
                c.setRequestProperty("User-Agent", download ? HTTPX_USER_AGENT : USER_AGENT);
                if (!download) c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setRequestProperty("Cookie", cookies(jar.get(url.getHost())));
                if (body != null) {
                    byte[] payload = body.getBytes("UTF-8");
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(payload.length);
                    OutputStream out = c.getOutputStream();
                    try {
                        out.write(payload);
                    } finally {
                        out.close();
                    }
                }
                int status = c.getResponseCode();
                for (int i = 1; ; i++) {
                    String key = c.getHeaderFieldKey(i);
                    if (key == null && c.getHeaderField(i) == null) break;
                    if (key != null && key.equalsIgnoreCase("Set-Cookie")) remember(jar, url.getHost(), c.getHeaderField(i));
                }
                String location = c.getHeaderField("Location");
                if (follow && location != null && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
                    if (hop >= MAX_REDIRECTS) throw new IOException("The book source redirected too many times.");
                    c.disconnect();
                    url = new URL(url, location);
                    if (status != 307 && status != 308) {
                        method = "GET";
                        body = null;
                    }
                    continue;
                }
                Reply reply = new Reply();
                reply.connection = c;
                reply.status = status;
                reply.url = url;
                reply.type = orEmpty(c.getHeaderField("Content-Type"));
                reply.disposition = orEmpty(c.getHeaderField("Content-Disposition"));
                reply.length = c.getContentLengthLong();
                InputStream raw = status >= 400 ? c.getErrorStream() : c.getInputStream();
                if (raw == null) raw = new java.io.ByteArrayInputStream(new byte[0]);
                String encoding = orEmpty(c.getContentEncoding()).trim().toLowerCase(Locale.US);
                reply.encoded = encoding.length() > 0 && !encoding.equals("identity");
                reply.body = decode(raw, encoding);
                return reply;
            } catch (IOException e) {
                c.disconnect();
                if (cancelled) throw new InterruptedException();
                if (e instanceof java.net.SocketTimeoutException) throw new Unreachable("The book source timed out.");
                if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException
                    || e instanceof javax.net.ssl.SSLException || e instanceof java.net.NoRouteToHostException) {
                    throw new Unreachable("Couldn't reach the book source.");
                }
                throw e;
            }
        }
    }

    /** The session's cookies, then whatever the hops so far have set for this host. */
    private String cookies(Map<String, String> set) {
        Map<String, String> all = new LinkedHashMap<String, String>();
        all.put("siteLanguageV2", "en");
        if (session.userId.length() > 0) all.put("remix_userid", session.userId);
        if (session.userKey.length() > 0) all.put("remix_userkey", session.userKey);
        if (set != null) {
            for (Map.Entry<String, String> e : set.entrySet()) {
                if (!all.containsKey(e.getKey())) all.put(e.getKey(), e.getValue());
            }
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

    private static void remember(Map<String, Map<String, String>> jar, String host, String header) {
        if (header == null) return;
        String pair = header.split(";", 2)[0];
        int eq = pair.indexOf('=');
        if (eq <= 0) return;
        Map<String, String> forHost = jar.get(host);
        if (forHost == null) {
            forHost = new LinkedHashMap<String, String>();
            jar.put(host, forHost);
        }
        forHost.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
    }

    /** Undoes Content-Encoding, which is ours to do since we asked for it. */
    private static InputStream decode(InputStream raw, String encoding) throws IOException {
        if (encoding.length() == 0 || encoding.equals("identity")) return raw;
        if (encoding.equals("gzip") || encoding.equals("x-gzip")) return new GZIPInputStream(raw);
        if (encoding.equals("deflate")) {
            // zlib-wrapped per the spec, but some servers send raw deflate.
            BufferedInputStream in = new BufferedInputStream(raw);
            in.mark(2);
            int a = in.read();
            int b = in.read();
            in.reset();
            boolean zlib = a >= 0 && b >= 0 && (a & 0x0f) == 8 && ((a << 8) | b) % 31 == 0;
            return new InflaterInputStream(in, new Inflater(!zlib));
        }
        throw new IOException("The book source sent an unsupported encoding.");
    }

    private static String text(Reply reply) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = reply.body.read(buffer)) >= 0; ) {
            if (out.size() + count > MAX_JSON) throw new IOException("The book source sent too much.");
            out.write(buffer, 0, count);
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    /** An insertion-ordered urlencoded body, as httpx encodes it. */
    private static String encode(List<String[]> form) {
        StringBuilder out = new StringBuilder();
        try {
            for (String[] kv : form) {
                if (out.length() > 0) out.append('&');
                out.append(URLEncoder.encode(kv[0], "UTF-8")).append('=').append(URLEncoder.encode(kv[1], "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    private static String segment(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Names, as `delta books` gave them.

    private static final List<String> EXTENSIONS = Arrays.asList(
        "txt", "pdf", "fb2", "epub", "lit", "mobi", "rtf", "djv", "djvu", "azw", "azw3",
        "cbr", "cbz", "doc", "docx", "odt");

    /** A known book extension, lower case and without its dot; empty otherwise. */
    static String extension(String ext) {
        String n = ext == null ? "" : ext.trim().toLowerCase(Locale.US);
        while (n.startsWith(".")) n = n.substring(1);
        return EXTENSIONS.contains(n) ? n : "";
    }

    private static String suffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** The Content-Disposition file name, else the last segment of the URL's path. */
    static String responseName(String disposition, URL url) {
        for (String param : disposition.split(";")) {
            String p = param.trim();
            if (p.toLowerCase(Locale.US).startsWith("filename=")) {
                String name = p.substring(9).trim();
                if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) name = name.substring(1, name.length() - 1);
                if (name.length() > 0) return name;
            }
        }
        if (url == null) return "";
        String path = url.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Copy n (from 1) of a book's file name: "Author - Title.ext", or
     * "Title.ext" with no author; later copies, for different books that would
     * share a name, get " (n)".
     */
    public static String filename(Book book, String ext, int n) {
        String name = clip(nameText(book.title), 150);
        if (name.length() == 0) name = "Untitled";
        String author = clip(nameText(book.author), 80);
        if (author.length() > 0) name = author + " - " + name;
        if (n > 1) name += " (" + n + ")";
        String e = extension(ext);
        if (e.length() > 0) name += "." + e;
        return name;
    }

    /**
     * Fit for a file name: characters some file systems refuse become spaces
     * (a colon before a subtitle becomes a dash), runs of spaces collapse, and
     * leading and trailing dots go.
     */
    static String nameText(String s) {
        s = s.replace(": ", " - ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0 ? ' ' : c);
        }
        String collapsed = out.toString().trim().replaceAll("\\s+", " ");
        return trim(collapsed, ". ");
    }

    /** At most n bytes of UTF-8, without splitting a character. */
    static String clip(String s, int n) {
        try {
            if (s.getBytes("UTF-8").length <= n) return s;
            int bytes = 0;
            int end = 0;
            while (end < s.length()) {
                int cp = s.codePointAt(end);
                int size = new String(Character.toChars(cp)).getBytes("UTF-8").length;
                if (bytes + size > n) break;
                bytes += size;
                end += Character.charCount(cp);
            }
            String clipped = s.substring(0, end);
            while (clipped.endsWith(".") || clipped.endsWith(" ")) clipped = clipped.substring(0, clipped.length() - 1);
            return clipped;
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    // ---- Loose JSON, as the source writes it.

    /** A JSON string, number or boolean as text; anything else is empty. */
    static String flex(Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof BigDecimal) return ((BigDecimal) value).toPlainString();
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return "";
    }

    private static boolean ok(Object value) {
        String s = flex(value);
        return s.equals("1") || s.equals("true");
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nonZero(String s) {
        return s.trim().equals("0") ? "" : s.trim();
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && v.length() > 0) return v;
        }
        return "";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
