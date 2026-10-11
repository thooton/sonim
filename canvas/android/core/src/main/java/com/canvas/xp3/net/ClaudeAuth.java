package com.canvas.xp3.net;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/** A Claude Pro/Max OAuth login, matching Pi's Anthropic flow. */
public final class ClaudeAuth implements Credentials {

    private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    private static final URI TOKEN_URL = URI.create("https://platform.claude.com/v1/oauth/token");
    private static final String AUTHORIZE_URL = "https://claude.ai/oauth/authorize";
    private static final String REDIRECT_URI = "http://localhost:53692/callback";
    private static final String SCOPES =
        "org:create_api_key user:profile user:inference user:sessions:claude_code " +
        "user:mcp_servers user:file_upload";
    private static final long SKEW_MS = 5L * 60L * 1000L;

    public interface Rotated {
        void store(String access, String refresh, long expiresAt);
    }

    /** The short-lived PKCE state kept while the browser login is open. */
    public static final class Login {
        private final String verifier;
        private final String url;

        private Login(String verifier, String url) {
            this.verifier = verifier;
            this.url = url;
        }

        public String url() {
            return url;
        }
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

    /** Starts the same manual-callback login Pi offers when localhost is unavailable. */
    public static Login begin() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String challenge;
        try {
            challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("UTF-8"))
            );
        } catch (Exception e) {
            throw new IllegalStateException("This phone cannot start a secure login", e);
        }
        String url = AUTHORIZE_URL
            + "?code=true"
            + "&client_id=" + encode(CLIENT_ID)
            + "&response_type=code"
            + "&redirect_uri=" + encode(REDIRECT_URI)
            + "&scope=" + encode(SCOPES)
            + "&code_challenge=" + encode(challenge)
            + "&code_challenge_method=S256"
            + "&state=" + encode(verifier);
        return new Login(verifier, url);
    }

    /** Exchanges a pasted callback URL or authorization code and stores the login. */
    public static ClaudeAuth exchange(Http http, Login login, String input, Rotated listener)
        throws IOException, InterruptedException {
        return exchange(http, TOKEN_URL, login, input, listener);
    }

    static ClaudeAuth exchange(Http http, URI endpoint, Login login, String input, Rotated listener)
        throws IOException, InterruptedException {
        Parsed parsed = parse(input);
        if (empty(parsed.code)) throw new IOException("Paste the final callback URL or authorization code");
        if (!empty(parsed.state) && !login.verifier.equals(parsed.state)) {
            throw new IOException("The Claude login did not match this sign-in attempt");
        }
        Map<String, Object> body = Json.map(
            "grant_type", "authorization_code",
            "client_id", CLIENT_ID,
            "code", parsed.code,
            "state", login.verifier,
            "redirect_uri", REDIRECT_URI,
            "code_verifier", login.verifier
        );
        Tokens tokens = post(http, endpoint, body, "Claude login");
        ClaudeAuth auth = new ClaudeAuth(http, endpoint, tokens.access, tokens.refresh,
            tokens.expiresAt, listener);
        if (listener != null) listener.store(tokens.access, tokens.refresh, tokens.expiresAt);
        return auth;
    }

    @Override
    public synchronized String token() throws IOException, InterruptedException {
        if (access != null && (expiresAt <= 0 || System.currentTimeMillis() < expiresAt - SKEW_MS)) {
            return access;
        }
        if (refresh == null) {
            if (access != null) return access;
            throw new IOException("No Claude login is set up");
        }
        renew();
        return access;
    }

    @Override
    public String account() {
        return null;
    }

    @Override
    public synchronized void invalidate() {
        // Zero means an imported access token with unknown lifetime. One is an
        // unambiguously expired instant and forces the refresh on the retry.
        if (refresh != null) expiresAt = 1;
    }

    private void renew() throws IOException, InterruptedException {
        Tokens tokens = post(http, endpoint, Json.map(
            "grant_type", "refresh_token",
            "client_id", CLIENT_ID,
            "refresh_token", refresh
        ), "Claude login");
        access = tokens.access;
        refresh = tokens.refresh;
        expiresAt = tokens.expiresAt;
        if (listener != null) listener.store(access, refresh, expiresAt);
    }

    private static Tokens post(Http http, URI endpoint, Map<String, Object> body, String label)
        throws IOException, InterruptedException {
        Http.Response response = http.send(Http.Request.to(endpoint)
            .timeout(60000)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(Json.write(body)));
        if (response.status() == 400 || response.status() == 401 || response.status() == 403) {
            throw new IOException("The Claude login was rejected. Start a new Claude sign-in from Settings.");
        }
        Http.requireSuccess(response.status(), label);
        try {
            Map<String, Object> json = Json.object(Json.parse(response.body()));
            String access = Json.string(json, "access_token");
            String refresh = Json.string(json, "refresh_token");
            Double seconds = Json.number(json, "expires_in");
            if (seconds == null || seconds.doubleValue() <= 0) throw new IllegalArgumentException();
            return new Tokens(access, refresh,
                System.currentTimeMillis() + (long) (seconds.doubleValue() * 1000));
        } catch (RuntimeException e) {
            throw new IOException("The Claude login endpoint returned something unreadable");
        }
    }

    private static final class Tokens {
        final String access;
        final String refresh;
        final long expiresAt;

        Tokens(String access, String refresh, long expiresAt) {
            this.access = access;
            this.refresh = refresh;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Parsed {
        String code;
        String state;
    }

    private static Parsed parse(String input) {
        Parsed result = new Parsed();
        String value = input == null ? "" : input.trim();
        int question = value.indexOf('?');
        if (question >= 0) readQuery(value.substring(question + 1), result);
        else if (value.contains("code=")) readQuery(value, result);
        else if (value.contains("#")) {
            String[] halves = value.split("#", 2);
            result.code = decode(halves[0]);
            result.state = decode(halves[1]);
        } else result.code = value;
        return result;
    }

    private static void readQuery(String query, Parsed result) {
        int fragment = query.indexOf('#');
        if (fragment >= 0) query = query.substring(0, fragment);
        for (String pair : query.split("&")) {
            String[] halves = pair.split("=", 2);
            String key = decode(halves[0]);
            String value = halves.length == 1 ? "" : decode(halves[1]);
            if ("code".equals(key)) result.code = value;
            else if ("state".equals(key)) result.state = value;
        }
    }

    private static boolean empty(String value) {
        return value == null || value.trim().length() == 0;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
