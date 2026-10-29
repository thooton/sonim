package com.canvas.xp3.net;

import com.xp3.common.Json;
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
 * A half-filled configuration is a normal state rather than an error: the
 * Settings screen fills it in one field at a time.
 */
public final class Config {

    /** Field names as the Settings screen addresses them. */
    public static final String CANVAS_URL = "canvas.baseUrl";
    public static final String COOKIE_URL = "canvas.cookieService.url";
    public static final String COOKIE_TOKEN = "canvas.cookieService.token";
    public static final String TIMEZONE = "timezone";

    private final URI canvasBase;
    private final URI cookieService;
    private final String cookieToken;
    private final String zone;

    private Config(URI canvasBase, URI cookieService, String cookieToken, String zone) {
        this.canvasBase = canvasBase;
        this.cookieService = cookieService;
        this.cookieToken = cookieToken;
        this.zone = zone;
    }

    public static Config blank() {
        return new Config(null, null, null, null);
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
     * Fields this version does not know, such as an older version's model
     * login, are ignored.
     */
    public static Config from(Map<String, Object> root) {
        Map<String, Object> canvas = Json.child(root, "canvas");
        Map<String, Object> service = Json.child(canvas, "cookieService");

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

        String zone = value(root, "timezone");
        if (zone != null) {
            try {
                ZoneId.of(zone);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Not a time zone name, e.g. America/Phoenix");
            }
        }

        return new Config(base, cookies, token, zone);
    }

    private static String value(Map<String, Object> source, String key) {
        String found = Json.text(source, key);
        if (found == null) return null;
        String trimmed = found.trim();
        return trimmed.length() == 0 ? null : trimmed;
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

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        if (!canvas.isEmpty()) root.put("canvas", canvas);
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
     * Editing goes through the settings tree rather than through setters
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

    public URI canvasBase() {
        return canvasBase;
    }

    public URI cookieService() {
        return cookieService;
    }

    public String cookieToken() {
        return cookieToken;
    }
}
