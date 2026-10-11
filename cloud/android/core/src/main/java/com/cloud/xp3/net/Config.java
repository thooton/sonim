package com.cloud.xp3.net;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Claude login, and nothing else.
 *
 * There is no model, environment or repository to configure: the cloud session
 * picks its own model, the first cloud environment on the account is used, and
 * every session starts with an empty sandbox. What changes is the rotating
 * OAuth pair, which is written back here every time Anthropic renews it.
 */
public final class Config {

    private final String accessToken;
    private final String refreshToken;
    private final long expiresAt;

    private Config(String accessToken, String refreshToken, long expiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
    }

    public static Config blank() {
        return new Config(null, null, 0);
    }

    public static Config of(String accessToken, String refreshToken, long expiresAt) {
        return new Config(credential(accessToken), credential(refreshToken), Math.max(0, expiresAt));
    }

    public static Config parse(String json) {
        Map<String, Object> claude = Json.child(Json.object(Json.parse(json)), "claude");
        Double expiry = Json.number(claude, "expiresAt");
        return of(
            Json.text(claude, "accessToken"),
            Json.text(claude, "refreshToken"),
            expiry == null ? 0 : (long) expiry.doubleValue()
        );
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
                throw new IllegalArgumentException("The login must be visible characters with no spaces");
            }
        }
        return trimmed;
    }

    public String toJson() {
        Map<String, Object> claude = new LinkedHashMap<String, Object>();
        if (accessToken != null) claude.put("accessToken", accessToken);
        if (refreshToken != null) claude.put("refreshToken", refreshToken);
        if (expiresAt > 0) claude.put("expiresAt", Long.valueOf(expiresAt));
        return Json.write(Json.map("claude", claude));
    }

    /** Whether there is a login to reach the cloud with. */
    public boolean ready() {
        return refreshToken != null || accessToken != null;
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
}
