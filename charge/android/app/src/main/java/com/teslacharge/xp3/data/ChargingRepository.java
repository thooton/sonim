package com.teslacharge.xp3.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

public final class ChargingRepository {
    private final TeslaApi api;
    private final TokenStore tokens;
    private final SiteDirectory directory;
    private final SharedPreferences sessionContext;

    private static ChargingRepository instance;

    /**
     * The one repository in the process.
     *
     * Tesla rotates the refresh token on every use, so two TokenStores over the
     * same preferences are two clients racing to spend the same single-use
     * credential: whichever refreshes second presents a token that was just
     * invalidated. The Activity and the charging service both need a
     * repository, so they need the same one. The site directory's per-run cache
     * is shared for free.
     */
    public static synchronized ChargingRepository get(Context context) {
        if (instance == null) {
            instance = new ChargingRepository(context.getApplicationContext());
        }
        return instance;
    }

    private ChargingRepository(Context context) {
        tokens = new TokenStore(context);
        api = new TeslaApi(tokens);
        directory = new SiteDirectory(api);
        sessionContext = context.getSharedPreferences("session_context", Context.MODE_PRIVATE);
    }

    public JSONObject search(String query, double radiusMiles) throws ApiException {
        try {
            JSONObject origin = api.geocode(query);
            JSONArray raw = api.fetchSites(origin.getDouble("lat"), origin.getDouble("lng"), radiusMiles);
            List<JSONObject> normalized = Normalizer.sortedSites(raw, origin);
            JSONArray sites = new JSONArray();
            for (JSONObject site : normalized) sites.put(site);
            return new JSONObject().put("origin", origin).put("radiusMiles", radiusMiles).put("count", sites.length()).put("sites", sites);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not search for chargers.", 500, e); }
    }

    public JSONObject site(String locationGuid) throws ApiException {
        try {
            JSONObject raw = api.fetchSite(locationGuid);
            if (raw == null) throw new ApiException("Charging site not found.", 404);
            return Normalizer.siteDetail(raw);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not load this charger.", 500, e); }
    }

    public JSONObject start(String locationGuid, String chargerId) throws ApiException {
        try {
            JSONObject detail = site(locationGuid); // Always re-read availability and pricebook at start time.
            JSONArray chargers = detail.getJSONArray("chargers");
            JSONObject charger = null;
            for (int i = 0; i < chargers.length(); i++) if (chargerId.equals(chargers.getJSONObject(i).optString("id"))) charger = chargers.getJSONObject(i);
            if (charger == null) throw new ApiException("That stall is not part of this site.", 400);
            if (!charger.optBoolean("available")) throw new ApiException("Stall " + charger.optString("label") + " is not available. Pick another stall.", 409);
            if (detail.isNull("pricebookId")) throw new ApiException("This site has no active price book.", 400);
            JSONObject validation = api.canStartCharging(detail.optLong("trtId"));
            if (!validation.optBoolean("enabled")) {
                JSONArray errors = validation.optJSONArray("errors");
                throw new ApiException(errors != null && errors.length() > 0 ? "Charging could not be authorized: " + errors.optString(0) : "Charging could not be authorized here.", 409);
            }
            int total = detail.optInt("totalStalls", chargers.length());
            int available = detail.optInt("availableStalls", detail.optInt("availableChargers"));
            JSONObject started = api.startSession(chargerId, detail.getLong("pricebookId"), Math.max(0, total - available), total);
            if (started == null || started.optString("authorizationSessionId").length() == 0) {
                throw new ApiException(started == null ? "The charging session could not be started." : started.optString("reason", "The charging session could not be started."), 502);
            }
            JSONObject context = new JSONObject().put("locationGUID", locationGuid)
                .put("siteName", detail.optString("name"))
                .put("siteAddress", detail.optString("address"))
                .put("addressStreet", detail.optString("addressStreet"))
                .put("addressLocality", detail.optString("addressLocality"))
                .put("stallLabel", charger.optString("label"))
                .put("stallPowerKw", charger.optDouble("powerKw"))
                .put("rate", detail.opt("schedule") == JSONObject.NULL ? detail.opt("rate") : detail.opt("schedule"))
                .put("holdAmount", detail.opt("holdAmount"));
            String id = started.getString("authorizationSessionId");
            sessionContext.edit().putString(id, context.toString()).apply();
            JSONObject raw = new JSONObject().put("authorizationSessionId", id).put("sessionEvents", started.optJSONArray("sessionEvents") == null ? new JSONArray() : started.optJSONArray("sessionEvents"));
            context.put("authorizationSessionId", id).put("postId", chargerId);
            return Normalizer.session(raw, context);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not start the session.", 500, e); }
    }

    /**
     * The card a session will be billed to, as a label to show, or null when
     * Tesla has no instrument on file. Not a precondition for charging, since
     * Tesla settles the session either way, so a caller that cannot answer
     * this should carry on without it.
     */
    public String paymentMethod() throws ApiException {
        return Normalizer.paymentMethod(api.paymentInstrument());
    }

    /**
     * The account behind the app, in one call: the card a session will be
     * billed to, what past sessions still owe, and the car the network knows
     * about, plus the currency all of that is priced in.
     *
     * Each part is fetched on its own and reported as it came back, because one
     * failing is no reason to hide the two that loaded: a card that will not
     * load is worth saying so about next to a balance that did. Only a run
     * where nothing at all arrived throws, since that is not three coincidences
     * but a connection or a sign-in.
     */
    public JSONObject account() throws ApiException {
        try {
            JSONObject result = new JSONObject();
            ApiException failure = null;
            int loaded = 0;

            try {
                JSONObject payment = Normalizer.paymentInstrument(api.paymentInstrument());
                if (payment != null) result.put("payment", payment);
                loaded++;
            } catch (ApiException e) {
                failure = e;
                result.put("paymentError", e.getMessage());
            }

            try {
                result.put("balances", Normalizer.balances(api.pendingBalances()));
                loaded++;
            } catch (ApiException e) {
                if (failure == null) failure = e;
                result.put("balancesError", e.getMessage());
            }

            try {
                JSONArray evs = api.getNonTeslaEvs();
                JSONObject vehicle = Normalizer.vehicle(evs.optJSONObject(0));
                if (vehicle != null) result.put("vehicle", vehicle);
                loaded++;
            } catch (ApiException e) {
                if (failure == null) failure = e;
                result.put("vehicleError", e.getMessage());
            }

            if (loaded == 0) throw failure;

            // When the credentials this build carries stop working. It is the
            // one fact on this screen that needs no network and the only
            // warning there is: the token cannot be renewed from the phone, so
            // a lapse is a rebuild and a sideload, and finding that out at a
            // stall is finding it out too late.
            long signInExpiry = tokens.getRefreshExpiresAt();
            if (signInExpiry > 0) result.put("signInExpiresAt", signInExpiry);

            // The currency this account is billed in, which is the one Tesla is
            // asked for the payment instrument in. Carried even though the
            // balances name their own, because a zero balance is a list with
            // nothing in it to read a currency off.
            result.put("currency", Bundled.CURRENCY_CODE);
            return result;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Could not load your account.", 500, e);
        }
    }

    public JSONObject activeSession() throws ApiException {
        try {
            JSONArray active = api.activeSessions();
            if (active.length() == 0) return null;
            JSONObject basic = active.getJSONObject(0);
            String id = basic.optString("authorizationSessionId");
            JSONObject info = api.sessionInfo(id);
            return Normalizer.session(info, context(id, basic));
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not check active sessions.", 500, e); }
    }

    public JSONObject session(String id) throws ApiException {
        try { return Normalizer.session(api.sessionInfo(id), context(id, null)); }
        catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not update the charging session.", 500, e); }
    }

    public JSONObject stop(String id) throws ApiException {
        try {
            JSONObject result = api.stopSession(id);
            if (!result.optBoolean("isRequestDelivered")) throw new ApiException("The stop request was not accepted.", 502);
            JSONObject info;
            try { info = api.sessionInfo(id); } catch (ApiException ignored) { info = new JSONObject().put("authorizationSessionId", id); }
            return Normalizer.session(info, context(id, null));
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not stop the session.", 500, e); }
    }

    /**
     * One page of history, newest first, pages counted from one. An empty page
     * ends the history whatever Tesla says about having more: a caller that
     * kept asking on the strength of the flag alone would page forever.
     */
    public HistoryPage history(int pageNumber) throws ApiException {
        try {
            JSONObject page = api.chargingHistory(pageNumber);
            // Nothing else to fetch: Tesla addresses each session itself, and a
            // history row shows nothing that has to be looked up.
            List<JSONObject> sessions = Normalizer.history(Jsons.array(page, "data"));
            return new HistoryPage(sessions, page.optBoolean("hasMoreData") && !sessions.isEmpty());
        }
        catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not load charging history.", 500, e); }
    }

    /**
     * Finds the charger behind one past session and attaches it: the
     * locationGUID its live screen opens by, and the label painted on the stall
     * it used. Neither appears in the history list, so this is not part of
     * loading a page - it is asked for when a session is actually opened.
     *
     * Never throws. A site that cannot be found leaves the session as it was,
     * which is still a complete receipt.
     */
    public void historySite(JSONObject session) {
        directory.resolve(session);
    }

    /** Whether {@link #historySite} would answer without a network round trip,
     *  which is the difference between a receipt that draws now and one that
     *  has to wait. */
    public boolean knowsHistorySite(JSONObject session) {
        return directory.knows(session);
    }

    public JSONObject sessionFees(String id) throws ApiException {
        try { return Normalizer.sessionFees(api.sessionSummary(id)); }
        catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("Could not load the session fees.", 500, e); }
    }

    private JSONObject context(String id, JSONObject fallback) {
        try {
            String stored = sessionContext.getString(id, null);
            if (stored != null) return new JSONObject(stored);
            JSONObject result = fallback == null ? new JSONObject() : Jsons.copy(fallback);
            result.put("authorizationSessionId", id);
            return result;
        } catch (JSONException ignored) { return new JSONObject(); }
    }
}
