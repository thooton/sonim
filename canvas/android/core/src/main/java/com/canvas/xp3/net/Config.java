package com.canvas.xp3.net;

import java.net.URI;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the app has been told about where to fetch from and who it is.
 *
 * The desktop program this was ported from kept its credentials in files beside
 * its jar and pointed at them by path. A phone has no such place and no comfortable
 * way to type a path anyway, so the values live inline in one small JSON
 * document in the app's private storage, written by the Settings screen and
 * read back here.
 *
 * A half-filled configuration is a normal state rather than an error. Canvas
 * and the model are configured separately and are useful separately: with the
 * cookie service set the three work lists are live and the Chat tab says it
 * needs a model, which is a better first launch than one screen refusing to do
 * anything until every field is populated.
 */
public final class Config {

    public static final String PROVIDER_API = "api";
    public static final String PROVIDER_CODEX = "codex";
    public static final String PROVIDER_CLAUDE = "claude";

    /** Field names as the Settings screen addresses them. */
    public static final String CANVAS_URL = "canvas.baseUrl";
    public static final String COOKIE_URL = "canvas.cookieService.url";
    public static final String COOKIE_TOKEN = "canvas.cookieService.token";
    public static final String PROVIDER = "openai.provider";
    public static final String MODEL = "openai.model";
    public static final String API_KEY = "openai.apiKey";
    public static final String ACCESS_TOKEN = "openai.accessToken";
    public static final String REFRESH_TOKEN = "openai.refreshToken";
    public static final String ACCOUNT_ID = "openai.accountId";
    public static final String EXPIRES_AT = "openai.expiresAt";
    public static final String TIMEZONE = "timezone";

    private final URI canvasBase;
    private final URI cookieService;
    private final String cookieToken;
    private final String provider;
    private final String model;
    private final String apiKey;
    private final String accessToken;
    private final String refreshToken;
    private final String accountId;
    private final long expiresAt;
    private final String zone;

    private Config(URI canvasBase, URI cookieService, String cookieToken, String provider,
                   String model, String apiKey, String accessToken, String refreshToken,
                   String accountId, long expiresAt, String zone) {
        this.canvasBase = canvasBase;
        this.cookieService = cookieService;
        this.cookieToken = cookieToken;
        this.provider = provider;
        this.model = model;
        this.apiKey = apiKey;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accountId = accountId;
        this.expiresAt = expiresAt;
        this.zone = zone;
    }

    public static Config blank() {
        return new Config(null, null, null, PROVIDER_API, null, null, null, null, null, 0, null);
    }

    public static Config parse(String json) {
        return from(Json.object(Json.parse(json)));
    }

    /**
     * Validates a settings tree into a Config, or throws with a sentence short
     * enough to be the whole of a dialog on a 240px screen.
     *
     * An absent or empty field is "not set". Only a field that is present and
     * malformed is an error, because the only thing that writes this document
     * is the Settings screen, which validates through here before saving.
     */
    public static Config from(Map<String, Object> root) {
        Map<String, Object> canvas = Json.child(root, "canvas");
        Map<String, Object> service = Json.child(canvas, "cookieService");
        Map<String, Object> openai = Json.child(root, "openai");

        URI base = null;
        String baseUrl = value(canvas, "baseUrl");
        if (baseUrl != null) base = origin(baseUrl);

        URI cookies = null;
        String cookieUrl = value(service, "url");
        if (cookieUrl != null) cookies = endpoint(cookieUrl);

        String token = value(service, "token");
        if (token != null && !token.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("The service token must be 32 to 128 letters, digits, - or _");
        }

        String provider = value(openai, "provider");
        if (provider == null) provider = PROVIDER_API;
        if (!PROVIDER_API.equals(provider) && !PROVIDER_CODEX.equals(provider)
            && !PROVIDER_CLAUDE.equals(provider)) {
            throw new IllegalArgumentException("Provider must be api, codex or claude");
        }

        long expiresAt = 0;
        Double storedExpiry = Json.number(openai, "expiresAt");
        if (storedExpiry != null && storedExpiry.doubleValue() > 0) {
            expiresAt = (long) storedExpiry.doubleValue();
        } else {
            String expiryText = value(openai, "expiresAt");
            if (expiryText != null) {
                try {
                    expiresAt = Long.parseLong(expiryText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("The login expiry is not valid");
                }
            }
        }

        String zone = value(root, "timezone");
        if (zone != null) {
            try {
                ZoneId.of(zone);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Not a time zone name, e.g. America/Phoenix");
            }
        }

        return new Config(base, cookies, token, provider, value(openai, "model"),
            credential(openai, "apiKey"), credential(openai, "accessToken"),
            credential(openai, "refreshToken"), credential(openai, "accountId"), expiresAt, zone);
    }

    private static String value(Map<String, Object> source, String key) {
        String found = Json.text(source, key);
        if (found == null) return null;
        String trimmed = found.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    /**
     * A credential goes into an HTTP header, so anything that could not sit in
     * one is refused here rather than at the point where it would split the
     * request in half.
     */
    private static String credential(Map<String, Object> source, String key) {
        String found = value(source, key);
        if (found == null) return null;
        for (int i = 0; i < found.length(); i++) {
            char c = found.charAt(i);
            if (c < 33 || c > 126) {
                throw new IllegalArgumentException("The key must be visible characters with no spaces");
            }
        }
        return found;
    }

    static URI origin(String value) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a web address, e.g. https://canvas.asu.edu");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null
            || !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Must be an https address with nothing after the host, e.g. https://canvas.asu.edu");
        }
        return URI.create(value.trim().replaceAll("/+$", "") + "/");
    }

