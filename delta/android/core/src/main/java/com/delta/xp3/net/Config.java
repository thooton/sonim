package com.delta.xp3.net;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The two logins the app holds, and nothing else.
 *
 * The Claude login is the rotating OAuth pair, written back here every time
 * Anthropic renews it. The command center is an address and a bearer token,
 * the same pair `delta set-auth` saves for the CLI; it never rotates.
 *
 * There is no model, environment or repository to configure: the cloud session
 * picks its own model, the first cloud environment on the account is used, and
 * every session starts with an empty sandbox.
 */
public final class Config {

    private final String accessToken;
    private final String refreshToken;
    private final long expiresAt;
    private final String server;
    private final String token;

    private Config(String accessToken, String refreshToken, long expiresAt, String server, String token) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
        this.server = server;
        this.token = token;
    }

    public static Config blank() {
        return new Config(null, null, 0, null, null);
    }

    /** A Claude login alone. */
    public static Config of(String accessToken, String refreshToken, long expiresAt) {
        return blank().withClaude(accessToken, refreshToken, expiresAt);
    }

    public static Config parse(String json) {
        Map<String, Object> root = Json.object(Json.parse(json));
        Map<String, Object> claude = Json.child(root, "claude");
        Map<String, Object> center = Json.child(root, "center");
        Double expiry = Json.number(claude, "expiresAt");
        Config config = of(
            Json.text(claude, "accessToken"),
            Json.text(claude, "refreshToken"),
            expiry == null ? 0 : (long) expiry.doubleValue()
        );
        String server = Json.text(center, "server");
        String token = Json.text(center, "token");
        if (server == null && token == null) return config;
        try {
            return config.withCenter(server, token);
        } catch (IllegalArgumentException e) {
            // A command center this version refuses must not cost the Claude
            // login saved beside it.
            return config;
        }
    }

    public Config withClaude(String accessToken, String refreshToken, long expiresAt) {
        return new Config(credential(accessToken), credential(refreshToken), Math.max(0, expiresAt), server, token);
    }

    /** The Claude half of `other`, with this config's command center. */
    public Config withClaudeOf(Config other) {
        return new Config(other.accessToken, other.refreshToken, other.expiresAt, server, token);
    }

    /**
     * Both halves are required: an address with no token, or the other way
     * round, could only fail later and less clearly.
     */
    public Config withCenter(String server, String token) {
        String address = server(server);
        String bearer = credential(token);
        if (address == null || bearer == null) {
            throw new IllegalArgumentException("The command center needs both an address and a token");
        }
        return new Config(accessToken, refreshToken, expiresAt, address, bearer);
    }

    /** The command center of `other`, with this config's Claude login. */
    public Config withCenterOf(Config other) {
        return new Config(accessToken, refreshToken, expiresAt, other.server, other.token);
    }

    /**
     * The command center's address as the CLI keeps it: https only, a host,
     * no trailing slash. A path is allowed, for a command center behind a
     * reverse proxy at a prefix. The app refuses plain http outright, because
     * the token would cross the network readable by anyone on the way.
     */
    static String server(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.length() == 0) return null;
        if (!trimmed.contains("://")) trimmed = "https://" + trimmed;
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That is not a web address");
        }
        if (!"https".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.US))) {
            throw new IllegalArgumentException("The command center must use https");
        }
        if (uri.getHost() == null || uri.getHost().length() == 0) {
            throw new IllegalArgumentException("That is not a web address");
        }
        if (uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("The command center address is just https://host");
        }
        return trimmed;
    }

    /**
     * A credential goes into an HTTP header, so anything that could not sit in
     * one is refused here rather than at the point where it would split the
     * request in half.
     */
    private static String credential(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() == 0) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 33 || c > 126) {
                throw new IllegalArgumentException("A login must be visible characters with no spaces");
            }
        }
        return trimmed;
    }

    public String toJson() {
        Map<String, Object> claude = new LinkedHashMap<String, Object>();
        if (accessToken != null) claude.put("accessToken", accessToken);
        if (refreshToken != null) claude.put("refreshToken", refreshToken);
        if (expiresAt > 0) claude.put("expiresAt", Long.valueOf(expiresAt));
        Map<String, Object> root = Json.map("claude", claude);
        if (centerReady()) root.put("center", Json.map("server", server, "token", token));
        return Json.write(root);
    }

    /** Whether there is a login to reach the cloud with. */
    public boolean ready() {
        return refreshToken != null || accessToken != null;
    }

    /** Whether there is a command center to reach. */
    public boolean centerReady() {
        return server != null && token != null;
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public long expiresAt() {
        return expiresAt;
    }

    /** The command center's address, or null. */
    public String server() {
        return server;
    }

    /** The command center's bearer token, or null. */
    public String token() {
        return token;
    }
}
