package com.weather.xp3;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Somewhere to show the weather for: a name, what tells it apart, a point. */
public final class Place {

    /** "Portland, OR": what the sky shows. */
    public final String name;
    /** "Oregon, United States": what tells it from the other Portlands. */
    public final String detail;
    public final double lat, lon;

    public Place(String name, String detail, double lat, double lon) {
        this.name = name;
        this.detail = detail;
        this.lat = lat;
        this.lon = lon;
    }

    public boolean same(Place other) {
        return other != null && Math.abs(lat - other.lat) < 1e-3 && Math.abs(lon - other.lon) < 1e-3;
    }

    public Map<String, Object> toJson() {
        return Json.map("name", name, "detail", detail, "lat", lat, "lon", lon);
    }

    public static Place fromJson(Object value) {
        try {
            Map<String, Object> o = Json.object(value);
            Double lat = Json.number(o, "lat"), lon = Json.number(o, "lon");
            String name = Json.text(o, "name");
            String detail = Json.text(o, "detail");
            if (lat == null || lon == null || name == null || name.isEmpty()) return null;
            return new Place(name, detail == null ? "" : detail, lat, lon);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Place fromJsonString(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return fromJson(Json.parse(json));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String toJsonString(Place p) {
        return Json.write(p.toJson());
    }

    /** A saved list, newest first; anything unreadable is skipped. */
    public static List<Place> list(String json) {
        List<Place> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            for (Object o : Json.array(Json.parse(json))) {
                Place p = fromJson(o);
                if (p != null) out.add(p);
            }
        } catch (RuntimeException e) {
            // A damaged list is an empty one.
        }
        return out;
    }

    /** `recent` with `p` put first, at most `max` long. */
    public static List<Place> remember(List<Place> recent, Place p, int max) {
        List<Place> out = new ArrayList<>();
        out.add(p);
        for (Place q : recent) {
            if (out.size() >= max) break;
            if (!q.same(p)) out.add(q);
        }
        return out;
    }

    public static String write(List<Place> places) {
        List<Object> items = new ArrayList<>();
        for (Place p : places) items.add(p.toJson());
        return Json.write(items);
    }
}
