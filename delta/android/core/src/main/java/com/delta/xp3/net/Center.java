package com.delta.xp3.net;

import com.delta.xp3.data.Chat;
import com.delta.xp3.data.RemoteFile;

import com.xp3.common.Http;
import com.xp3.common.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The Delta command center: the same few routes the `delta` CLI uses, from
 * the phone's side.
 *
 * An agent pushes a file with the CLI; this lists it, fetches it, and deletes
 * it. The phone can push one too, for an agent to `delta pull`. It also keeps
 * the cloud accounts the sessions run on, and hands out their access tokens,
 * and runs the backup sessions it keeps itself (▲ in Chats), whose event
 * streams read like the cloud's. Every request carries the bearer token
 * `delta-cc gen-token` printed.
 */
public final class Center implements EventSource {

    /** Reported while a download runs, and asked whether to stop. */
    public interface Progress {
        boolean cancelled();

        /** `total` is -1 when the server did not say how big the file is. */
        void progress(long done, long total);
    }

    /** Thrown out of `download` once Progress reports a cancel. */
    public static final class Cancelled extends IOException {
        public Cancelled() {
            super("Cancelled");
        }
    }

    /** A cloud account, as the command center lists it; it keeps the login itself. */
    public static final class Account {
        /** The provider's id for the account, which goes in a route. */
        public final String id;
        public final String email;
        /** The subscription, as the provider names it ("claude_pro"), or null. */
        public final String plan;

        public Account(String id, String email) {
            this(id, email, null);
        }

        public Account(String id, String email, String plan) {
            this.id = id;
            this.email = email == null || email.length() == 0 ? id : email;
            this.plan = plan == null || plan.length() == 0 ? null : plan;
        }
    }

    /**
     * How much of an account's 5-hour and weekly limits is used, in percent
     * (null when the report doesn't say), and when each resets, in epoch
     * milliseconds (0 when it doesn't say).
     */
    public static final class Usage {
        public final Double fiveHour;
        public final long fiveHourResets;
        public final Double weekly;
        public final long weeklyResets;

        Usage(Double fiveHour, long fiveHourResets, Double weekly, long weeklyResets) {
            this.fiveHour = fiveHour;
            this.fiveHourResets = fiveHourResets;
            this.weekly = weekly;
            this.weeklyResets = weeklyResets;
        }

        static Usage of(Map<String, Object> report) {
            Map<String, Object> five = Json.child(report, "five_hour");
            Map<String, Object> week = Json.child(report, "seven_day");
            return new Usage(Json.number(five, "utilization"), Json.instant(five, "resets_at"),
                Json.number(week, "utilization"), Json.instant(week, "resets_at"));
        }

        /**
         * When the account has room again, if either window is used up; 0
         * when it has room. A full window with no reset time still rules
         * the account out for now: an hour is a guess the next check corrects.
         */
        public long limitedUntil() {
            return Math.max(full(fiveHour, fiveHourResets), full(weekly, weeklyResets));
        }

        private static long full(Double used, long resets) {
            if (used == null || used.doubleValue() < 100) return 0;
            return resets > 0 ? resets : System.currentTimeMillis() + 3600000L;
        }
    }

    /** The command center has the account, but its login no longer works. */
    public static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    private static final String SERVICE = "Command center";
    private static final String OLD =
        "The command center needs updating to keep the cloud accounts (delta update-cc).";

    private final Http http;
    private final String server;
    private final String token;

    /** `server` is an address with no trailing slash, as Config keeps it. */
    public Center(Http http, String server, String token) {
        this.http = http;
        this.server = server;
        this.token = token;
    }

    /** Whether the server answers and takes the token, as `delta set-auth` checks. */
    public void check() throws IOException, InterruptedException {
        call(request("/auth"));
    }

