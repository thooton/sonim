package com.teslacharge.xp3.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the charger a past session was charged at.
 *
 * History already says where the place is - Tesla sends the street, the city
 * and the site's entry coordinates with every session - so nothing here
 * geocodes or addresses anything. What history does not send is the site's
 * locationGUID, and without one there is no way to open the charger's live
 * screen. So the coordinates are handed straight back to the site list, which
 * answers with the handful of chargers standing at that spot.
 *
 * Which stall a past session used is deliberately not answered here. History
 * identifies the post with a position, a cabinet serial and a DIN; live site
 * detail identifies it with a request-scoped UUID and the painted label. The
 * two payloads share no key, so any label this could produce would be a guess
 * dressed up as a fact.
 *
 * Neither answer appears in the history list, so none of this runs while a
 * page is loading. It runs when a session is opened, for that session's site
 * alone.
 *
 * A match is only accepted when exactly one charger in that handful answers to
 * the session's name. That test used to have to carry a thirty-mile radius and
 * was the weak point of the whole thing; against a two-mile box centred on the
 * site's own coordinates it is close to a formality.
 *
 * Nothing here is written to disk, deliberately. Which charger stands at a set
 * of coordinates is a fact about today, not about the session: a supercharger
 * built since would either invalidate a remembered answer or introduce an
 * ambiguity that should have suppressed it, and a cache would never notice
 * either. Every run asks again.
 */
public final class SiteDirectory {
    /** The site's own entry coordinates are the centre, so this only has to be
     *  wide enough to absorb the difference between where a charger's entrance
     *  is and where the site list thinks its centre is. */
    private static final double SITE_RADIUS_MILES = 2;
    private static final String[] SITE_NAME_FIELDS = { "displayName", "localizedSiteName", "name" };

    /** Session label to the charger found for it, or to a tombstone for the
     *  ones this run has already looked for and not found. */
    private final Map<String, JSONObject> known = new ConcurrentHashMap<>();
    private final TeslaApi api;

    public SiteDirectory(TeslaApi api) {
        this.api = api;
    }

    /** True once this session's site has been looked up, whether or not it was
     *  found. Lets a caller tell an instant {@link #resolve} from one that has
     *  to go to the network before it can draw anything. */
    public boolean knows(JSONObject session) {
        return known.containsKey(session.optString("siteName", ""));
    }

    /**
     * Attaches the locationGUID to one session. Each distinct site is looked up
     * once per run, so the second session opened at a charger already visited
     * costs nothing.
     *
     * Best-effort. A site that cannot be found, or a network that will not
     * answer, costs the session its "open this charger" button; the address it
     * already came with is untouched.
     */
    public void resolve(JSONObject session) {
        String label = session.optString("siteName", "");
        if (label.length() == 0) return;
        JSONObject entry = known.get(label);
        if (entry == null) {
            try {
                entry = lookUp(session);
            } catch (Exception unavailable) {
                // A network failure is a fact about the network, not about the
                // charger, so it is not remembered and opening the session
                // again asks again.
                return;
            }
            known.put(label, entry);
        }
        apply(session, entry);
    }

    /**
     * The charger standing at this session's coordinates, or a tombstone if the
     * site list does not unambiguously name one. Throws only when the lookup
     * could not be made at all.
     */
    private JSONObject lookUp(JSONObject session) throws ApiException, JSONException {
        if (session.isNull("lat") || session.isNull("lng")) return unmatched();
        JSONArray nearby = api.fetchSitesInArea(session.getDouble("lat"), session.getDouble("lng"), SITE_RADIUS_MILES);
        JSONObject match = byName(nearby).get(key(session.optString("siteName")));
        if (match == null) return unmatched();
        String locationGuid = match.optString("locationGUID");
        if (locationGuid.length() == 0) return unmatched();

        return new JSONObject().put("locationGUID", locationGuid);
    }

    /** Puts the charger found for a session onto it. The address is left alone:
     *  it came down with the session and is already the better answer. */
    private static void apply(JSONObject session, JSONObject entry) {
        try {
            if (entry.has("locationGUID")) session.put("locationGUID", entry.get("locationGUID"));
        } catch (Exception ignored) { /* A session that will not take one keeps what it had. */ }
    }

    /**
     * Chargers indexed by every name they answer to, minus the names that more
     * than one charger answers to. Dropping the collisions here is what makes a
     * hit at lookup time unambiguous by construction.
     */
    private static Map<String, JSONObject> byName(JSONArray sites) {
        Map<String, JSONObject> index = new LinkedHashMap<>();
        Set<String> collisions = new LinkedHashSet<>();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject site = sites.optJSONObject(i);
            if (site == null) continue;
            Set<String> names = new LinkedHashSet<>();
            for (String field : SITE_NAME_FIELDS) {
                String name = Jsons.string(site, field);
                if (name != null) names.add(key(name));
            }
            for (String name : names) {
                JSONObject existing = index.get(name);
                if (existing == null) index.put(name, site);
                else if (!existing.optString("locationGUID").equals(site.optString("locationGUID"))) collisions.add(name);
            }
        }
        for (String name : collisions) index.remove(name);
        return index;
    }

    private static JSONObject unmatched() throws JSONException {
        return new JSONObject().put("unmatched", true);
    }

    /** Case, punctuation and spacing differ between the history label and the
     *  site list's own names often enough to matter; the words do not. */
    static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
