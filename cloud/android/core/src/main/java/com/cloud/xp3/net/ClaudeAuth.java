package com.cloud.xp3.net;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * The bundled Claude Pro/Max OAuth login, renewed as it expires.
 *
 * The access token is short-lived and is renewed from the refresh token. Each
 * renewal also replaces the refresh token, so the new pair is reported to
 * `Rotated` to be written down: the old refresh token stops working.
 */
public final class ClaudeAuth {

    private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    private static final URI TOKEN_URL = URI.create("https://platform.claude.com/v1/oauth/token");
    /**
     * Asked for again on every renewal, as Claude Code itself does: the
     * sessions scope is what lets this login drive cloud sessions at all.
     */
    private static final String SCOPES =
        "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload";
    private static final long SKEW_MS = 5L * 60L * 1000L;

    public interface Rotated {
        void store(String access, String refresh, long expiresAt);
    }

    private final Http http;
    private final URI endpoint;
    private final Rotated listener;
    private String access;
    private String refresh;
    private long expiresAt;

    public ClaudeAuth(Http http, String access, String refresh, long expiresAt, Rotated listener) {
        this(http, TOKEN_URL, access, refresh, expiresAt, listener);
    }

    ClaudeAuth(Http http, URI endpoint, String access, String refresh, long expiresAt, Rotated listener) {
        this.http = http;
        this.endpoint = endpoint;
        this.access = empty(access) ? null : access;
        this.refresh = empty(refresh) ? null : refresh;
        this.expiresAt = expiresAt;
        this.listener = listener;
    }

    /** The bearer token, renewing it first if it is due to expire. */
    public synchronized String token() throws IOException, InterruptedException {
        if (access != null && (expiresAt <= 0 || System.currentTimeMillis() < expiresAt - SKEW_MS)) {
            return access;
        }
        if (refresh == null) {
            if (access != null) return access;
            throw new IOException("This build has no Claude login");
        }
        renew();
        return access;
    }

    /** Called after a 401, so the retry renews rather than repeating a refused token. */
    public synchronized void invalidate() {
        // Zero means an access token with unknown lifetime. One is an
        // unambiguously expired instant and forces the refresh on the retry.
        if (refresh != null) expiresAt = 1;
    }

    private void renew() throws IOException, InterruptedException {
        Http.Response response = http.send(Http.Request.to(endpoint)
            .timeout(60000)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(Json.write(Json.map(
                "grant_type", "refresh_token",
                "client_id", CLIENT_ID,
                "refresh_token", refresh,
                "scope", SCOPES
            ))));
        if (response.status() == 400 || response.status() == 401 || response.status() == 403) {
            throw new IOException("The Claude login was rejected; rebuild the app with a fresh login");
        }
        Http.requireSuccess(response.status(), "Claude login");
        try {
            Map<String, Object> json = Json.object(Json.parse(response.body()));
            String nextAccess = Json.string(json, "access_token");
            // Rotation is the norm, but a renewal that leaves the refresh
            // token out means the current one stays good.
            String nextRefresh = Json.text(json, "refresh_token");
            if (empty(nextRefresh)) nextRefresh = refresh;
            Double seconds = Json.number(json, "expires_in");
            if (seconds == null || seconds.doubleValue() <= 0) throw new IllegalArgumentException();
            access = nextAccess;
            refresh = nextRefresh;
            expiresAt = System.currentTimeMillis() + (long) (seconds.doubleValue() * 1000);
        } catch (RuntimeException e) {
            throw new IOException("The Claude login endpoint returned something unreadable");
        }
        if (listener != null) listener.store(access, refresh, expiresAt);
    }

    private static boolean empty(String value) {
        return value == null || value.trim().length() == 0;
    }
}