    /** Every file, newest first, as the command center orders them. */
    public List<RemoteFile> list() throws IOException, InterruptedException {
        String body = call(request("/files"));
        List<RemoteFile> files = new ArrayList<RemoteFile>();
        List<Object> listed;
        try {
            listed = Json.array(Json.parse(body));
        } catch (RuntimeException e) {
            throw new IOException("The command center sent an unreadable file list");
        }
        for (Object value : listed) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> entry = Json.object(value);
            String name = Json.text(entry, "name");
            Double size = Json.number(entry, "size");
            if (!RemoteFile.validName(name) || size == null) continue;
            files.add(new RemoteFile(name, size.longValue(), Json.instant(entry, "modified")));
        }
        return files;
    }

    /** Deletes a file. One that is already gone counts as deleted. */
    public void delete(String name) throws IOException, InterruptedException {
        Http.Response response = http.send(request("/files/" + segment(name)).method("DELETE"));
        if (response.status() == 404) return;
        require(response.status());
    }

    /**
     * Writes a file's bytes to `out` as they arrive. The caller owns `out`
     * and decides what a partial file is worth; a short body is an error here.
     */
    public void download(String name, OutputStream out, Progress progress)
        throws IOException, InterruptedException {
        Http.Stream stream = http.stream(request("/files/" + segment(name)).identity());
        try {
            require(stream.status());
            long total = stream.length();
            long done = 0;
            progress.progress(0, total);
            InputStream in = stream.stream();
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (progress.cancelled()) throw new Cancelled();
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                out.write(buffer, 0, count);
                done += count;
                progress.progress(done, total);
            }
            if (total >= 0 && done != total) throw new IOException("The download stopped part way.");
        } finally {
            stream.close();
        }
    }

    /**
     * Sends `length` bytes from `in` as a new file called `name`, as
     * `delta push` does. Returns false, having stored nothing, when the
     * command center already has a file of that name: it never replaces one.
     */
    public boolean upload(String name, InputStream in, long length, final Progress progress)
        throws IOException, InterruptedException {
        Http.Request request = request("/files/" + segment(name))
            .method("PUT")
            .header("Content-Type", "application/octet-stream")
            .timeout(120000);
        Http.Response response = http.upload(request, in, length, (done, total) -> {
            if (progress.cancelled()) throw new Cancelled();
            progress.progress(done, total);
        });
        if (response.status() == 409) return false;
        if (response.status() == 413) throw new IOException("That file is too big for the command center.");
        if (response.status() == 400) throw new IOException("The command center refused that name.");
        require(response.status());
        return true;
    }

    // ------------------------------------------------------------------
    // Backup sessions
    // ------------------------------------------------------------------

    /** The backup sessions, most recently active first. */
    public List<Chat> sessions() throws IOException, InterruptedException {
        String body = session(request("/agent-sessions"));
        List<Chat> chats = new ArrayList<Chat>();
        try {
            for (Object value : Json.children(Json.object(Json.parse(body)), "data")) {
                if (!(value instanceof Map)) continue;
                Map<String, Object> session = Json.object(value);
                String id = Json.text(session, "id");
                if (!Chat.validId(id) || !Chat.backup(id)) continue;
                chats.add(new Chat(id, Json.text(session, "title"), Json.text(session, "status"),
                    Json.instant(session, "updated")));
            }
        } catch (RuntimeException e) {
            throw new IOException("The command center sent an unreadable session list");
        }
        Collections.sort(chats, (a, b) -> a.updated == b.updated ? 0 : a.updated > b.updated ? -1 : 1);
        return chats;
    }

    /**
     * Starts a backup session with its first message, and returns its id.
     * The command center writes the session's setup itself.
     */
    public String create(String title, String uuid, String text, List<byte[]> jpegs)
        throws IOException, InterruptedException {
        String body = session(request("/agent-sessions").post(message(title, uuid, text, jpegs)));
        String id;
        try {
            id = Json.string(Json.object(Json.parse(body)), "id");
        } catch (RuntimeException e) {
            id = null;
        }
        if (!Chat.validId(id) || !Chat.backup(id)) throw new IOException("The command center returned an unexpected session id");
        return id;
    }

    /** Sends a follow-up message; one sent mid-turn reaches the agent between its steps. */
    public void send(String session, String uuid, String text, List<byte[]> jpegs)
        throws IOException, InterruptedException {
        session(request(sessionPath(session) + "/messages").post(message(null, uuid, text, jpegs)));
    }

    public void interrupt(String session) throws IOException, InterruptedException {
        session(request(sessionPath(session) + "/interrupt").post("{}"));
    }

    /** Deletes a backup session with its computer. One already gone counts as deleted. */
    public void deleteSession(String session) throws IOException, InterruptedException {
        Http.Response response = http.send(request(sessionPath(session)).method("DELETE"));
        if (response.status() == 404) return;
        sessionStatus(response.status(), response.body());
    }

    /** The session's event stream after `afterSequence`, as Cloud.open. */
    @Override
    public Http.Stream open(String session, long afterSequence) throws IOException, InterruptedException {
        String path = sessionPath(session) + "/events";
        if (afterSequence > 0) path += "?from_sequence_num=" + afterSequence;
        Http.Request request = request(path).header("Accept", "text/event-stream").identity();
        if (afterSequence > 0) request.header("Last-Event-ID", String.valueOf(afterSequence));
        Http.Stream stream = http.stream(request);
        if (stream.status() < 200 || stream.status() >= 300) {
            int status = stream.status();
            stream.close();
            sessionStatus(status, null);
        }
        return stream;
    }

    private static String sessionPath(String session) {
        if (!Chat.validId(session) || !Chat.backup(session)) throw new IllegalArgumentException("Not a backup session");
        return "/agent-sessions/" + session;
    }

    private static String message(String title, String uuid, String text, List<byte[]> jpegs) {
        List<Object> images = new ArrayList<Object>();
        if (jpegs != null) for (byte[] jpeg : jpegs) images.add(Base64.getEncoder().encodeToString(jpeg));
        Map<String, Object> body = Json.map("uuid", uuid, "text", text, "images", images);
        if (title != null) body.put("title", title);
        return Json.write(body);
    }

    private String session(Http.Request request) throws IOException, InterruptedException {
        Http.Response response = http.send(request);
        sessionStatus(response.status(), response.body());
        return response.body();
    }

    /** The command center explains its refusals, and they are worth showing. */
    private static void sessionStatus(int status, String body) throws IOException {
        if (status >= 200 && status < 300) return;
        if (status == 401) throw new IOException("The command center refused the token.");
        if (status == 404) throw new IOException("That session is no longer on the command center.");
        if (body != null) {
            try {
                String said = Json.text(Json.object(Json.parse(body)), "error");
                if (said != null && said.trim().length() > 0) throw new IOException(said.trim());
            } catch (RuntimeException e) {
                // Not the command center's JSON; say it generically below.
            }
        }
        Http.requireSuccess(status, SERVICE);
    }

    // ------------------------------------------------------------------
    // Cloud accounts
    // ------------------------------------------------------------------

    /** The cloud accounts, in the order new sessions try them. */
    public List<Account> accounts() throws IOException, InterruptedException {
        Http.Response response = http.send(request("/cloud-accounts"));
        if (response.status() == 404 || response.status() == 405) throw new IOException(OLD);
        require(response.status());
        List<Account> accounts = new ArrayList<Account>();
        try {
            for (Object value : Json.array(Json.parse(response.body()))) {
                if (!(value instanceof Map)) continue;
                Map<String, Object> entry = Json.object(value);
                String id = Json.text(entry, "id");
                if (!validAccount(id)) continue;
                accounts.add(new Account(id, Json.text(entry, "email"), Json.text(entry, "plan")));
            }
        } catch (RuntimeException e) {
            throw new IOException("The command center sent an unreadable account list");
        }
        return accounts;
    }

    /**
     * An access token for an account. `refused` is a token the cloud turned
     * down, or null; the command center renews the login if it is still the
     * current one.
     */
    public CloudAuth.Grant token(String account, String refused) throws IOException, InterruptedException {
        String body = refused == null ? "{}" : Json.write(Json.map("refused", refused));
        Http.Response response = http.send(request(accountPath(account) + "/token")
            .header("Content-Type", "application/json")
            .post(body));
        requireAccount(response.status());
        try {
            Map<String, Object> grant = Json.object(Json.parse(response.body()));
            Double expires = Json.number(grant, "expires_at");
            return new CloudAuth.Grant(Json.string(grant, "access_token"),
                expires == null ? 0 : expires.longValue());
        } catch (RuntimeException e) {
            throw new IOException("The command center sent an unreadable token");
        }
    }

    /** How much of the account's 5-hour and weekly limits is used. */
    public Usage usage(String account) throws IOException, InterruptedException {
        Http.Response response = http.send(request(accountPath(account) + "/usage"));
        requireAccount(response.status());
        try {
            return Usage.of(Json.object(Json.parse(response.body())));
        } catch (RuntimeException e) {
            throw new IOException("The command center sent an unreadable usage report");
        }
    }

    /**
     * When the account's usage limit lifts, in epoch milliseconds, if its
     * 5-hour or weekly window is used up; 0 when it has room.
     */
    public long limitedUntil(String account) throws IOException, InterruptedException {
        return usage(account).limitedUntil();
    }

    /**
     * Stores a conversation carried over to another session, and returns the
     * id `delta resume` prints it by.
     */
    public String putConversation(String text) throws IOException, InterruptedException {
        Http.Response response = http.send(request("/conversations")
            .header("Content-Type", "text/plain; charset=utf-8")
            .timeout(300000)
            .post(text));
        if (response.status() == 404 || response.status() == 405) throw new IOException(OLD);
        if (response.status() == 413) throw new IOException("The conversation is too big for the command center.");
        require(response.status());
        try {
            String id = Json.string(Json.object(Json.parse(response.body())), "id");
            if (!validAccount(id)) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException e) {
            throw new IOException("The command center sent an unreadable conversation id");
        }
    }

    /**
     * Hands an old-style login, kept on the phone by earlier versions, to the
     * command center, which keeps it from then on.
     */
    public void addAccount(String access, String refresh, long expiresAt) throws IOException, InterruptedException {
        Map<String, Object> login = Json.map("refresh_token", refresh);
        if (access != null) login.put("access_token", access);
        if (expiresAt > 0) login.put("expires_at", Long.valueOf(expiresAt));
        Http.Response response = http.send(request("/cloud-accounts")
            .header("Content-Type", "application/json")
            .post(Json.write(login)));
        if (response.status() == 404 || response.status() == 405) throw new IOException(OLD);
        if (response.status() == 403) throw new Refused("The cloud login on this phone no longer works.");
        require(response.status());
    }

    private static String accountPath(String account) {
        if (!validAccount(account)) throw new IllegalArgumentException("Not an account id");
        return "/cloud-accounts/" + account;
    }

    /** Account ids go in a route as they are, so only the characters UUIDs use get in. */
    static boolean validAccount(String id) {
        if (id == null || id.length() == 0 || id.length() > 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static void requireAccount(int status) throws IOException {
        if (status == 403) {
            throw new Refused("A cloud account's login stopped working. Sign it in again with delta accounts login.");
        }
        if (status == 404) throw new IOException("That cloud account is no longer on the command center.");
        if (status == 405) throw new IOException(OLD);
        require(status);
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private Http.Request request(String path) {
        return Http.Request.to(URI.create(server + path))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .timeout(60000);
    }

    private String call(Http.Request request) throws IOException, InterruptedException {
        Http.Response response = http.send(request);
        require(response.status());
        return response.body();
    }

    private static void require(int status) throws IOException {
        if (status == 401) throw new IOException("The command center refused the token.");
        if (status == 404) throw new IOException("That file is no longer on the command center.");
        Http.requireSuccess(status, SERVICE);
    }

    /**
     * A file name as one URL path segment. Everything but the unreserved
     * characters is escaped, so a space or a '+' reaches the server as itself
     * rather than as each other.
     */
    static String segment(String name) {
        StringBuilder out = new StringBuilder();
        byte[] bytes;
        try {
            bytes = name.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        for (byte b : bytes) {
            int c = b & 0xff;
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
            if (plain) out.append((char) c);
            else out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return out.toString();
    }
}