    static URI endpoint(String value) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a web address");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null || !uri.normalize().equals(uri)
            || uri.getPath() == null || !uri.getPath().endsWith("/cookie")) {
            throw new IllegalArgumentException("Must be an https address ending in /cookie");
        }
        return uri;
    }

    /** The same settings tree, ready to be written back out as JSON. */
    public Map<String, Object> toMap() {
        Map<String, Object> service = new LinkedHashMap<String, Object>();
        put(service, "url", cookieService == null ? null : cookieService.toString());
        put(service, "token", cookieToken);

        Map<String, Object> canvas = new LinkedHashMap<String, Object>();
        put(canvas, "baseUrl", canvasBase == null ? null : trimSlash(canvasBase.toString()));
        if (!service.isEmpty()) canvas.put("cookieService", service);

        Map<String, Object> openai = new LinkedHashMap<String, Object>();
        openai.put("provider", provider);
        put(openai, "model", model);
        put(openai, "apiKey", apiKey);
        put(openai, "accessToken", accessToken);
        put(openai, "refreshToken", refreshToken);
        put(openai, "accountId", accountId);
        if (expiresAt > 0) openai.put("expiresAt", Long.valueOf(expiresAt));

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        if (!canvas.isEmpty()) root.put("canvas", canvas);
        root.put("openai", openai);
        put(root, "timezone", zone);
        return root;
    }

    public String toJson() {
        return Json.write(toMap());
    }

    private static void put(Map<String, Object> into, String key, String value) {
        if (value != null) into.put(key, value);
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * One field changed, validated, and returned as a new configuration.
     *
     * Editing goes through the settings tree rather than through nine setters
     * so that there is exactly one place that decides whether a value is
     * acceptable, and it is the same place that reads the file at startup.
     */
    public Config with(String field, String value) {
        Map<String, Object> root = toMap();
        String[] path = field.split("\\.");
        Map<String, Object> node = root;
        for (int i = 0; i < path.length - 1; i++) {
            Map<String, Object> next = Json.child(node, path[i]);
            node.put(path[i], next);
            node = next;
        }
        String last = path[path.length - 1];
        if (value == null || value.trim().length() == 0) node.remove(last);
        else node.put(last, value.trim());
        return from(root);
    }

    /** What is currently in one field, for the Settings screen to show. */
    public String get(String field) {
        Map<String, Object> node = toMap();
        String[] path = field.split("\\.");
        for (int i = 0; i < path.length - 1; i++) node = Json.child(node, path[i]);
        String found = Json.text(node, path[path.length - 1]);
        return found == null ? "" : found;
    }

    /** Whether the three work lists can be filled from Canvas. */
    public boolean canvasReady() {
        return canvasBase != null && cookieService != null && cookieToken != null;
    }

    /**
     * Whether the Chat tab has a model to talk to.
     *
     * A ChatGPT login needs either a refresh token, which can produce an access
     * token and carries the account id inside it, or an access token and
     * account id already in hand.
     */
    public boolean chatReady() {
        if (!canvasReady() || model == null) return false;
        if (PROVIDER_API.equals(provider)) return apiKey != null;
        if (PROVIDER_CLAUDE.equals(provider)) return refreshToken != null || accessToken != null;
        return refreshToken != null || (accessToken != null && accountId != null);
    }

    public URI canvasBase() {
        return canvasBase;
    }

    public URI cookieService() {
        return cookieService;
    }

    public String cookieToken() {
        return cookieToken;
    }

    public String model() {
        return model;
    }

    public boolean codex() {
        return PROVIDER_CODEX.equals(provider);
    }

    public boolean claude() {
        return PROVIDER_CLAUDE.equals(provider);
    }

    public ZoneId zone() {
        if (zone == null) return ZoneId.systemDefault();
        try {
            return ZoneId.of(zone);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    public String apiKey() {
        return apiKey;
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public String accountId() {
        return accountId;
    }

    public long expiresAt() {
        return expiresAt;
    }

    /**
     * The credentials for a model request.
     *
     * A ChatGPT login gets one that can renew itself and reports the rotated
     * tokens to `rotated` so they can be written down; an API key gets a
     * constant. Config decides which because it is what knows the provider,
     * but it holds neither the network nor the storage.
     */
    public Credentials credentials(Http http, CodexAuth.Rotated rotated) {
        if (!codex()) return new Credentials.ApiKey(apiKey);
        return new CodexAuth(http, accessToken, refreshToken, accountId, rotated);
    }
}
