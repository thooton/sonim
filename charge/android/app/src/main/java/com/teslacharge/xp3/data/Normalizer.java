package com.teslacharge.xp3.data;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class Normalizer {

    private static final Pattern FINAL_EVENT = Pattern.compile(
        "DEVICE_SESSION_FINAL|CANCELLED|CANCELED|STOPPED|COMPLETE|ENDED|TERMINATED|FAILED|EXPIRED"
    );
    private static final Pattern STOPPING_EVENT = Pattern.compile("TERMINATING|STOPPING");
    private static final Pattern INITIALIZING_EVENT = Pattern.compile(
        "INITIALIZING_CHARGE|STARTING"
    );
    private static final Pattern CHARGING_EVENT = Pattern.compile(
        "CHARGING_STARTED|CHARGING_IN_PROGRESS|SESSION_STARTED"
    );

    private Normalizer() {}

    public static JSONObject siteListItem(JSONObject site)
        throws JSONException {
        JSONObject result = new JSONObject();
        Jsons.put(result, "id", Jsons.string(site, "locationGUID"));
        Jsons.put(result, "trtId", Jsons.longNumber(site, "trtId"));
        String name = first(
            Jsons.string(site, "displayName"),
            Jsons.string(site, "localizedSiteName"),
            Jsons.string(site, "name")
        );
        Jsons.put(result, "name", name);
        Jsons.put(result, "subtitle", Jsons.string(site, "displaySubTitle"));
        Jsons.put(result, "address", formatAddress(site.optJSONObject("address")));
        // Split out as well as joined: a list row leads with the street and
        // demotes the city line, while the detail screen wants the whole thing.
        Jsons.put(result, "addressStreet", streetLine(site.optJSONObject("address")));
        Jsons.put(result, "addressLocality", localityLine(site.optJSONObject("address")));
        Jsons.put(result, "postalCode", Jsons.string(site.optJSONObject("address"), "postalCode"));
        Jsons.put(result, "coordinates", site.optJSONObject("centroid"));
        Jsons.put(result, "distanceMiles", Jsons.number(site, "haversineDistanceMiles"));
        String siteType = Jsons.string(site, "siteType");
        Jsons.put(result, "siteType", siteType);
        result.put(
            "siteTypeLabel",
            "SITE_TYPE_SUPERCHARGER".equals(siteType)
                ? "Charger"
                : titleCase(siteType == null ? "" : siteType.replace("SITE_TYPE_", ""))
        );
        Jsons.put(result, "powerType", Jsons.string(site, "powerType"));
        Jsons.put(result, "maxPowerKw", Jsons.number(site, "maxPowerKw"));
        Jsons.put(result, "totalStalls", Jsons.longNumber(site, "totalStalls"));
        Jsons.put(result, "availableStalls", Jsons.longNumber(site, "availableStalls"));
        Jsons.put(result, "rate", chargingRate(site.optJSONObject("pricing")));
        JSONArray amenities = new JSONArray();
        JSONArray rawAmenities = site.optJSONArray("amenities");
        if (rawAmenities != null) for (int i = 0; i < rawAmenities.length(); i++) amenities.put(
            titleCase(
                rawAmenities.optString(i).replace("AMENITIES_", "")
                    .replace("TWENTY_FOUR_HOUR", "OPEN_24_HOURS")
            )
        );
        result.put("amenities", amenities);
        Jsons.put(
            result,
            "openToNonTeslas",
            site.has("openToNonTeslas")
                ? site.optBoolean("openToNonTeslas")
                : null
        );
        result.put("hasHighCongestion", site.optBoolean("hasHighCongestion"));
        Jsons.put(
            result,
            "waitEstimate",
            labelWithoutPrefix(Jsons.string(site, "waitEstimateBucket"), "WAIT_ESTIMATE_BUCKET_")
        );
        Jsons.put(result, "outage", Jsons.string(site, "activeOutageMessage"));
        Jsons.put(result, "timeZone", Jsons.string(site, "timeZone"));
        return result;
    }

    public static JSONObject siteDetail(JSONObject site) throws JSONException {
        JSONObject result = siteListItem(site);
        JSONObject activePricebook = Jsons.object(site, "pricing", "userRates", "activePricebook");
        Jsons.put(result, "pricebookId", Jsons.longNumber(activePricebook, "priceBookID"));
        Jsons.put(result, "schedule", schedule(site));
        JSONArray chargers = new JSONArray();
        JSONArray raw = site.optJSONArray("chargerList");
        List<JSONObject> sorted = new ArrayList<>();
        if (raw != null) for (int i = 0; i < raw.length(); i++) if (
            raw.optJSONObject(i) != null
        ) sorted.add(raw.optJSONObject(i));
        Collections.sort(
            sorted,
            (a, b) -> {
                return naturalLabel(a.optString("label"), b.optString("label"));
            }
        );
        int available = 0;
        for (JSONObject charger : sorted) {
            String state = charger.optString("availability");
            boolean isAvailable = "CHARGER_AVAILABILITY_AVAILABLE".equals(state);
            if (isAvailable) available++;
            chargers.put(
                new JSONObject().put("id", charger.optString("id"))
                    .put("connectorId", charger.optString("connectorId"))
                    .put("label", charger.optString("label")).put(
                        "powerKw",
                        charger.has("powerKw")
                            ? charger.optDouble("powerKw")
                            : JSONObject.NULL
                    ).put("standard", charger.optString("standard")).put("availability", state)
                    .put("available", isAvailable).put(
                        "availabilityLabel",
                        titleCase(state.replace("CHARGER_AVAILABILITY_", ""))
                    )
            );
        }
        result.put("chargers", chargers).put("availableChargers", available);

        JSONArray extras = new JSONArray();
        addExtraRate(
            extras,
            activePricebook == null
                ? null
                : activePricebook.optJSONObject("congestion"),
            "Congestion fee"
        );
        addExtraRate(
            extras,
            activePricebook == null
                ? null
                : activePricebook.optJSONObject("parking"),
            "Idle / parking fee"
        );
        addExtraRate(
            extras,
            activePricebook == null
                ? null
                : activePricebook.optJSONObject("duration"),
            "Duration fee"
        );
        addExtraRate(
            extras,
            activePricebook == null
                ? null
                : activePricebook.optJSONObject("flat"),
            "Session fee"
        );
        result.put("extraRates", extras);
        Jsons.put(result, "holdAmount", priceObject(site.optJSONObject("holdAmount")));
        Jsons.put(result, "currentCongestion", Jsons.number(site, "currentCongestion"));
        Jsons.put(result, "accessRestriction", Jsons.string(site, "accessRestriction"));
        Jsons.put(result, "accessInstruction", Jsons.string(site, "accessInstruction"));
        Jsons.put(
            result,
            "additionalNavInstructions",
            Jsons.string(site, "additionalNavInstructions")
        );
        Jsons.put(result, "criticalInfo", Jsons.string(site, "criticalInfo"));
        result.put("payToPark", site.optBoolean("payToPark"));
        Jsons.put(result, "payToParkInstructions", Jsons.string(site, "payToParkInstructions"));
        Jsons.put(result, "parkingLevel", Jsons.string(site, "parkingLevel"));
        result.put("valetOnly", site.optBoolean("valetOnly"));
        Jsons.put(result, "owner", Jsons.string(site, "owner"));
        Jsons.put(result, "operator", Jsons.string(site, "operator"));
        Jsons.put(result, "entryPoint", site.optJSONObject("entryPoint"));
        return result;
    }

    public static JSONObject session(JSONObject info, JSONObject extra)
        throws JSONException {
        if (info == null) info = new JSONObject();
        JSONArray events = info.optJSONArray("sessionEvents");
        if (events == null) events = new JSONArray();
        double powerW = nestedNumber(info, "instantaneousPowerW", "value", 0);
        double energy = number(info, "cumulativeEnergy", 0);
        boolean isFinal = eventMatches(events, FINAL_EVENT);
        String endReason = info.optString(
            "authorizationEndReason",
            "AUTHORIZATION_END_REASON_INVALID"
        );
        boolean authEnded =
            info.optJSONObject("chargeEndTimeEpoch") != null ||
            !"AUTHORIZATION_END_REASON_INVALID".equals(endReason);
        boolean stopping =
            !isFinal && (authEnded || eventMatches(events, STOPPING_EVENT));
        boolean everCharged =
            energy > 0 || info.optBoolean("hasCumulativeEnergy");
        boolean awaitingUnplug = stopping && powerW == 0 && everCharged;
        boolean cancelled =
            isFinal &&
            energy == 0 &&
            (!info.optBoolean("hasCumulativeEnergy", true) ||
                eventContains(events, "CANCELLED") ||
                eventContains(events, "CANCELED"));

        String phase;
        if (isFinal) phase = cancelled ? "cancelled" : "ended";
        else if (stopping) phase = "stopping";
        else if (
            powerW > 0 || energy > 0 || eventMatches(events, CHARGING_EVENT)
        ) phase = "charging";
        else if (eventMatches(events, INITIALIZING_EVENT)) phase =
            "initializing";
        else if (eventContains(events, "WAITING_FOR_PLUG_IN")) phase =
            "waiting_for_plug_in";
        else phase = "authorizing";

        String status;
        if ("authorizing".equals(phase)) status = "Authorizing at the stall…";
        else if ("waiting_for_plug_in".equals(phase)) status =
            "Ready, waiting for plug in";
        else if ("initializing".equals(phase)) status =
            "Plugged in, starting up…";
        else if ("charging".equals(phase)) status = "Charging";
        else if ("stopping".equals(phase)) status = awaitingUnplug
            ? "Unplug to finish"
            : "Ending session…";
        else if ("cancelled".equals(phase)) status = "Session cancelled";
        else status = "Session complete";

        JSONObject result =
            extra == null ? new JSONObject() : Jsons.copy(extra);
        String id = first(
            info.optString("authorizationSessionId", null),
            extra == null
                ? null
                : extra.optString("authorizationSessionId", null)
        );
        Jsons.put(result, "authorizationSessionId", id);
        result.put("phase", phase).put("statusText", status).put(
                "pending",
                "authorizing".equals(phase) ||
                    "waiting_for_plug_in".equals(phase)
            ).put("active", !isFinal).put(
                "charging",
                "charging".equals(phase) || "initializing".equals(phase)
            ).put("cancellable", !isFinal && !stopping).put("awaitingUnplug", awaitingUnplug)
            .put("summaryReady", isFinal).put("events", events).put("energyKwh", energy)
            .put("hasEnergyReading", info.optBoolean("hasCumulativeEnergy"))
            .put("cost", number(info, "cumulativeCost", 0)).put("powerKw", powerW / 1000.0);
        Jsons.put(result, "currency", Jsons.string(info, "currency"));
        Jsons.put(result, "voltageV", nestedNullableNumber(info, "voltageV", "value"));
        Jsons.put(result, "currentA", nestedNullableNumber(info, "currentA", "value"));
        Jsons.put(result, "stateOfCharge", nestedNullableNumber(info, "stateOfCharge", "value"));
        Jsons.put(result, "timeToFullSecs", duration(info.optJSONObject("timeToFull")));
        Jsons.put(result, "timeToBulkSecs", duration(info.optJSONObject("timeToBulk")));
        Jsons.put(result, "startedAt", timestamp(info.optJSONObject("chargeStartTimeEpoch")));
        Jsons.put(result, "endedAt", timestamp(info.optJSONObject("chargeEndTimeEpoch")));
        return result;
    }

    public static JSONObject sessionFees(JSONObject summary)
        throws JSONException {
        if (summary == null) return null;
        JSONObject result = feeTotals(summary.optJSONArray("fees"));
        result.put("chargeSessionId", summary.optString("chargeSessionId"));
        result.put("siteName", summary.optString("siteLocationName"));
        return result;
    }

    /**
     * "Visa ending in 1234" for the card on file, or null when Tesla names no
     * instrument. Wallets and other non-card instruments carry no last four, so
     * the brand stands alone rather than ending in nothing.
     */
    public static String paymentMethod(JSONObject instrument) {
        if (instrument == null) return null;
        String brand = Jsons.string(instrument, "accountType");
        String lastFour = Jsons.string(instrument, "lastFourDigits");
        String label = brand == null ? null : titleCase(brand);
        if (lastFour == null) return label;
        return join(" ending in ", first(label, "Card"), lastFour);
    }

    /**
     * The same card broken into rows rather than a sentence. An account screen
     * has room to name the brand, the digits and the expiry separately, and to
     * say when that expiry has already passed, which is the one thing about a
     * card on file worth finding out before a session rather than during one.
     * The one-line {@link #paymentMethod} label rides along for the dialogs
     * that only have room for that.
     */
    public static JSONObject paymentInstrument(JSONObject instrument)
        throws JSONException {
        if (instrument == null) return null;
        JSONObject result = new JSONObject();
        Jsons.put(result, "label", paymentMethod(instrument));
        Jsons.put(result, "brand", labelOf(Jsons.string(instrument, "accountType")));
        Jsons.put(result, "lastFour", Jsons.string(instrument, "lastFourDigits"));
        Jsons.put(result, "type", labelOf(Jsons.string(instrument, "paymentInstrumentType")));
        Jsons.put(result, "channels", labelList(instrument.optJSONArray("paymentChannels")));
        int month = intOf(instrument.opt("expiryMonth"));
        // Two-digit years are written on the card itself; a card expiring in
        // "28" is not a card from 1928.
        int year = intOf(instrument.opt("expiryYear"));
        if (year > 0 && year < 100) year += 2000;
        if (month > 0 && year > 0) {
            result.put("expiryMonth", month);
            result.put("expiryYear", year);
            result.put("expired", expired(month, year));
        }
        return result;
    }

    /** A card is good through the last day of the month printed on it. */
    private static boolean expired(int month, int year) {
        Calendar now = Calendar.getInstance();
        int thisYear = now.get(Calendar.YEAR);
        int thisMonth = now.get(Calendar.MONTH) + 1;
        return year < thisYear || (year == thisYear && month < thisMonth);
    }

    /**
     * What past sessions still owe, totalled per currency. Tesla lists one
     * balance per unsettled session; the account screen is read for whether
     * anything is owed at all, so the sessions behind a currency are counted
     * rather than itemized.
     */
    public static JSONObject balances(JSONArray list) throws JSONException {
        Map<String, Double> amounts = new LinkedHashMap<>();
        Map<String, Integer> sessions = new LinkedHashMap<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject balance = list.optJSONObject(i);
            if (balance == null) continue;
            double amount = balance.optDouble("amount", 0);
            // Fractions of a cent are rounding, not a debt.
            if (amount <= .005) continue;
            String currency = balance.optString("currencyCode", "USD");
            Double owed = amounts.get(currency);
            amounts.put(currency, (owed == null ? 0 : owed) + amount);
            Integer count = sessions.get(currency);
            sessions.put(currency, (count == null ? 0 : count) + 1);
        }
        JSONArray entries = new JSONArray();
        for (Map.Entry<String, Double> entry : amounts.entrySet()) {
            entries.put(
                new JSONObject().put("currency", entry.getKey()).put("amount", entry.getValue())
                    .put("sessions", sessions.get(entry.getKey()))
            );
        }
        return new JSONObject().put("owed", entries.length() > 0).put("entries", entries);
    }

    /**
     * The car Tesla's network associates with this account. A Tesla account
     * lists none at all, so answering null is ordinary rather than a failure.
     */
    public static JSONObject vehicle(JSONObject ev) throws JSONException {
        if (ev == null) return null;
        JSONObject result = new JSONObject();
        int year = intOf(ev.opt("year"));
        Jsons.put(
            result,
            "name",
            // first() of the joined line rather than the line itself: a car
            // with no year, make or model named is an absent name, not a blank
            // one, and the screen leaves the row out entirely.
            first(
                withoutRepeatedWords(
                    join(
                        " ",
                        year > 0 ? String.valueOf(year) : null,
                        first(Jsons.string(ev, "makeLabel"), labelOf(Jsons.string(ev, "make"))),
                        Jsons.string(ev, "model")
                    )
                )
            )
        );
        Jsons.put(result, "plate", Jsons.string(ev, "licensePlate"));
        result.put("hasNacsAdapter", ev.optBoolean("hasNacsAdapter"));
        double range = ev.optDouble("maxRange", 0);
        if (range > 0) {
            Jsons.put(
                result,
                "range",
                Math.round(range) +
                    " " +
                    rangeUnit(Jsons.string(ev, "maxRangeUnit"))
            );
        }
        return result;
    }

    /**
     * Drops any word that repeats the one before it. Tesla gives the make as
     * "Chevrolet" and the model as "Chevrolet Bolt EV", so joining them names
     * the make twice. Done by comparing neighbours rather than by stripping the
     * make off the front of the model, because the overlap is not always at the
     * join and the API is not consistent about which field carries it.
     */
    private static String withoutRepeatedWords(String value) {
        if (value == null) return null;
        StringBuilder result = new StringBuilder();
        String previous = "";
        for (String word : value.split(" ")) {
            if (word.length() == 0) continue;
            if (!word.equalsIgnoreCase(previous)) {
                if (result.length() > 0) result.append(' ');
                result.append(word);
            }
            previous = word;
        }
        return result.toString();
    }

    /** "MILES" is how the API writes it and not how anyone reads it. */
    private static String rangeUnit(String unit) {
        if (unit == null || unit.length() == 0) return "mi";
        if (unit.toUpperCase(Locale.US).startsWith("MI")) return "mi";
        if (unit.toUpperCase(Locale.US).startsWith("KM")) return "km";
        if (unit.toUpperCase(Locale.US).startsWith("KILO")) return "km";
        return unit.toLowerCase(Locale.US);
    }

    /** Enum-shaped values joined for display: "Credit Card · Apple Pay". */
    private static String labelList(JSONArray values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; values != null && i < values.length(); i++) {
            String label = labelOf(values.optString(i, null));
            if (label == null) continue;
            if (result.length() > 0) result.append(" · ");
            result.append(label);
        }
        return result.length() == 0 ? null : result.toString();
    }

    private static String labelOf(String value) {
        return value == null || value.length() == 0 ? null : titleCase(value);
    }

    /** Tesla sends card expiries as numbers in some responses and as strings in
     *  others, so read whichever arrived. */
    private static int intOf(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null || value == JSONObject.NULL) return 0;
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Past sessions, newest first. Tesla dates these as ISO-8601 with an offset
     * rather than the proto timestamps a live session carries. The itemized
     * total is what was charged. The
     * address of the place comes down with the session, so a row is fully
     * addressed the moment it is normalised.
     */
    public static List<JSONObject> history(JSONArray raw) throws JSONException {
        List<JSONObject> sessions = new ArrayList<>();
        for (int i = 0; raw != null && i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            JSONObject session = feeTotals(item.optJSONArray("fees"));
            session.put("id", item.optString("chargeSessionId"));
            session.put("siteName", first(Jsons.string(item, "siteLocationName"), "Charger"));
            // Tesla addresses the place itself, in the same shape a site list
            // row carries, so a history row and a charger row read their
            // address the one way and neither has to be looked up.
            JSONObject address = item.optJSONObject("siteAddress");
            Jsons.put(session, "address", formatAddress(address));
            Jsons.put(session, "addressStreet", streetLine(address));
            Jsons.put(session, "addressLocality", localityLine(address));
            // The site's own entry point. Not shown anywhere, but it is what
            // finds the charger behind a past session without having to guess
            // from its name.
            JSONObject where = item.optJSONObject("siteEntryLocation");
            Jsons.put(session, "lat", Jsons.number(where, "latitude"));
            Jsons.put(session, "lng", Jsons.number(where, "longitude"));
            Long startedAt = isoTime(Jsons.string(item, "chargeStartDateTime"));
            Long endedAt = isoTime(Jsons.string(item, "chargeStopDateTime"));
            Long unlatchedAt = isoTime(Jsons.string(item, "unlatchDateTime"));
            Jsons.put(session, "startedAt", startedAt);
            Jsons.put(session, "endedAt", endedAt);
            Jsons.put(session, "unlatchedAt", unlatchedAt);
            // How long the charge ran, and how long the car then sat on the
            // stall afterwards. Idle time is billable, so the gap is worth
            // naming even in the sessions that were not billed for it.
            Jsons.put(session, "durationSecs", elapsed(startedAt, endedAt));
            Jsons.put(session, "idleSecs", elapsed(endedAt, unlatchedAt));
            // What the session worked out to per kilowatt-hour with every fee
            // counted: the one figure two sessions compare on.
            double energy = session.optDouble("energyKwh", 0);
            Jsons.put(
                session,
                "pricePerKwh",
                energy > 0 ? session.optDouble("total", 0) / energy : null
            );
            sessions.add(session);
        }
        Collections.sort(sessions, (a, b) -> Long.compare(startedAt(b), startedAt(a)));
        return sessions;
    }

    private static long startedAt(JSONObject session) {
        return session.optLong("startedAt", session.optLong("endedAt", 0));
    }

    /** Seconds between two millisecond stamps, or null unless both are present
     *  and in order. Tesla omits a stop time on sessions it never closed. */
    private static Long elapsed(Long from, Long to) {
        return from == null || to == null || to <= from
            ? null
            : (to - from) / 1000;
    }

    private static Long isoTime(String value) {
        if (value == null) return null;
        String[] patterns = {
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                if (pattern.endsWith("'Z'")) format.setTimeZone(TimeZone.getTimeZone("UTC"));
                return format.parse(value).getTime();
            } catch (ParseException ignored) {
                /* Try the next shape Tesla might send. */
            }
        }
        return null;
    }

    /**
     * Fee lines folded into the totals a receipt and a history row both need.
     * Tesla itemizes by fee type (CHARGING, CONGESTION, …), each with its own
     * rate and usage; energy is whichever line is billed by the kilowatt-hour.
     */
    private static JSONObject feeTotals(JSONArray rawFees)
        throws JSONException {
        JSONObject result = new JSONObject();
        JSONArray fees = new JSONArray();
        double total = 0,
            energy = 0;
        boolean paid = true;
        String currency = null;
        if (rawFees != null) for (int i = 0; i < rawFees.length(); i++) {
            JSONObject fee = rawFees.optJSONObject(i);
            if (fee == null) continue;
            String type = fee.optString("feeType");
            String uom = fee.optString("uom");
            double usage =
                number(fee, "usageBase", 0) +
                number(fee, "usageTier1", 0) +
                number(fee, "usageTier2", 0);
            double feeTotal = number(fee, "totalDue", 0);
            if ("kwh".equalsIgnoreCase(uom)) energy += usage;
            total += feeTotal;
            paid = paid && (fee.optBoolean("isPaid") || feeTotal == 0);
            if (currency == null) currency = Jsons.string(fee, "currencyCode");
            fees.put(
                new JSONObject().put("type", type).put("label", feeLabel(type))
                    .put("currency", fee.optString("currencyCode")).put("uom", uom).put(
                        "waived",
                        "NO_CHARGE".equals(fee.optString("pricingType"))
                    ).put("total", feeTotal).put("usage", usage)
                    .put("rate", number(fee, "rateBase", 0))
            );
        }
        result.put("fees", fees).put("energyKwh", energy).put("total", total).put("paid", paid);
        Jsons.put(result, "currency", currency);
        return result;
    }

    public static List<JSONObject> sortedSites(JSONArray raw, JSONObject origin)
        throws JSONException {
        List<JSONObject> sites = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject source = raw.optJSONObject(i);
            if (source == null) continue;
            JSONObject site = siteListItem(source);
            if (site.isNull("distanceMiles") && !site.isNull("coordinates")) {
                JSONObject point = site.optJSONObject("coordinates");
                site.put(
                    "distanceMiles",
                    haversine(
                        origin.getDouble("lat"),
                        origin.getDouble("lng"),
                        point.getDouble("latitude"),
                        point.getDouble("longitude")
                    )
                );
            }
            sites.add(site);
        }
        Collections.sort(
            sites,
            (a, b) -> {
                return Double.compare(
                    a.optDouble("distanceMiles", Double.MAX_VALUE),
                    b.optDouble("distanceMiles", Double.MAX_VALUE)
                );
            }
        );
        return sites;
    }

    private static JSONObject chargingRate(JSONObject pricing)
        throws JSONException {
        JSONObject charging = Jsons.object(pricing, "userRates", "activePricebook", "charging");
        if (charging == null) return null;
        JSONArray rates = charging.optJSONArray("rates");
        JSONObject result = new JSONObject();
        Jsons.put(result, "currency", Jsons.string(charging, "currencyCode"));
        Jsons.put(result, "uom", Jsons.string(charging, "uom"));
        Jsons.put(result, "price", rates != null && rates.length() > 0 ? rates.optDouble(0) : null);
        result.put(
            "dynamic",
            Jsons.object(charging, "dynamicRates") != null &&
                Jsons.object(charging, "dynamicRates").optBoolean("enabled")
        );
        return result;
    }

    private static JSONObject schedule(JSONObject site) throws JSONException {
        JSONObject charging = Jsons.object(
            site,
            "pricing",
            "userRates",
            "activePricebook",
            "charging"
        );
        if (charging == null) return null;
        JSONArray rates = charging.optJSONArray("rates");
        Double flat =
            rates != null && rates.length() > 0 ? rates.optDouble(0) : null;
        int hour = currentHour(site.optString("timeZone", "UTC"));
        JSONObject tou = charging.optJSONObject("touRates");
        JSONArray windows =
            tou != null && tou.optBoolean("enabled")
                ? tou.optJSONArray("activeRatesByTime")
                : null;
        JSONArray hours = new JSONArray();
        Double current = flat;
        for (int h = 0; h < 24; h++) {
            Double price = priceAt(h * 60 + 30, windows, flat);
            JSONObject point = new JSONObject().put("hour", h);
            Jsons.put(point, "price", price);
            hours.put(point);
            if (h == hour) current = price;
        }
        JSONObject result = new JSONObject()
            .put("currency", charging.optString("currencyCode", "USD"))
            .put("uom", charging.optString("uom", "kwh")).put("currentHour", hour)
            .put("hours", hours);
        Jsons.put(result, "currentPrice", current);
        return result;
    }

    private static Double priceAt(int minute, JSONArray windows, Double fallback) {
        if (windows == null) return fallback;
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window == null) continue;
            int start = minutes(window.optString("startTime"));
            int end = "24:00".equals(window.optString("endTime"))
                ? 1440
                : minutes(window.optString("endTime"));
            if (minute >= start && minute < end) {
                JSONArray rates = window.optJSONArray("rates");
                return rates != null && rates.length() > 0
                    ? rates.optDouble(0)
                    : fallback;
            }
        }
        return fallback;
    }

    private static void addExtraRate(
        JSONArray target,
        JSONObject rate,
        String label
    ) throws JSONException {
        if (
            rate == null ||
            rate.optJSONArray("rates") == null ||
            rate.optJSONArray("rates").length() == 0
        ) return;
        target.put(
            new JSONObject().put("label", label)
                .put("price", rate.optJSONArray("rates").optDouble(0))
                .put("currency", rate.optString("currencyCode", "USD"))
                .put("uom", rate.optString("uom"))
        );
    }

    private static JSONObject priceObject(JSONObject price)
        throws JSONException {
        if (price == null || !price.has("price")) return null;
        return new JSONObject().put("price", price.optDouble("price"))
            .put("currency", price.optString("currencyCode", "USD"));
    }

    private static boolean eventMatches(JSONArray events, Pattern pattern) {
        for (int i = 0; i < events.length(); i++) if (
            pattern.matcher(events.optString(i)).find()
        ) return true;
        return false;
    }

    private static boolean eventContains(JSONArray events, String needle) {
        for (int i = 0; i < events.length(); i++) if (
            events.optString(i).contains(needle)
        ) return true;
        return false;
    }

    private static Long duration(JSONObject duration) {
        if (duration == null) return null;
        long value = duration.optLong("seconds", 0);
        return value > 0 && value < 65534 ? value : null;
    }

    private static Long timestamp(JSONObject timestamp) {
        if (timestamp == null) return null;
        long seconds = timestamp.optLong("seconds", 0);
        return seconds > 0 ? seconds * 1000L : null;
    }

    private static double nestedNumber(
        JSONObject root,
        String object,
        String key,
        double fallback
    ) {
        JSONObject child = root.optJSONObject(object);
        return child == null ? fallback : number(child, key, fallback);
    }

    private static Double nestedNullableNumber(JSONObject root, String object, String key) {
        return Jsons.number(root.optJSONObject(object), key);
    }

    private static double number(JSONObject root, String key, double fallback) {
        Double value = Jsons.number(root, key);
        return value == null ? fallback : value;
    }

    private static String formatAddress(JSONObject address) {
        if (address == null) return "";
        return join(", ", streetLine(address), localityLine(address));
    }

    /** Street line for any site or session record, including favourites and
     *  session contexts persisted before the address was stored pre-split. For
     *  those, the joined address is divided at its first comma. */
    public static String addressStreet(JSONObject record) {
        if (record == null) return "";
        String street = record.optString("addressStreet", "");
        if (street.length() > 0) return street;
        String joined = joinedAddress(record);
        int comma = joined.indexOf(',');
        return comma > 0 ? joined.substring(0, comma).trim() : joined;
    }

    /** City line for the same records, normalised so legacy values that comma
     *  separated the postal code read the same as freshly stored ones. */
    public static String addressLocality(JSONObject record) {
        if (record == null) return "";
        String locality = record.optString("addressLocality", "");
        if (locality.length() == 0) {
            String joined = joinedAddress(record);
            int comma = joined.indexOf(',');
            locality = comma > 0 ? joined.substring(comma + 1).trim() : "";
        }
        return locality.replaceAll(",\\s*(\\d[\\d-]*)$", " $1");
    }

    private static String joinedAddress(JSONObject record) {
        String value = record.optString("address", "");
        return value.length() > 0 ? value : record.optString("siteAddress", "");
    }

    /** "2450 W Happy Valley Rd" is the part worth remembering. */
    private static String streetLine(JSONObject address) {
        if (address == null) return "";
        return join(" ", Jsons.string(address, "streetNumber"), Jsons.string(address, "street"));
    }

    /** "Phoenix, AZ 85085": state and postal code read as one unit, so they
     *  are spaced rather than comma-separated. */
    private static String localityLine(JSONObject address) {
        if (address == null) return "";
        String region = join(
            " ",
            Jsons.string(address, "state"),
            Jsons.string(address, "postalCode")
        );
        return join(", ", Jsons.string(address, "city"), region);
    }

    private static String join(String separator, String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values)
            if (value != null && value.length() > 0) {
                if (result.length() > 0) result.append(separator);
                result.append(value);
            }
        return result.toString();
    }

    private static String first(String... values) {
        for (String value : values)
            if (value != null && value.length() > 0) return value;
        return null;
    }

    private static String labelWithoutPrefix(String value, String prefix) {
        return value == null ? null : titleCase(value.replace(prefix, ""));
    }

    private static String titleCase(String value) {
        StringBuilder result = new StringBuilder();
        for (String word : value.toLowerCase(Locale.US).split("_"))
            if (word.length() > 0) {
                if (result.length() > 0) result.append(' ');
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        return result.toString();
    }

    private static int naturalLabel(String a, String b) {
        try {
            int numberOrder = Integer.compare(
                Integer.parseInt(a.replaceAll("\\D", "")),
                Integer.parseInt(b.replaceAll("\\D", ""))
            );
            if (numberOrder != 0) return numberOrder;
            // Labels at the same numbered post still need a deterministic
            // connector order: 1A, 1B, 1C rather than Tesla's API order.
            return a.compareToIgnoreCase(b);
        } catch (Exception ignored) {
            return a.compareToIgnoreCase(b);
        }
    }

    private static int currentHour(String timeZone) {
        SimpleDateFormat format = new SimpleDateFormat("HH", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone(timeZone));
        return Integer.parseInt(format.format(new Date()));
    }

    private static int minutes(String hhmm) {
        String[] parts = hhmm.split(":");
        try {
            return (
                Integer.parseInt(parts[0]) * 60 +
                (parts.length > 1 ? Integer.parseInt(parts[1]) : 0)
            );
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String feeLabel(String type) {
        if ("CHARGING".equals(type)) return "Charging";
        if ("CONGESTION".equals(type)) return "Congestion fee";
        if ("IDLE".equals(type)) return "Idle fee";
        return titleCase(type);
    }

    private static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1),
            dLng = Math.toRadians(lng2 - lng1);
        double h =
            Math.pow(Math.sin(dLat / 2), 2) +
            Math.cos(Math.toRadians(lat1)) *
                Math.cos(Math.toRadians(lat2)) *
                Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 3958.7613 * Math.asin(Math.sqrt(h));
    }
}
