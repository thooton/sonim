package com.teslacharge.xp3.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TeslaApi {
    private static final String BFF = "https://apigateway-charging-bff.tesla.com/api/graphql";
    private static final String OWNERSHIP = "https://akamai-apigateway-charging-ownership.tesla.com/graphql";
    private static final double AVAILABILITY_RADIUS = 13.0;

    private final TokenStore tokens;
    private final Object evsLock = new Object();
    private volatile JSONArray cachedEvs;

    public TeslaApi(TokenStore tokens) {
        this.tokens = tokens;
    }

    private JSONObject gql(String network, String query, JSONObject variables, String operation, String screen) throws ApiException {
        return gql(network, query, variables, operation, screen, true);
    }

    private JSONObject gql(String network, String query, JSONObject variables, String operation, String screen, boolean retry401) throws ApiException {
        try {
            String token = tokens.getAccessToken();
            String requestId = UUID.randomUUID().toString();
            String url = network + "?deviceLanguage=" + encode(Bundled.DEVICE_LANGUAGE)
                + "&deviceCountry=" + encode(Bundled.DEVICE_COUNTRY)
                + "&ttpLocale=" + encode(Bundled.TTP_LOCALE)
                + "&operationName=" + encode(operation)
                + "&screen=" + encode(screen);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("content-type", "application/json");
            headers.put("accept", "*/*");
            headers.put("accept-language", Bundled.DEVICE_LANGUAGE);
            headers.put("cache-control", "no-cache");
            headers.put("authorization", "Bearer " + token);
            headers.put("x-tesla-user-agent", Bundled.TESLA_USER_AGENT);
            headers.put("user-agent", "TeslaV4/4542 CFNetwork/3860.100.1 Darwin/25.0.0");
            headers.put("x-request-id", requestId);
            headers.put("x-txid", requestId);

            JSONObject body = new JSONObject().put("query", query).put("variables", variables).put("operationName", operation);
            HttpJson.Response response = HttpJson.post(url, headers, body);
            if (response.status == 401 && retry401) {
                tokens.refreshRejected(token);
                return gql(network, query, variables, operation, screen, false);
            }
            JSONObject payload = response.object();
            JSONArray errors = payload.optJSONArray("errors");
            if (response.status < 200 || response.status >= 300 || (errors != null && errors.length() > 0)) {
                String message = operation + " failed (" + response.status + ")";
                if (errors != null && errors.length() > 0) message = errors.optJSONObject(0).optString("message", message);
                throw new ApiException(message, response.status == 401 ? 401 : 502);
            }
            JSONObject data = payload.optJSONObject("data");
            return data == null ? new JSONObject() : data;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(operation + " could not be completed.", 500, e);
        }
    }

    public JSONObject geocode(String rawQuery) throws ApiException {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() == 0) throw new ApiException("Enter an address or ZIP code.", 400);
        if (query.matches("^-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?$")) {
            String[] parts = query.split(",");
            try {
                return origin(parts[0].trim() + ", " + parts[1].trim(), Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()), "coordinates");
            } catch (JSONException e) { throw new ApiException("Invalid coordinates.", 400, e); }
        }
        return Bundled.GOOGLE_MAPS_KEY.length() > 0 ? geocodeGoogle(query) : geocodeNominatim(query);
    }

    private JSONObject geocodeGoogle(String query) throws ApiException {
        try {
            String url = "https://maps.googleapis.com/maps/api/geocode/json?key=" + encode(Bundled.GOOGLE_MAPS_KEY)
                + "&address=" + encode(query) + "&components=" + encode("country:" + Bundled.DEVICE_COUNTRY);
            HttpJson.Response response = HttpJson.get(url, null);
            JSONObject payload = response.object();
            String status = payload.optString("status");
            if ("ZERO_RESULTS".equals(status)) throw new ApiException("No location found for \"" + query + "\".", 404);
            if (!"OK".equals(status)) throw new ApiException(payload.optString("error_message", "Google geocoding failed: " + status), 502);
            JSONObject best = payload.getJSONArray("results").getJSONObject(0);
            JSONObject point = best.getJSONObject("geometry").getJSONObject("location");
            return origin(best.optString("formatted_address", query), point.getDouble("lat"), point.getDouble("lng"), "google");
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not look up that location.", 502, e); }
    }

    private JSONObject geocodeNominatim(String query) throws ApiException {
        try {
            String url = "https://nominatim.openstreetmap.org/search?q=" + encode(query) + "&format=jsonv2&limit=1&addressdetails=1";
            if (query.matches("^\\d{5}(?:-\\d{4})?$") && Bundled.DEVICE_COUNTRY.length() > 0) {
                url += "&countrycodes=" + encode(Bundled.DEVICE_COUNTRY.toLowerCase(Locale.US));
            }
            Map<String, String> headers = new HashMap<>();
            String contact = Bundled.GEOCODE_EMAIL.length() > 0 ? " (" + Bundled.GEOCODE_EMAIL + ")" : "";
            headers.put("user-agent", "tesla-charge-xp3/1.0" + contact);
            headers.put("accept", "application/json");
            headers.put("accept-language", Bundled.DEVICE_LANGUAGE);
            HttpJson.Response response = HttpJson.get(url, headers);
            if (response.status < 200 || response.status >= 300) throw new ApiException("Geocoding service returned " + response.status, 502);
            JSONArray results = response.array();
            if (results.length() == 0) throw new ApiException("No location found for \"" + query + "\".", 404);
            JSONObject best = results.getJSONObject(0);
            return origin(best.optString("display_name", query), Double.parseDouble(best.getString("lat")), Double.parseDouble(best.getString("lon")), "nominatim");
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not look up that location.", 502, e); }
    }

    private static JSONObject origin(String label, double lat, double lng, String source) throws JSONException {
        return new JSONObject().put("label", label).put("lat", lat).put("lng", lng).put("source", source);
    }

    public JSONArray getNonTeslaEvs() throws ApiException {
        JSONArray cached = cachedEvs;
        if (cached != null) return cached;
        synchronized (evsLock) {
            if (cachedEvs != null) return cachedEvs;
            JSONObject data = gql(OWNERSHIP, GraphqlQueries.GET_NON_TESLA_EVS, new JSONObject(), "getNonTeslaEvs", "charging_home_screen");
            cachedEvs = Jsons.array(data, "me", "nonTeslaEvs");
            return cachedEvs;
        }
    }

    public JSONArray fetchSites(double lat, double lng, double radiusMiles) throws ApiException {
        if (radiusMiles <= AVAILABILITY_RADIUS) return querySites(lat, lng, radiusMiles);
        JSONArray wide = querySites(lat, lng, radiusMiles);
        JSONArray near = querySites(lat, lng, AVAILABILITY_RADIUS);
        Map<String, JSONObject> live = new HashMap<>();
        for (int i = 0; i < near.length(); i++) {
            JSONObject site = near.optJSONObject(i);
            if (site != null) live.put(site.optString("locationGUID"), site);
        }
        JSONArray merged = new JSONArray();
        for (int i = 0; i < wide.length(); i++) {
            JSONObject site = wide.optJSONObject(i);
            if (site != null) merged.put(live.containsKey(site.optString("locationGUID")) ? live.get(site.optString("locationGUID")) : site);
        }
        return merged;
    }

    /**
     * Every charger in one box, in a single request. Unlike {@link #fetchSites}
     * this skips the second near-range query that exists only to attach live
     * stall counts: a caller mapping past sessions to places wants coverage,
     * not availability.
     */
    public JSONArray fetchSitesInArea(double lat, double lng, double radiusMiles) throws ApiException {
        return querySites(lat, lng, radiusMiles);
    }

    private JSONArray querySites(double lat, double lng, double radiusMiles) throws ApiException {
        try {
            JSONArray filters = new JSONArray();
            filters.put(new JSONObject().put("name", "rate").put("type", "LIST").put("value", new JSONArray().put("1").put("2")));
            JSONObject siteTypes = new JSONObject().put("3rdPartyDestinationChargers", false)
                .put("privateDestinationChargers", false).put("superchargerTeslaOnly", false)
                .put("teslaDestinationChargers", false);
            filters.put(new JSONObject().put("name", "siteType").put("type", "CHECKBOX").put("value", siteTypes));

            if ("NTSLA".equals(Bundled.VEHICLE_MAKE_TYPE)) {
                JSONArray evs;
                try { evs = getNonTeslaEvs(); } catch (ApiException ignored) { evs = new JSONArray(); }
                if (evs.length() > 0) {
                    JSONObject ev = evs.getJSONObject(0);
                    JSONObject value = new JSONObject()
                        .put("additionalProps", new JSONObject().put("chargingAccessibility", ev.optBoolean("hasNacsAdapter") ? "NACS_ONLY" : "ALL_VEHICLES"))
                        .put("value", ev.optString("evUuid"));
                    JSONObject vehicleFilter = new JSONObject().put("name", "myVehicles").put("type", "COMBO").put("value", value);
                    JSONArray withVehicle = new JSONArray().put(vehicleFilter);
                    for (int i = 0; i < filters.length(); i++) withVehicle.put(filters.get(i));
                    filters = withVehicle;
                }
            }

            double latDelta = radiusMiles / 69.0;
            double lngDelta = radiusMiles / (69.0 * Math.max(0.05, Math.cos(Math.toRadians(lat))));
            JSONObject siteFilter = new JSONObject().put("userLocation", latLng(lat, lng))
                .put("northwestCorner", latLng(lat + latDelta, lng - lngDelta))
                .put("southeastCorner", latLng(lat - latDelta, lng + lngDelta))
                .put("filters", filters).put("experience", Bundled.VEHICLE_MAKE_TYPE);
            JSONObject variables = new JSONObject().put("siteFilter", siteFilter).put("vehicleMakeType", Bundled.VEHICLE_MAKE_TYPE);
            JSONObject data = gql(BFF, GraphqlQueries.GET_SITE_LIST, variables, "getSiteList", "charging_nt_site_selection_screen");
            JSONArray all = Jsons.array(data, "chargingNetwork", "siteList");
            JSONArray superchargers = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject site = all.optJSONObject(i);
                if (site != null && "SITE_TYPE_SUPERCHARGER".equals(site.optString("siteType"))) superchargers.put(site);
            }
            return superchargers;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not build the site search.", 500, e); }
    }

    private static JSONObject latLng(double lat, double lng) throws JSONException {
        return new JSONObject().put("latitude", lat).put("longitude", lng);
    }

    public JSONObject fetchSite(String locationGuid) throws ApiException {
        try {
            JSONObject variables = new JSONObject()
                .put("siteId", new JSONObject().put("byLocationGUID", new JSONObject().put("locationGUID", locationGuid).put("programType", "PTSCH")))
                .put("vehicleMakeType", Bundled.VEHICLE_MAKE_TYPE);
            JSONObject data = gql(BFF, GraphqlQueries.GET_SITE_BY_ID, variables, "getSiteById", "supercharging_nt_site_details_screen");
            return Jsons.object(data, "chargingNetwork", "site");
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not load the charger.", 500, e); }
    }

    public JSONObject canStartCharging(long trtId) throws ApiException {
        try {
            JSONObject variables = new JSONObject().put("trtId", trtId).put("programType", "PROGRAM_TYPE_PTSCH")
                .put("deviceLanguage", Bundled.DEVICE_LANGUAGE).put("deviceCountry", Bundled.DEVICE_COUNTRY);
            JSONObject data = gql(OWNERSHIP, GraphqlQueries.PRE_START_CHARGING_VALIDATION, variables, "preStartChargingValidation", "supercharging_nt_site_details_screen");
            JSONObject result = Jsons.object(data, "me", "charging", "canStartCharging");
            return result == null ? new JSONObject() : result;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not validate charging.", 500, e); }
    }

    public JSONObject startSession(String postId, long pricebookId, int occupied, int total) throws ApiException {
        try {
            JSONObject variables = new JSONObject().put("postId", postId).put("programType", "PTSCH")
                .put("pricebookId", pricebookId).put("deviceCountry", Bundled.DEVICE_COUNTRY)
                .put("deviceLanguage", Bundled.DEVICE_LANGUAGE).put("occupiedChargers", occupied)
                .put("totalChargers", total).put("source", "SUPERCHARGER");
            JSONObject data = gql(OWNERSHIP, GraphqlQueries.START_CHARGING_SESSION, variables, "StartChargingSession", "supercharging_nt_site_details_screen");
            return Jsons.object(data, "charging", "startSession");
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not start charging.", 500, e); }
    }

    public JSONObject stopSession(String id) throws ApiException {
        JSONObject data = gql(OWNERSHIP, GraphqlQueries.STOP_CHARGING_SESSION, variable("authorizationSessionId", id), "StopChargingSessionById", "charging_session_screen");
        JSONObject result = Jsons.object(data, "charging", "stopSession");
        return result == null ? new JSONObject() : result;
    }

    public JSONObject sessionInfo(String id) throws ApiException {
        JSONObject data = gql(OWNERSHIP, GraphqlQueries.GET_CHARGING_SESSION_BY_ID, variable("authorizationSessionId", id), "GetChargingSessionById", "charging_session_screen");
        return Jsons.object(data, "charging", "sessionInfo");
    }

    public JSONObject sessionSummary(String id) throws ApiException {
        JSONObject data = gql(OWNERSHIP, GraphqlQueries.GET_CHARGING_SESSION_SUMMARY, variable("authorizationSessionId", id), "GetChargingSessionSummary", "charging_session_screen");
        return Jsons.object(data, "charging", "sessionSummary");
    }

    public JSONArray activeSessions() throws ApiException {
        JSONObject data = gql(OWNERSHIP, GraphqlQueries.GET_ACTIVE_CHARGING_SESSIONS, new JSONObject(), "GetActiveChargingSessions", "product_home_screen");
        Object value = Jsons.object(data, "charging") == null ? null : Jsons.object(data, "charging").opt("activeSessions");
        if (value instanceof JSONArray) return (JSONArray) value;
        return value instanceof JSONObject ? new JSONArray().put(value) : new JSONArray();
    }

    /**
     * One page of past sessions, newest first. Pages are one-based, and the
     * returned object carries Tesla's own {@code hasMoreData} alongside the
     * rows, which is the only way to know whether asking for another is worth
     * a request.
     */
    public JSONObject chargingHistory(int pageNumber) throws ApiException {
        try {
            JSONObject variables = new JSONObject().put("pageNumber", pageNumber)
                .put("sortBy", "start_datetime").put("sortOrder", "DESC");
            JSONObject data = gql(OWNERSHIP, GraphqlQueries.GET_CHARGING_HISTORY, variables, "getChargingHistoryV2", "charging_history_screen");
            JSONObject page = Jsons.object(data, "me", "charging", "historyV2");
            return page == null ? new JSONObject() : page;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not load charging history.", 500, e); }
    }

    public JSONObject paymentInstrument() throws ApiException {
        try {
            JSONObject variables = new JSONObject().put("paymentSource", "SUPERCHARGE")
                .put("countryCode", Bundled.DEVICE_COUNTRY).put("currencyCode", Bundled.CURRENCY_CODE)
                .put("sourceFlow", "NTSLA".equals(Bundled.VEHICLE_MAKE_TYPE) ? "NON_TESLA_CHARGING" : "TESLA_CHARGING");
            JSONObject data = gql(OWNERSHIP, GraphqlQueries.GET_PAYMENT_INSTRUMENT, variables, "GetInstrumentBySource", "charging_session_screen");
            return Jsons.object(data, "me", "payment", "getInstrumentBySource");
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not load payment method.", 500, e); }
    }

    public JSONArray pendingBalances() throws ApiException {
        JSONObject data = gql(OWNERSHIP, GraphqlQueries.GET_PENDING_BALANCES, new JSONObject(), "getPendingBalances", "charging_home_screen");
        return Jsons.array(data, "me", "charging", "balances", "list");
    }

    private static JSONObject variable(String key, Object value) throws ApiException {
        try { return new JSONObject().put(key, value); }
        catch (JSONException e) { throw new ApiException("Could not build the charging request.", 500, e); }
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception impossible) { return ""; }
    }
}
