package com.places.xp3.net;

import com.xp3.common.Json;
import java.util.Locale;
import java.util.Map;

/**
 * Where searches are made near: what the user typed ("Tempe", "85281", "Union
 * Station Denver" or "33.42,-111.94"), and the point Google found for it.
 *
 * It is kept between searches, point and all, so a search near the same
 * place again costs one request rather than two.
 */
public final class Anchor {

    /** What was typed, as typed. */
    public final String typed;
    /** What Google found, for showing: "Tempe, AZ, USA". */
    public final String label;
    public final double lat;
    public final double lng;

    public Anchor(String typed, String label, double lat, double lng) {
        this.typed = typed;
        this.label = label;
        this.lat = lat;
        this.lng = lng;
    }

    /** Whether this is the place for `text`, so it need not be looked up again. */
    public boolean isFor(String text) {
        return typed.equalsIgnoreCase(text.trim());
    }

    /** "33.42, -111.94" typed as such needs no lookup; null for anything else. */
    public static Anchor coordinates(String text) {
        String[] parts = text.trim().split("\\s*,\\s*");
        if (parts.length != 2) return null;
        double lat;
        double lng;
        try {
            lat = Double.parseDouble(parts[0]);
            lng = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(lat) || Double.isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
        String label = String.format(Locale.US, "%.5f, %.5f", lat, lng);
        return new Anchor(text.trim(), label, lat, lng);
    }

    public String toJson() {
        return Json.write(Json.map("typed", typed, "label", label, "lat", Double.valueOf(lat), "lng", Double.valueOf(lng)));
    }

    /** What toJson wrote; null for anything unreadable. */
    public static Anchor parse(String json) {
        if (json == null || json.length() == 0) return null;
        try {
            Map<String, Object> root = Json.object(Json.parse(json));
            Double lat = Json.number(root, "lat");
            Double lng = Json.number(root, "lng");
            if (lat == null || lng == null) return null;
            return new Anchor(Json.string(root, "typed"), Json.string(root, "label"), lat, lng);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The great-circle distance to a point, in meters. */
    public double metersTo(double toLat, double toLng) {
        double r = 6371008.8;
        double rad = Math.PI / 180;
        double dLat = (toLat - lat) * rad;
        double dLng = (toLng - lng) * rad;
        double h = Math.pow(Math.sin(dLat / 2), 2)
            + Math.cos(lat * rad) * Math.cos(toLat * rad) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** "500 ft", "2.4 mi", "31 mi", as `delta maps` wrote them. */
    public static String distance(double meters) {
        double miles = meters / 1609.344;
        if (miles < 0.1) return String.format(Locale.US, "%.0f ft", meters / 0.3048);
        if (miles < 10) return String.format(Locale.US, "%.1f mi", miles);
        return String.format(Locale.US, "%.0f mi", miles);
    }
}
