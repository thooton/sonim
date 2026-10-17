package com.mail.xp3.net;

import com.xp3.common.Http;
import com.xp3.common.Json;
import com.xp3.common.Login;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The user's Gmail, as `delta gmail` reaches it (cmd/delta/gmail*.go in
 * thooton/delta).
 *
 * The login (the user's own OAuth client and the refresh token they granted
 * it) lives on the command center, at GET /gmail-login with the delta token.
 * It is fetched when first needed and kept in memory only, traded at
 * Google's token endpoint for an hour-long access token, and Gmail is called
 * directly with that: the command center never sees any mail.
 */
public final class Gmail {

    public static final String API = "https://gmail.googleapis.com";
    public static final String TOKEN = "https://oauth2.googleapis.com/token";
    /** Conversations per page of a list. */
    public static final int PAGE = 20;
    /** Http refuses a response past 8 MB, and base64 makes a file a third bigger. */
    public static final long MAX_FILE = 6000000;

    private static final String METADATA = "format=metadata&metadataHeaders=From&metadataHeaders=To"
        + "&metadataHeaders=Cc&metadataHeaders=Subject&metadataHeaders=Date";

    private final Http http = new Http();
    private final String center;
    private final String token;
    private final String api;
    private final String tokenUrl;
    /** A few lookups at once: one per conversation in a list, as the CLI does. */
    private final ExecutorService pool = Executors.newFixedThreadPool(5, runnable -> {
        Thread thread = new Thread(runnable, "gmail");
        thread.setDaemon(true);
        return thread;
    });

    private Map<String, Object> login;
    private String access;
    private long accessUntil;
    private volatile Me me;

    public Gmail(Login login) {
        this(login.server(), login.token(), API, TOKEN);
    }

    /** Any command center, Gmail and token endpoint, which is how the tests use it. */
    public Gmail(String center, String token, String api, String tokenUrl) {
        if (center == null || token == null) throw new IllegalArgumentException("There is no command center login");
        this.center = center;
        this.token = token;
        this.api = api;
        this.tokenUrl = tokenUrl;
    }

    public void close() {
        pool.shutdownNow();
    }

    // ---- Signing in ----

    /** An access token, fetched again a minute before it runs out. */
    synchronized String access() throws IOException, InterruptedException {
        if (access != null && System.currentTimeMillis() < accessUntil) return access;
        if (login == null) login = fetchLogin();
        String form = "grant_type=refresh_token&refresh_token=" + encode(Json.string(login, "refresh_token"))
            + "&client_id=" + encode(Json.string(login, "client_id"))
            + "&client_secret=" + encode(Json.string(login, "client_secret"));
        Http.Response response = http.send(Http.Request.to(URI.create(tokenUrl))
            .post(form).header("Content-Type", "application/x-www-form-urlencoded").timeout(30000));
        Map<String, Object> reply;
        try {
            reply = Json.object(Json.parse(response.body()));
        } catch (RuntimeException e) {
            throw new IOException("Google sent something unreadable (HTTP " + response.status() + ").");
        }
        String error = Json.text(reply, "error");
        if (error != null) {
            // The login may have been replaced on the command center since.
            login = null;
            if (error.equals("invalid_grant")) {
                throw new IOException("The Gmail login has expired or was revoked. Run `delta gmail login` again on a computer.");
            }
            if (error.equals("invalid_client") || error.equals("unauthorized_client")) {
                throw new IOException("Google no longer accepts the Gmail login's OAuth client. Run `delta gmail login` again.");
            }
            String detail = Json.text(reply, "error_description");
            throw new IOException("Google refused the Gmail login: " + (detail != null ? detail : error));
        }
        String fresh = Json.text(reply, "access_token");
        if (fresh == null || fresh.length() == 0) throw new IOException("Google sent no access token.");
        Double seconds = Json.number(reply, "expires_in");
        access = fresh;
        accessUntil = System.currentTimeMillis() + (seconds == null ? 3000 : seconds.longValue() - 60) * 1000;
        return fresh;
    }

