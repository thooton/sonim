package com.places.xp3.net;

import com.xp3.common.Http;
import com.xp3.common.Json;
import com.xp3.common.Login;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Finds places with the Google Places API (New), as `delta maps` does
 * (cmd/delta/maps.go in thooton/delta).
 *
 * The API key lives on the command center (GET /maps-key, with the delta
 * token). It is fetched the first time it is needed and kept in memory only,
 * never on the phone's storage; Google is then called directly, so the
 * command center never sees a search.
 *
 * Field masks decide what Google bills, and these are the CLI's: a search asks
 * for nothing past the Enterprise tier (ratings and opening hours need it), a
 * place's details add reviews and the other Atmosphere fields, and looking up
 * where to search near asks only for Pro fields. A search also asks for the
 * short address, for the results list; it is a Pro field, so the search still
 * bills as Enterprise.
 */
public final class Places {

    public static final String GOOGLE = "https://places.googleapis.com/v1";
    /** Text Search's most per request. */
    public static final int PAGE_SIZE = 20;
    /** How hard results are pulled toward the anchor: a preference, not a limit. */
    static final int BIAS_METERS = 5000;

    static final String LOCATE_FIELDS = "places.id,places.displayName,places.formattedAddress,places.location";

    static final String SEARCH_FIELDS = "places.id,places.displayName,places.primaryTypeDisplayName,"
        + "places.formattedAddress,places.shortFormattedAddress,places.location,places.rating,places.userRatingCount,places.priceLevel,"
        + "places.businessStatus,places.utcOffsetMinutes,places.currentOpeningHours.openNow,"
        + "places.currentOpeningHours.nextOpenTime,places.currentOpeningHours.nextCloseTime,nextPageToken";

    static final String DETAIL_FIELDS;

    static {
        StringBuilder fields = new StringBuilder("id,displayName,primaryTypeDisplayName,formattedAddress,location,"
            + "nationalPhoneNumber,internationalPhoneNumber,websiteUri,rating,userRatingCount,priceLevel,priceRange,"
            + "businessStatus,utcOffsetMinutes,currentOpeningHours,regularOpeningHours,currentSecondaryOpeningHours,"
            + "editorialSummary,generativeSummary.overview,reviewSummary.text,reviews,paymentOptions,parkingOptions,"
            + "accessibilityOptions,fuelOptions");
        for (String[] feature : Place.FEATURES) fields.append(',').append(feature[0]);
        DETAIL_FIELDS = fields.toString();
    }

    /** One page of results, and what is needed to ask for the next. */
    public static final class Page {
        public final String query;
        public final Anchor near;
        public final List<Place> places;
        /** Null when Google has no more. */
        public final String next;

        Page(String query, Anchor near, List<Place> places, String next) {
            this.query = query;
            this.near = near;
            this.places = Collections.unmodifiableList(places);
            this.next = next;
        }
    }

    private final Http http = new Http();
    private final String center;
    private final String token;
    private final String google;
    private volatile String key;

    public Places(Login login) {
        this(login.server(), login.token(), GOOGLE);
    }

    /** Any command center and any Places endpoint, which is how the tests use it. */
    public Places(String center, String token, String google) {
        if (center == null || token == null) throw new IllegalArgumentException("There is no command center login");
        this.center = center;
        this.token = token;
        this.google = google;
    }

    /** The point to search near: Google's best match for what was typed. */
    public Anchor locate(String typed) throws IOException, InterruptedException {
        Anchor coordinates = Anchor.coordinates(typed);
        if (coordinates != null) return coordinates;
        Map<String, Object> body = Json.map("textQuery", typed.trim(), "pageSize", Integer.valueOf(1));
        List<Object> found = Json.children(google("/places:searchText", LOCATE_FIELDS, Json.write(body)), "places");
        if (found.isEmpty() || !(found.get(0) instanceof Map)) {
            throw new IOException("Couldn't find \"" + typed.trim() + "\". Try adding a city or zip code.");
        }
        Place place = new Place(Json.object(found.get(0)));
        if (!place.located()) throw new IOException("\"" + typed.trim() + "\" has no location.");
        // "Tempe" is better shown as its address, "Tempe, AZ, USA"; a business
        // as its name and address.
        String label = place.address().startsWith(place.name()) ? place.address()
            : place.address().length() == 0 ? place.name() : place.name() + ", " + place.address();
        return new Anchor(typed.trim(), label, place.lat(), place.lng());
    }

    /** A Text Search, pulled toward `near` when there is one; `after` is a page's `next`, or null. */
    public Page search(String query, Anchor near, String after) throws IOException, InterruptedException {
        Map<String, Object> body = Json.map("textQuery", query.trim(), "pageSize", Integer.valueOf(PAGE_SIZE));
        if (near != null) {
            Map<String, Object> point = Json.map("latitude", Double.valueOf(near.lat), "longitude", Double.valueOf(near.lng));
            body.put("locationBias", Json.map("circle", Json.map("center", point, "radius", Integer.valueOf(BIAS_METERS))));
        }
        if (after != null) body.put("pageToken", after);
        Map<String, Object> result = google("/places:searchText", SEARCH_FIELDS, Json.write(body));
        List<Place> places = new ArrayList<Place>();
        for (Object item : Json.children(result, "places")) {
            if (item instanceof Map) places.add(new Place(Json.object(item)));
        }
        String next = Json.text(result, "nextPageToken");
        return new Page(query.trim(), near, places, next == null || next.length() == 0 ? null : next);
    }

    /** Everything about one place. */
    public Place details(String id) throws IOException, InterruptedException {
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("That is not a place.");
        return new Place(google("/places/" + id, DETAIL_FIELDS, null));
    }

    /** The key, from the command center the first time and from memory after. */
    String key() throws IOException, InterruptedException {
        String known = key;
        if (known != null) return known;
        Http.Response response = http.send(Http.Request.to(URI.create(center + "/maps-key"))
            .header("Authorization", "Bearer " + token)
            .timeout(30000));
        if (response.status() == 404) {
            throw new IOException("The command center has no Google Maps key. Set one with `delta maps key set`.");
        }
        Http.requireSuccess(response.status(), "Command center");
        String fetched;
        try {
            fetched = Json.string(Json.object(Json.parse(response.body())), "key").trim();
        } catch (RuntimeException e) {
            throw new IOException("The command center sent an unreadable key.");
        }
        key = fetched;
        return fetched;
    }

    private Map<String, Object> google(String path, String fields, String body) throws IOException, InterruptedException {
        Http.Request request = Http.Request.to(URI.create(google + path))
            .header("X-Goog-Api-Key", key())
            .header("X-Goog-FieldMask", fields)
            .timeout(30000);
        if (body != null) request.post(body).header("Content-Type", "application/json");
        Http.Response response = http.send(request);
        if (response.status() / 100 != 2) {
            // A key Google refuses may have been replaced since: fetch it again next time.
            if (response.status() == 400 || response.status() == 401 || response.status() == 403) key = null;
            throw new IOException("Google Maps: " + problem(response));
        }
        try {
            return Json.object(Json.parse(response.body()));
        } catch (RuntimeException e) {
            throw new IOException("Google Maps sent something unreadable.");
        }
    }

    /** Google's own message, which names the problem ("API key not valid…"), cut to fit a screen. */
    static String problem(Http.Response response) {
        String message = null;
        try {
            message = Json.text(Json.child(Json.object(Json.parse(response.body())), "error"), "message");
        } catch (RuntimeException e) {
            // Not Google's JSON error: say the status instead.
        }
        if (message == null || message.trim().length() == 0) return "HTTP " + response.status();
        message = message.trim();
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }
}
