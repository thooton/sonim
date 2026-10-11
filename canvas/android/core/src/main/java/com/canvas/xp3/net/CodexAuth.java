package com.canvas.xp3.net;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.Map;

/**
 * A ChatGPT login that renews itself.
 *
 * The access token this is built from lasts about ten days, which for an app
 * installed once and then carried around is no time at all: bundling one would
 * mean a Chat tab that works for a week and then says the credentials expired.
 * So what is bundled is the refresh token, and the access token is obtained
 * from it and renewed a couple of minutes before it lapses.
 *
 * The refresh token rotates. Every renewal returns a new one and the old one is
 * spent, so a rotation that is not written down is a login that works exactly
 * once more. That is what the listener is for, and why it is called before the
 * new access token is handed out rather than after.
 *
 * The flow matches the one the Codex CLI uses, checked against Pi's
 * implementation of it rather than guessed at: a form POST to the OAuth token
 * endpoint with the CLI's own client id.
 */
public final class CodexAuth implements Credentials {

    /** The Codex CLI's public OAuth client id. */
    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final URI TOKEN_URL = URI.create("https://auth.openai.com/oauth/token");
    /** Where the account id lives inside the access token. */
    private static final String CLAIM = "https://api.openai.com/auth";
    /** Renew this far ahead, so a token cannot lapse mid-request. */
    private static final long SKEW_MS = 120000;

    /** Told about every rotation, so the new refresh token outlives the process. */
    public interface Rotated {
        void store(String access, String refresh, String account);
    }

    private final Http http;
    private final URI endpoint;
    private final Rotated listener;

    private String access;
    private String refresh;
    private String account;
    private long expiresAt;

    public CodexAuth(Http http, String access, String refresh, String account, Rotated listener) {
        this(http, TOKEN_URL, access, refresh, account, listener);
    }

    /** Endpoint injection is for the local tests, never for configuration. */
    CodexAuth(Http http, URI endpoint, String access, String refresh, String account, Rotated listener) {
        this.http = http;
        this.endpoint = endpoint;
        this.access = empty(access) ? null : access;
        this.refresh = empty(refresh) ? null : refresh;
        this.account = empty(account) ? null : account;
        this.expiresAt = this.access == null ? 0 : expiryOf(this.access);
        this.listener = listener;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().length() == 0;
    }

    @Override
    public synchronized String token() throws IOException, InterruptedException {
        if (access != null && System.currentTimeMillis() < expiresAt - SKEW_MS) return access;
        if (refresh == null) {
            // Nothing to renew with. An access token that may already have
            // lapsed is still worth sending: the service's own answer is better
            // than this guessing, and the clock on a phone can be wrong.
            if (access != null) return access;
            throw new IOException("No ChatGPT login is set up");
        }
        renew();
        return access;
    }

    @Override
    public synchronized String account() throws IOException, InterruptedException {
        if (account == null) token();
        return account;
    }

    @Override
    public synchronized void invalidate() {
        // Only when there is a way back. Discarding the one token available
        // would turn a single rejected request into a permanently broken tab.
        if (refresh != null) expiresAt = 0;
    }

    private void renew() throws IOException, InterruptedException {
        String body = "grant_type=refresh_token"
            + "&refresh_token=" + encode(refresh)
            + "&client_id=" + encode(CLIENT_ID);
        Http.Response response = http.send(Http.Request.to(endpoint)
            .timeout(60000)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .post(body));

        if (response.status() == 400 || response.status() == 401 || response.status() == 403) {
            // A spent or revoked refresh token. Most often this is the same
            // login having been renewed somewhere else, which rotates the token
            // out from under this copy of it.
            throw new IOException("The ChatGPT login was rejected. Sign in with codex again and re-bundle, or paste a new refresh token in Settings.");
        }
        Http.requireSuccess(response.status(), "ChatGPT login");

        String nextAccess;
        String nextRefresh;
        try {
            Map<String, Object> json = Json.object(Json.parse(response.body()));
            nextAccess = Json.string(json, "access_token");
            nextRefresh = Json.text(json, "refresh_token");
        } catch (RuntimeException e) {
            // Never echo the body: it is a set of credentials.
            throw new IOException("The ChatGPT login endpoint returned something unreadable");
        }

        access = nextAccess;
        if (!empty(nextRefresh)) refresh = nextRefresh;
        String found = accountOf(access);
        if (found != null) account = found;
        expiresAt = expiryOf(access);
        // Written down before the token is used, so a crash mid-request cannot
        // lose a rotation and strand the login.
        if (listener != null) listener.store(access, refresh, account);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * When the access token stops being valid, from its own contents.
     *
     * Taken from the token rather than from the endpoint's expires_in because
     * it is also needed for a token that was bundled at build time, which
     * arrived without a response around it. A token that cannot be read is
     * treated as never expiring: the service is a better judge of that than a
     * parser here, and a 401 is handled anyway.
     */
    static long expiryOf(String jwt) {
        Map<String, Object> claims = claims(jwt);
        if (claims == null) return Long.MAX_VALUE;
        Double exp = Json.number(claims, "exp");
        return exp == null ? Long.MAX_VALUE : (long) (exp.doubleValue() * 1000);
    }

    /** The ChatGPT account id carried inside the access token. */
    static String accountOf(String jwt) {
        Map<String, Object> claims = claims(jwt);
        if (claims == null) return null;
        String found = Json.text(Json.child(claims, CLAIM), "chatgpt_account_id");
        return empty(found) ? null : found;
    }

    private static Map<String, Object> claims(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        try {
            String payload = parts[1];
            int padding = payload.length() % 4;
            if (padding != 0) {
                StringBuilder padded = new StringBuilder(payload);
                for (int i = padding; i < 4; i++) padded.append('=');
                payload = padded.toString();
            }
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            return Json.object(Json.parse(new String(decoded, "UTF-8")));
        } catch (RuntimeException e) {
            return null;
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }
}