    private Map<String, Object> fetchLogin() throws IOException, InterruptedException {
        Http.Response response = http.send(Http.Request.to(URI.create(center + "/gmail-login"))
            .header("Authorization", "Bearer " + token).timeout(30000));
        if (response.status() == 404) {
            throw new IOException("Gmail isn't set up on the command center. Run `delta gmail login` on a computer.");
        }
        Http.requireSuccess(response.status(), "Command center");
        try {
            Map<String, Object> found = Json.object(Json.parse(response.body()));
            Json.string(found, "client_id");
            Json.string(found, "client_secret");
            Json.string(found, "refresh_token");
            Json.string(found, "email");
            return found;
        } catch (RuntimeException e) {
            throw new IOException("The command center sent an unreadable Gmail login.");
        }
    }

    private synchronized void forget(String refused) {
        if (refused.equals(access)) access = null;
    }

    /** The user and the addresses they send as, looked up once. */
    public Me me() throws IOException, InterruptedException {
        Me known = me;
        if (known != null) return known;
        access();
        String email;
        synchronized (this) {
            email = Json.string(login, "email");
        }
        List<Address> sendAs = new ArrayList<Address>();
        try {
            for (Object item : Json.children(get("/settings/sendAs", ""), "sendAs")) {
                Map<String, Object> entry = Json.object(item);
                String status = Json.text(entry, "verificationStatus");
                String address = Json.text(entry, "sendAsEmail");
                if (address == null || status != null && !status.equals("accepted")) continue;
                Address one = new Address(Json.text(entry, "displayName"), address);
                if (Json.flag(entry, "isDefault")) sendAs.add(0, one);
                else sendAs.add(one);
            }
        } catch (IOException e) {
            // Sending as the account's own address still works.
        }
        known = new Me(email, sendAs);
        me = known;
        return known;
    }

    // ---- Reading ----

    /** One page of a folder's conversations, and what is needed to ask for the next. */
    public static final class Page {
        public final List<Conversation> conversations;
        /** Null when there are no more. */
        public final String next;

        Page(List<Conversation> conversations, String next) {
            this.conversations = Collections.unmodifiableList(conversations);
            this.next = next;
        }
    }

    public Page list(Folder folder, String after) throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder("maxResults=" + PAGE);
        if (folder.query.length() > 0) query.append("&q=").append(encode(folder.query));
        if (folder.label != null) query.append("&labelIds=").append(encode(folder.label));
        if (folder.includesSpamAndTrash()) query.append("&includeSpamTrash=true");
        if (after != null) query.append("&pageToken=").append(encode(after));
        Map<String, Object> found = get("/threads", query.toString());
        List<Future<Conversation>> pending = new ArrayList<Future<Conversation>>();
        for (Object item : Json.children(found, "threads")) {
            final String id = Json.string(Json.object(item), "id");
            pending.add(pool.submit(() -> {
                try {
                    return new Conversation(get("/threads/" + path(id), METADATA));
                } catch (Gone e) {
                    return null; // Deleted meanwhile.
                }
            }));
        }
        List<Conversation> conversations = new ArrayList<Conversation>();
        try {
            for (Future<Conversation> future : pending) {
                Conversation conversation = future.get();
                if (conversation != null && !conversation.messages.isEmpty()) conversations.add(conversation);
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException(cause == null ? "Gmail failed." : String.valueOf(cause.getMessage()));
        } finally {
            for (Future<Conversation> future : pending) future.cancel(true);
        }
        String next = Json.text(found, "nextPageToken");
        return new Page(conversations, next == null || next.length() == 0 ? null : next);
    }

    /** A whole conversation, every message's text and files. */
    public Conversation conversation(String id) throws IOException, InterruptedException {
        return new Conversation(get("/threads/" + path(id), "format=full"));
    }

    /** Unread conversations in the inbox. */
    public int unread() throws IOException, InterruptedException {
        Double count = Json.number(get("/labels/INBOX", ""), "threadsUnread");
        return count == null ? 0 : count.intValue();
    }

