package com.source.xp3.net;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * The command center login: its address and the delta token, the same pair
 * `delta set-auth` saves for the CLI and the Delta app bakes in.
 *
 * Validated the way the Delta app validates it: https only (the token would
 * otherwise cross the network readable by anyone on the way), a host and an
 * optional path prefix, no trailing slash, and a token of visible characters
 * that can sit in a header.
 */
public final class Login {

    private final String server;
    private final String token;

    private Login(String server, String token) {
        this.server = server;
        this.token = token;
    }

    public static Login blank() {
        return new Login(null, null);
    }

    /** Throws IllegalArgumentException, phrased for a person, when either part is unusable. */
    public static Login of(String server, String token) {
        return new Login(server(server), credential(token));
    }

    /** {"server": "...", "token": "..."}; anything unreadable is a blank login. */
    public static Login parse(String json) {
        if (json == null || json.trim().length() == 0) return blank();
        try {
            Map<String, Object> root = Json.object(Json.parse(json));
            return of(Json.text(root, "server"), Json.text(root, "token"));
        } catch (RuntimeException e) {
            return blank();
        }
    }

    public String toJson() {
        return Json.write(Json.map("server", server, "token", token));
    }

    public boolean ready() {
        return server != null && token != null;
    }

    public String server() {
        return server;
    }

    public String token() {
        return token;
    }

    /** The address without its scheme, for showing on a 240px screen. */
    public String host() {
        if (server == null) return "";
        URI uri = URI.create(server);
        return uri.getHost() == null ? server : uri.getHost();
    }

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

    private static String credential(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() == 0) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 33 || c > 126) {
                throw new IllegalArgumentException("A token must be visible characters with no spaces");
            }
        }
        return trimmed;
    }
}
