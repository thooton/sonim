package com.teslacharge.xp3.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

public final class Jsons {
    private Jsons() {}

    public static JSONObject object(JSONObject root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof JSONObject)) return null;
            current = ((JSONObject) current).opt(key);
            if (current == null || current == JSONObject.NULL) return null;
        }
        return current instanceof JSONObject ? (JSONObject) current : null;
    }

    public static JSONArray array(JSONObject root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof JSONObject)) return new JSONArray();
            current = ((JSONObject) current).opt(key);
            if (current == null || current == JSONObject.NULL) return new JSONArray();
        }
        return current instanceof JSONArray ? (JSONArray) current : new JSONArray();
    }

    public static String string(JSONObject object, String key) {
        if (object == null || object.isNull(key)) return null;
        String value = object.optString(key, null);
        return value == null || value.length() == 0 ? null : value;
    }

    public static Double number(JSONObject object, String key) {
        if (object == null || object.isNull(key)) return null;
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.valueOf(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    public static Long longNumber(JSONObject object, String key) {
        Double value = number(object, key);
        return value == null ? null : value.longValue();
    }

    public static void put(JSONObject object, String key, Object value) throws JSONException {
        object.put(key, value == null ? JSONObject.NULL : value);
    }

    public static JSONObject copy(JSONObject source) throws JSONException {
        JSONObject result = new JSONObject();
        if (source == null) return result;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, source.get(key));
        }
        return result;
    }
}
