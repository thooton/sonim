package com.teslacharge.xp3.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class FavoriteStore {
    private final SharedPreferences preferences;

    public FavoriteStore(Context context) {
        preferences = context.getSharedPreferences("favorites", Context.MODE_PRIVATE);
    }

    public List<JSONObject> all() {
        List<JSONObject> result = new ArrayList<>();
        try {
            JSONObject stored = new JSONObject(preferences.getString("sites", "{}"));
            Iterator<String> keys = stored.keys();
            // Favorites are bookmarks, not a cache. Older app versions saved
            // availability and pricing here, which made both values look live
            // long after they had expired. Return only durable site metadata;
            // MainActivity fills the live fields from Tesla on every launch.
            while (keys.hasNext()) result.add(compact(stored.getJSONObject(keys.next())));
        } catch (Exception ignored) { /* Corrupt local favorites become an empty list. */ }
        return result;
    }

    public boolean contains(String id) {
        try { return new JSONObject(preferences.getString("sites", "{}")).has(id); }
        catch (Exception ignored) { return false; }
    }

    public void toggle(JSONObject site) {
        try {
            String id = site.getString("id");
            JSONObject stored = new JSONObject(preferences.getString("sites", "{}"));
            if (stored.has(id)) stored.remove(id); else stored.put(id, compact(site));
            preferences.edit().putString("sites", stored.toString()).apply();
        } catch (Exception ignored) { /* A malformed remote site is not persisted. */ }
    }

    private JSONObject compact(JSONObject site) throws Exception {
        JSONObject result = new JSONObject();
        // Distance belongs to the search origin that happened to find this site;
        // it is not durable bookmark metadata and becomes misleading in the
        // Favorites tab after that search is gone.
        String[] fields = { "id", "name", "subtitle", "address", "addressStreet", "addressLocality", "coordinates", "siteType", "siteTypeLabel", "maxPowerKw", "totalStalls" };
        for (String field : fields) if (site.has(field)) result.put(field, site.get(field));
        return result;
    }
}