    /**
     * The folder pane's counts, by label id (see {@link Folder#countedLabel}),
     * five at a time. A label deleted meanwhile is left out.
     */
    public Map<String, Integer> counts(List<Folder> folders) throws IOException, InterruptedException {
        final Map<String, Future<Integer>> pending = new LinkedHashMap<String, Future<Integer>>();
        for (final Folder folder : folders) {
            final String label = folder.countedLabel();
            if (label == null || pending.containsKey(label)) continue;
            pending.put(label, pool.submit(() -> {
                try {
                    Map<String, Object> found = get("/labels/" + path(label), "");
                    Double count = Json.number(found, folder.countsAll() ? "threadsTotal" : "threadsUnread");
                    return count == null ? 0 : count.intValue();
                } catch (Gone e) {
                    return null;
                }
            }));
        }
        Map<String, Integer> out = new HashMap<String, Integer>();
        try {
            for (Map.Entry<String, Future<Integer>> entry : pending.entrySet()) {
                Integer count = entry.getValue().get();
                if (count != null) out.put(entry.getKey(), count);
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException(cause == null ? "Gmail failed." : String.valueOf(cause.getMessage()));
        } finally {
            for (Future<Integer> future : pending.values()) future.cancel(true);
        }
        return out;
    }

    /** The user's own labels, by name. */
    public List<Folder> labels() throws IOException, InterruptedException {
        List<Folder> out = new ArrayList<Folder>();
        for (Object item : Json.children(get("/labels", ""), "labels")) {
            Map<String, Object> label = Json.object(item);
            if (!"user".equals(Json.text(label, "type"))) continue;
            out.add(Folder.label(Json.string(label, "id"), Json.string(label, "name")));
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** A file's bytes. */
    public byte[] attachment(Attachment attachment) throws IOException, InterruptedException {
        if (attachment.id.length() == 0) return Mime.decode(attachment.data);
        if (attachment.size > MAX_FILE) {
            throw new IOException(attachment.name + " is " + Mime.size(attachment.size) + "; the phone can fetch up to about 6 MB.");
        }
        String data = Json.text(get("/messages/" + path(attachment.message) + "/attachments/" + path(attachment.id), ""), "data");
        return Mime.decode(data == null ? "" : data);
    }

    // ---- Changing ----

    public void change(String threadId, Change change) throws IOException, InterruptedException {
        if (change == Change.TRASH) post("/threads/" + path(threadId) + "/trash", "");
        else if (change == Change.UNTRASH) post("/threads/" + path(threadId) + "/untrash", "");
        else relabel(threadId, change.add, change.remove);
    }

    public void relabel(String threadId, List<String> add, List<String> remove) throws IOException, InterruptedException {
        post("/threads/" + path(threadId) + "/modify",
            Json.write(Json.map("addLabelIds", new ArrayList<Object>(add), "removeLabelIds", new ArrayList<Object>(remove))));
    }

    // ---- Writing ----

    /** Sends it; a draft is sent as itself, so it leaves Drafts. Returns the sent message's id. */
    public String send(Outgoing out) throws IOException, InterruptedException {
        out.recipients();
        if (out.draftId.length() > 0) {
            save(out);
            return Json.string(post("/drafts/send", Json.write(Json.map("id", out.draftId))), "id");
        }
        Map<String, Object> meta = Json.map();
        if (out.threadId.length() > 0) meta.put("threadId", out.threadId);
        return Json.string(upload("POST", "/messages/send", meta, raw(out)), "id");
    }

    /** Saves it to Gmail's drafts, replacing its earlier save; returns the draft's id. */
    public String save(Outgoing out) throws IOException, InterruptedException {
        Map<String, Object> message = Json.map();
        if (out.threadId.length() > 0) message.put("threadId", out.threadId);
        Map<String, Object> saved;
        if (out.draftId.length() > 0) {
            saved = upload("PUT", "/drafts/" + path(out.draftId), Json.map("id", out.draftId, "message", message), raw(out));
        } else {
            saved = upload("POST", "/drafts", Json.map("message", message), raw(out));
        }
        out.draftId = Json.string(saved, "id");
        return out.draftId;
    }

    /** Throws the draft away. */
    public void discard(String draftId) throws IOException, InterruptedException {
        request("DELETE", "/drafts/" + path(draftId), "", null, null);
    }

    /** The draft holding this message, or null when it was sent or deleted. */
    public String draftId(String messageId) throws IOException, InterruptedException {
        String page = null;
        do {
            Map<String, Object> found = get("/drafts", "maxResults=500" + (page == null ? "" : "&pageToken=" + encode(page)));
            for (Object item : Json.children(found, "drafts")) {
                Map<String, Object> draft = Json.object(item);
                if (messageId.equals(Json.text(Json.child(draft, "message"), "id"))) return Json.string(draft, "id");
            }
            page = Json.text(found, "nextPageToken");
        } while (page != null && page.length() > 0);
        return null;
    }

    private String raw(Outgoing out) throws IOException, InterruptedException {
        Me user = me();
        List<Address> chosen = Address.parse(out.from);
        Address from = chosen.isEmpty() || !user.is(chosen.get(0).email) ? user.addresses.get(0) : chosen.get(0);
        String raw = out.raw(from, System.currentTimeMillis(), this::attachment);
        if (raw.length() > 34000000) throw new IOException("The message is too big for Gmail.");
        return raw;
    }

    // ---- The API ----

    /** A 404 from Gmail: what was asked for is gone. */
    static final class Gone extends IOException {
        Gone(String message) {
            super(message);
        }
    }

    private Map<String, Object> get(String path, String query) throws IOException, InterruptedException {
        return request("GET", path, query, null, null);
    }

    private Map<String, Object> post(String path, String json) throws IOException, InterruptedException {
        return request("POST", path, "", json, json.length() == 0 ? null : "application/json");
    }

    /** A message to an upload endpoint with its metadata, as one multipart/related body; up to 35 MB. */
    private Map<String, Object> upload(String method, String path, Map<String, Object> meta, String raw)
        throws IOException, InterruptedException {
        String boundary = Outgoing.boundary();
        String body = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + Json.write(meta)
            + "\r\n--" + boundary + "\r\nContent-Type: message/rfc822\r\n\r\n" + raw + "\r\n--" + boundary + "--\r\n";
        return request(method, "/upload/gmail/v1/users/me" + path, "uploadType=multipart", body,
            "multipart/related; boundary=" + boundary);
    }

    private Map<String, Object> request(String method, String path, String query, String body, String type)
        throws IOException, InterruptedException {
        String url = api + (path.startsWith("/upload/") ? path : "/gmail/v1/users/me" + path)
            + (query.length() > 0 ? "?" + query : "");
        for (int attempt = 0; ; attempt++) {
            String bearer = access();
            Http.Request request = Http.Request.to(URI.create(url)).method(method)
                .header("Authorization", "Bearer " + bearer).timeout(body != null && body.length() > 100000 ? 300000 : 60000);
            if (body != null) request.post(body);
            if (type != null) request.header("Content-Type", type);
            Http.Response response;
            try {
                response = http.send(request);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("exceeds 8 MB")) {
                    throw new IOException("That is too big for the phone to fetch.");
                }
                throw e;
            }
            int status = response.status();
            if (status == 401 && attempt == 0) {
                // Revoked or expired early: a fresh token, once.
                forget(bearer);
                continue;
            }
            if (status / 100 == 2) {
                if (response.body().trim().length() == 0) return Json.map();
                try {
                    return Json.object(Json.parse(response.body()));
                } catch (RuntimeException e) {
                    throw new IOException("Gmail sent something unreadable.");
                }
            }
            String message = problem(response);
            if (status == 404) throw new Gone("Gmail: that isn't there any more.");
            if (status == 401) throw new IOException("Gmail rejected the login. Run `delta gmail login` again on a computer.");
            if (status == 403 && message.toLowerCase(Locale.US).matches(".*(scope|permission).*")) {
                throw new IOException("The Gmail login lacks a permission. Run `delta gmail login` again and allow everything.");
            }
            if (status == 429) throw new IOException("Gmail: too many requests");
            throw new IOException("Gmail: " + (message.length() > 0 ? message : "HTTP " + status));
        }
    }

    /** Gmail's own message, cut to fit a screen. */
    static String problem(Http.Response response) {
        try {
            String message = Json.text(Json.child(Json.object(Json.parse(response.body())), "error"), "message");
            if (message == null) return "";
            message = message.trim();
            return message.length() > 200 ? message.substring(0, 200) + "…" : message;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An id in a path: Gmail's are hex or base64url, so anything else is refused rather than escaped. */
    private static String path(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new IOException("That is not a Gmail id.");
        return id;
    }
}
