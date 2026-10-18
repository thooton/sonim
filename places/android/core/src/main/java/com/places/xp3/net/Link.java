package com.places.xp3.net;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Google Maps link, as someone sends one in a text message, read for what
 * it points at: a place's id, a name or address to search for, a point, or
 * some of each.
 *
 * The phone's own Maps is disabled, so the links are offered to Places
 * instead. The shapes read are the ones Maps shares and the ones its URL
 * API documents:
 *
 * - google.com/maps/place/Name/@lat,lng,17z/data=...!3dlat!4dlng...
 * - google.com/maps/search/?api=1&query=...&query_place_id=ChIJ...
 * - google.com/maps/dir/.../Destination (the last stop)
 * - google.com/maps/@lat,lng,15z, and maps.google.com/?q=... or ?ll=...
 * - geo:lat,lng?q=...
 *
 * A short link (maps.app.goo.gl/..., goo.gl/maps/...) says nothing until it
 * is followed; {@link #isShort} says so and {@link Places#expand} follows it.
 */
public final class Link {

    private static final Pattern AT = Pattern.compile("@(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern PIN = Pattern.compile("!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern PLACE_ID = Pattern.compile("ChIJ[A-Za-z0-9_-]+");

    /** A Places API id, or null. */
    public final String placeId;
    /** A name or address to search for, or null. */
    public final String query;
    /** The point the link is at, or null: where to search near. */
    public final Anchor near;

    Link(String placeId, String query, Anchor near) {
        this.placeId = placeId;
        this.query = query;
        this.near = near;
    }

    /** Whether the link is a short one, which must be followed to be read. */
    public static boolean isShort(String link) {
        URI uri = uri(link);
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(Locale.US);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return host.equals("maps.app.goo.gl") || (host.equals("goo.gl") && path.startsWith("/maps"));
    }

    /** Whether a link is on one of Google's own hosts, so it is safe to follow a redirect to it. */
    static boolean isGoogle(URI uri) {
        if (uri == null || uri.getHost() == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost().toLowerCase(Locale.US);
        return host.equals("goo.gl") || host.endsWith(".goo.gl") || host.matches("([a-z0-9-]+\\.)*google(\\.com?)?(\\.[a-z]{2})?");
    }

    /** What a full link points at; null when it points at nothing Places can look up. */
    public static Link parse(String link) {
        URI uri = uri(link);
        if (uri == null || uri.getScheme() == null) return null;
        if (uri.getScheme().equalsIgnoreCase("geo")) return geo(uri);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String id = null;
        String query = null;
        String coordinates = null;

        String q = param(uri, "query_place_id");
        if (q != null && PLACE_ID.matcher(q).matches()) id = q;
        for (String name : new String[] {"q", "query", "daddr", "destination"}) {
            String value = param(uri, name);
            if (value != null && value.trim().length() > 0 && query == null) query = value.trim();
        }
        if (query != null && query.startsWith("place_id:")) {
            String named = query.substring("place_id:".length()).trim();
            if (PLACE_ID.matcher(named).matches()) id = named;
            query = null;
        }
        String[] segments = path.split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            String segment = segments[i];
            String next = decode(segments[i + 1]);
            if (next.length() == 0 || next.startsWith("@") || next.startsWith("data=")) continue;
            if (query == null && (segment.equals("place") || segment.equals("search"))) query = next;
        }
        if (query == null && path.contains("/dir/")) {
            // The stops follow /dir/; the last one that is not the map's
            // view or its data is where the directions are to.
            String after = path.substring(path.indexOf("/dir/") + 5);
            for (String stop : after.split("/")) {
                String decoded = decode(stop);
                if (decoded.length() > 0 && !decoded.startsWith("@") && !decoded.startsWith("data=")) query = decoded;
            }
        }
        Matcher pin = PIN.matcher(path);
        Matcher at = AT.matcher(path);
        if (pin.find()) coordinates = pin.group(1) + "," + pin.group(2);
        else if (at.find()) coordinates = at.group(1) + "," + at.group(2);
        else if (param(uri, "ll") != null) coordinates = param(uri, "ll");

        // A query that is itself a point is better as where to search near.
        Anchor near = coordinates == null ? null : Anchor.coordinates(coordinates);
        if (query != null && Anchor.coordinates(query) != null) {
            if (near == null) near = Anchor.coordinates(query);
            query = null;
        }
        if (id == null && query == null && near == null) return null;
        return new Link(id, query, near);
    }

    /** geo:lat,lng, geo:lat,lng?q=name, and geo:0,0?q=address (no point at all). */
    private static Link geo(URI uri) {
        String part = uri.getRawSchemeSpecificPart();
        int mark = part.indexOf('?');
        String point = decode(mark < 0 ? part : part.substring(0, mark));
        int semicolon = point.indexOf(';');
        if (semicolon >= 0) point = point.substring(0, semicolon);
        Anchor near = Anchor.coordinates(point);
        if (near != null && near.lat == 0 && near.lng == 0) near = null;
        String query = null;
        if (mark >= 0) {
            for (String pair : part.substring(mark + 1).split("&")) {
                if (pair.startsWith("q=")) query = decode(pair.substring(2)).trim();
            }
        }
        if (query != null) {
            // "lat,lng(Label)" names a point; the label is what to look for.
            Matcher labelled = Pattern.compile("^(-?[\\d.]+\\s*,\\s*-?[\\d.]+)\\s*\\((.*)\\)$").matcher(query);
            if (labelled.matches()) {
                if (near == null) near = Anchor.coordinates(labelled.group(1));
                query = labelled.group(2).trim();
            } else if (Anchor.coordinates(query) != null) {
                if (near == null) near = Anchor.coordinates(query);
                query = null;
            }
            if (query != null && query.length() == 0) query = null;
        }
        if (query == null && near == null) return null;
        return new Link(null, query, near);
    }

    private static URI uri(String link) {
        if (link == null) return null;
        try {
            return new URI(link.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** A query parameter, decoded; null when it is absent. */
    private static String param(URI uri, String name) {
        String raw = uri.getRawQuery();
        if (raw == null) return null;
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            if (key.equals(name)) return equals < 0 ? "" : decode(pair.substring(equals + 1));
        }
        return null;
    }

    /** A path segment or parameter as a person would write it: "+" is a space. */
    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, "UTF-8").trim();
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return raw.trim();
        }
    }
}
