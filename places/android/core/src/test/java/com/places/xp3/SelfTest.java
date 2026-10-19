package com.places.xp3;

import com.places.xp3.net.Anchor;
import com.places.xp3.net.Link;
import com.places.xp3.net.Place;
import com.places.xp3.net.Places;
import com.xp3.common.Check;
import com.xp3.common.Json;
import com.xp3.common.TestServer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Everything in :core, checked on a desktop JVM. */
public final class SelfTest {

    /** Noon on Friday 16 October 2026 in Arizona (UTC-7), where the test places are. */
    static final long NOON = OffsetDateTime.parse("2026-10-16T12:00:00-07:00").toInstant().toEpochMilli();

    public static void main(String[] args) {
        final Check check = new Check();
        check.run("anchor", () -> {
            Anchor typed = Anchor.coordinates(" 33.4255, -111.94 ");
            check.ok("coordinates need no lookup", typed != null);
            check.same("label", "33.42550, -111.94000", typed.label);
            check.ok("out of range", Anchor.coordinates("91,0") == null);
            check.ok("a name is not coordinates", Anchor.coordinates("Tempe, AZ") == null);
            Anchor tempe = new Anchor("Tempe", "Tempe, AZ, USA", 33.4255, -111.94);
            Anchor back = Anchor.parse(tempe.toJson());
            check.same("round trip label", "Tempe, AZ, USA", back.label);
            check.close("round trip lat", 33.4255, back.lat, 1e-9);
            check.ok("same place, any case", back.isFor(" tempe "));
            check.ok("another place", !back.isFor("Mesa"));
            check.ok("junk is nothing", Anchor.parse("{nope") == null);
            check.close("a degree of latitude", 111195, new Anchor("", "", 0, 0).metersTo(1, 0), 50);
            check.same("feet", "164 ft", Anchor.distance(50));
            check.same("miles", "2.4 mi", Anchor.distance(2.4 * 1609.344));
            check.same("many miles", "31 mi", Anchor.distance(31 * 1609.344));
        });
        check.run("status", () -> {
            check.same("open", "Open until 9:00 PM",
                place("{\"utcOffsetMinutes\":-420,\"currentOpeningHours\":{\"openNow\":true,\"nextCloseTime\":\"2026-10-17T04:00:00Z\"}}").status(NOON));
            check.same("open past midnight", "Open until midnight",
                place("{\"utcOffsetMinutes\":-420,\"currentOpeningHours\":{\"openNow\":true,\"nextCloseTime\":\"2026-10-17T07:00:00Z\"}}").status(NOON));
            check.same("closed", "Closed until tomorrow 7:00 AM",
                place("{\"utcOffsetMinutes\":-420,\"currentOpeningHours\":{\"openNow\":false,\"nextOpenTime\":\"2026-10-17T14:00:00Z\"}}").status(NOON));
            check.same("closed for days", "Closed until Tue 7:00 AM",
                place("{\"utcOffsetMinutes\":-420,\"currentOpeningHours\":{\"openNow\":false,\"nextOpenTime\":\"2026-10-20T14:00:00Z\"}}").status(NOON));
            check.same("closed for weeks", "Closed until Mon Nov 2, 7:00 AM",
                place("{\"utcOffsetMinutes\":-420,\"currentOpeningHours\":{\"openNow\":false,\"nextOpenTime\":\"2026-11-02T14:00:00Z\"}}").status(NOON));
            check.same("no times", "Open now", place("{\"currentOpeningHours\":{\"openNow\":true}}").status(NOON));
            check.same("unknown", "Hours unknown", place("{}").status(NOON));
            check.same("gone", "Permanently closed",
                place("{\"businessStatus\":\"CLOSED_PERMANENTLY\",\"currentOpeningHours\":{\"openNow\":true}}").status(NOON));
            check.same("gone is not open", Boolean.FALSE,
                place("{\"businessStatus\":\"CLOSED_PERMANENTLY\",\"currentOpeningHours\":{\"openNow\":true}}").openNow());
            check.same("open", Boolean.TRUE, place("{\"currentOpeningHours\":{\"openNow\":true}}").openNow());
            check.same("not known", null, place("{}").openNow());
        });
        check.run("summary", () -> {
            Place p = place("{\"displayName\":{\"text\":\"Cartel Coffee\"},\"primaryTypeDisplayName\":{\"text\":\"Coffee shop\"},"
                + "\"rating\":4.62,\"userRatingCount\":1820,\"priceLevel\":\"PRICE_LEVEL_MODERATE\","
                + "\"location\":{\"latitude\":33.4255,\"longitude\":-111.94}}");
            check.same("name", "Cartel Coffee", p.name());
            check.same("here", "164 ft", p.distanceFrom(new Anchor("x", "x", 33.4255 + 50 / 111195.0, -111.94)));
            check.same("no anchor", "", p.distanceFrom(null));
            check.same("unnamed", "Unnamed place", place("{}").name());
        });
        check.run("details", () -> {
            Place p = place("{\"id\":\"ChIJabc\",\"displayName\":{\"text\":\"Joe's\"},\"primaryTypeDisplayName\":{\"text\":\"Diner\"},"
                + "\"rating\":4,\"userRatingCount\":1,\"priceLevel\":\"PRICE_LEVEL_INEXPENSIVE\","
                + "\"priceRange\":{\"startPrice\":{\"currencyCode\":\"USD\",\"units\":\"10\"},\"endPrice\":{\"currencyCode\":\"USD\",\"units\":\"20\"}},"
                + "\"formattedAddress\":\"1 Mill Ave, Tempe, AZ\",\"nationalPhoneNumber\":\"(480) 555-0100\",\"utcOffsetMinutes\":-420,"
                + "\"location\":{\"latitude\":33.4255,\"longitude\":-111.94},"
                + "\"currentOpeningHours\":{\"openNow\":true,\"nextCloseTime\":\"2026-10-17T04:00:00Z\","
                + "\"weekdayDescriptions\":[\"Monday: 7:00 AM – 9:00 PM\",\"Tuesday: Closed\"]},"
                + "\"regularOpeningHours\":{\"weekdayDescriptions\":[\"Monday: 7:00 AM – 9:00 PM\",\"Tuesday: Closed\"]},"
                + "\"currentSecondaryOpeningHours\":[{\"secondaryHoursType\":\"HAPPY_HOUR\",\"weekdayDescriptions\":[\"Friday: 3:00 PM – 6:00 PM\"]}],"
                + "\"editorialSummary\":{\"text\":\"Pancakes since 1950.\"},"
                + "\"takeout\":true,\"delivery\":false,\"paymentOptions\":{\"acceptsCreditCards\":true,\"acceptsNfc\":true,\"acceptsCashOnly\":false},"
                + "\"accessibilityOptions\":{\"wheelchairAccessibleEntrance\":true},"
                + "\"fuelOptions\":{\"fuelPrices\":[{\"type\":\"REGULAR_UNLEADED\",\"price\":{\"currencyCode\":\"USD\",\"units\":\"3\",\"nanos\":459000000}}]},"
                + "\"reviews\":[{\"rating\":5,\"relativePublishTimeDescription\":\"a week ago\",\"text\":{\"text\":\" Great. \"},"
                + "\"authorAttribution\":{\"displayName\":\"Sam\"}}]}");
            List<Place.Section> sections = p.details(NOON, new Anchor("Tempe", "Tempe, AZ, USA", 33.4255, -111.94));
            check.same("top", "Diner\n★★★★☆ 4.0 (1 review)\n$ ($10-20 per person)\nOpen until 9:00 PM\n0 ft from Tempe, AZ, USA",
                text(sections, null));
            check.same("address", "1 Mill Ave, Tempe, AZ", text(sections, "Address"));
            check.same("phone", "(480) 555-0100", p.phone());
            check.same("local time", "Fri Oct 16, 12:00 PM", text(sections, "Local time there"));
            check.same("hours, days shortened, spaces made plain and dashes hyphens", "Mon 7:00 AM - 9:00 PM\nTue Closed", text(sections, "Hours this week"));
            check.same("usual hours left out when the same", null, text(sections, "Usual hours"));
            check.same("happy hour", "Fri 3:00 PM - 6:00 PM", text(sections, "Happy hour"));
            check.same("about", "Pancakes since 1950.", text(sections, "About"));
            check.same("has", "takeout", text(sections, "Has"));
            check.same("hasn't", "delivery", text(sections, "Doesn't have"));
            check.same("payment", "credit cards, NFC (not: cash only)", text(sections, "Payment"));
            check.same("wheelchair", "entrance", text(sections, "Wheelchair access"));
            check.same("fuel", "regular unleaded: $3.46", text(sections, "Fuel"));
            check.same("review", "★★★★★ · Sam · a week ago\nGreat.", text(sections, "Review"));
            check.same("no website, no section", null, text(sections, "Website"));
            check.same("no hours", "No opening hours listed.", text(place("{}").details(NOON, null), "Hours"));

            // The pieces the details screen draws from, apart.
            check.same("open, apart", "Open|Closes 9:00 PM", String.join("|", p.statusParts(NOON)));
            check.same("closed, apart", "Closed|Opens tomorrow 7:00 AM", String.join("|",
                place("{\"utcOffsetMinutes\":-420,\"currentOpeningHours\":{\"openNow\":false,\"nextOpenTime\":\"2026-10-17T14:00:00Z\"}}").statusParts(NOON)));
            check.same("unknown, apart", "Hours unknown|", String.join("|", place("{}").statusParts(NOON)));
            check.same("today, there", "Fri", p.today(NOON));
            check.same("short price", "$10-20", p.priceShort());
            check.same("country left off", "2400 W Happy Valley Rd #1100, Phoenix, AZ 85085",
                place("{\"formattedAddress\":\"2400 W Happy Valley Rd #1100, Phoenix, AZ 85085, USA\"}").localAddress());
            check.same("postcode last, kept", "10 Downing St, London SW1A 2AA",
                place("{\"formattedAddress\":\"10 Downing St, London SW1A 2AA\"}").localAddress());
            check.same("one part, kept", "Tempe", place("{\"formattedAddress\":\"Tempe\"}").localAddress());
            check.same("short price, level only", "$$", place("{\"priceLevel\":\"PRICE_LEVEL_MODERATE\"}").priceShort());
            check.same("local time, elsewhere", "Fri Oct 16, 12:00 PM", p.localTime(NOON, -240));
            check.same("local time, same offset", "", p.localTime(NOON, -420));
            check.ok("wheelchair entrance", p.wheelchairEntrance());
            check.same("features", "[takeout]|[delivery]", p.features().yes + "|" + p.features().no);
            check.same("payment", "[credit cards, NFC]|[cash only]", p.payment().yes + "|" + p.payment().no);
            check.ok("no parking", p.parking().isEmpty());
            check.same("fuel", "regular unleaded $3.46", p.fuel().get(0)[0] + " " + p.fuel().get(0)[1]);
            Place.Review review = p.reviews().get(0);
            check.same("review", "5.0 Sam, a week ago: Great.",
                String.format(java.util.Locale.US, "%.1f %s, %s: %s", review.rating, review.author, review.ago, review.text));
            check.same("hours sections", 2, p.hours().size());
        });
        check.run("links", () -> {
            Link place = Link.parse("https://www.google.com/maps/place/Cartel+Coffee+Lab/@33.4210,-111.9400,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d33.4215!4d-111.9391!16s%2Fg%2F1tf");
            check.same("place name", "Cartel Coffee Lab", place.query);
            check.close("the pin, not the view", 33.4215, place.near.lat, 1e-9);
            check.same("no id", null, place.placeId);
            Link api = Link.parse("https://www.google.com/maps/search/?api=1&query=Joe%27s+Diner&query_place_id=ChIJabc_123");
            check.same("api query", "Joe's Diner", api.query);
            check.same("api place id", "ChIJabc_123", api.placeId);
            check.same("q", "1 Mill Ave, Tempe", Link.parse("https://maps.google.com/?q=1+Mill+Ave,+Tempe").query);
            check.same("place_id in q", "ChIJxyz", Link.parse("https://maps.google.com/maps?q=place_id:ChIJxyz").placeId);
            Link point = Link.parse("https://maps.google.com/?q=33.42,-111.94");
            check.ok("a point in q", point.query == null && point.near != null && point.near.lng == -111.94);
            check.close("a view", 40.7, Link.parse("https://www.google.com/maps/@40.7,-74.0,15z").near.lat, 1e-9);
            check.same("directions go to the last stop", "Tempe Town Lake",
                Link.parse("https://www.google.com/maps/dir/Home/Tempe+Town+Lake/@33.4,-111.9,14z/data=!4m2").query);
            Link geo = Link.parse("geo:33.42,-111.94?q=33.42,-111.94(Cartel%20Coffee)");
            check.ok("geo label", "Cartel Coffee".equals(geo.query) && geo.near.lat == 33.42);
            Link address = Link.parse("geo:0,0?q=1+Mill+Ave+Tempe");
            check.ok("geo address", "1 Mill Ave Tempe".equals(address.query) && address.near == null);
            check.ok("geo point", Link.parse("geo:33.42,-111.94").near != null);
            check.ok("nothing to look up", Link.parse("https://www.google.com/maps") == null);
            check.ok("short", Link.isShort("https://maps.app.goo.gl/AbC123") && Link.isShort("https://goo.gl/maps/AbC"));
            check.ok("not short", !Link.isShort("https://goo.gl/xyz") && !Link.isShort("https://www.google.com/maps/@1,2,3z"));
        });
        check.run("client", () -> {
            final AtomicInteger keys = new AtomicInteger();
            final String[] key = {"key-1"};
            final String[] server0 = {""};
            TestServer server = new TestServer(request -> {
                if (request.path.equals("/cc/maps-key")) {
                    if (!request.header("Authorization").equals("Bearer tok")) return TestServer.Reply.of(401, "text/plain", "no");
                    keys.incrementAndGet();
                    return TestServer.Reply.json("{\"key\":\"" + key[0] + "\"}");
                }
                if (request.path.equals("/short/AbC")) {
                    return TestServer.Reply.of(302, "text/html", "").header("Location: /maps/place/Joe%27s/@33.4,-111.9,17z");
                }
                if (request.path.equals("/v1/img/one.jpg")) {
                    return TestServer.Reply.of(request.header("X-Goog-Api-Key") == null || request.header("X-Goog-Api-Key").isEmpty() ? 200 : 400, "image/jpeg", "JPEG");
                }
                if (!request.header("X-Goog-Api-Key").equals("key-2")) {
                    return TestServer.Reply.of(400, "application/json",
                        "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\"}}");
                }
                if (request.path.equals("/v1/places:searchText")) {
                    Map<String, Object> body = Json.object(Json.parse(request.body));
                    if (((Number) body.get("pageSize")).intValue() == 1) {
                        return TestServer.Reply.json("{\"places\":[{\"id\":\"ChIJtempe\",\"displayName\":{\"text\":\"Tempe\"},"
                            + "\"formattedAddress\":\"Tempe, AZ, USA\",\"location\":{\"latitude\":33.4,\"longitude\":-111.9}}]}");
                    }
                    if (body.get("pageToken") != null) {
                        return TestServer.Reply.json("{\"places\":[{\"id\":\"ChIJthird\",\"displayName\":{\"text\":\"Third\"}}]}");
                    }
                    return TestServer.Reply.json("{\"places\":[{\"id\":\"ChIJone\",\"displayName\":{\"text\":\"One\"}},"
                        + "{\"id\":\"ChIJtwo\",\"displayName\":{\"text\":\"Two\"}}],\"nextPageToken\":\"page2\"}");
                }
                if (request.path.startsWith("/v1/places/ChIJone/photos/p1/media?")) {
                    return TestServer.Reply.json("{\"name\":\"places/ChIJone/photos/p1\",\"photoUri\":\"" + server0[0] + "/v1/img/one.jpg\"}");
                }
                if (request.path.equals("/v1/places/ChIJone") && request.method.equals("GET")) {
                    return TestServer.Reply.json("{\"id\":\"ChIJone\",\"displayName\":{\"text\":\"One\"},\"websiteUri\":\"https://one.example\","
                        + "\"photos\":[{\"name\":\"places/ChIJone/photos/p1\"},{\"widthPx\":10}]}");
                }
                return TestServer.Reply.of(404, "application/json", "{}");
            });
            try {
                String base = server.uri("").toString();
                server0[0] = base;
                Places places = new Places(base + "/cc", "tok", base + "/v1");
                check.fails("a refused key says why", "API key not valid", () -> places.locate("Tempe"));
                key[0] = "key-2";
                Anchor tempe = places.locate("Tempe");
                check.same("refused key fetched again", 2, keys.get());
                check.same("label is the address", "Tempe, AZ, USA", tempe.label);
                check.same("typed kept", "Tempe", tempe.typed);
                TestServer.Request located = last(server);
                check.same("locate asks for Pro fields only",
                    "places.id,places.displayName,places.formattedAddress,places.location", located.header("X-Goog-FieldMask"));

                Places.Page page = places.search("coffee", tempe, null);
                check.same("results", 2, page.places.size());
                check.same("first", "One", page.places.get(0).name());
                check.same("more", "page2", page.next);
                Map<String, Object> sent = Json.object(Json.parse(last(server).body));
                Map<String, Object> circle = Json.child(Json.child(sent, "locationBias"), "circle");
                check.close("biased toward the anchor", 33.4, Json.number(Json.child(circle, "center"), "latitude"), 1e-9);
                check.ok("search asks for hours", last(server).header("X-Goog-FieldMask").contains("currentOpeningHours.openNow"));
                check.ok("search asks for the price range", last(server).header("X-Goog-FieldMask").contains("places.priceRange"));
                check.ok("search asks for the entrance", last(server).header("X-Goog-FieldMask").contains("places.accessibilityOptions.wheelchairAccessibleEntrance"));
                check.ok("search leaves reviews out", !last(server).header("X-Goog-FieldMask").contains("reviews"));
                Places.Page second = places.search("coffee", tempe, page.next);
                check.same("next page", "Third", second.places.get(0).name());
                check.same("last page", null, second.next);
                check.same("same page token sent", "page2", Json.text(Json.object(Json.parse(last(server).body)), "pageToken"));

                Places.Page anywhere = places.search("coffee", null, null);
                check.ok("no anchor, no bias", !Json.object(Json.parse(last(server).body)).containsKey("locationBias"));
                check.same("anywhere results", 2, anywhere.places.size());

                Place one = places.details("ChIJone");
                check.same("details", "https://one.example", one.website());
                check.ok("details ask for reviews", last(server).header("X-Goog-FieldMask").contains("reviews"));
                check.ok("details ask for features", last(server).header("X-Goog-FieldMask").contains("servesCoffee"));
                check.same("key fetched once", 2, keys.get());
                check.fails("an id is checked", () -> places.details("../maps-key"));
                check.same("photos named", java.util.Arrays.asList("places/ChIJone/photos/p1"), one.photos());
                check.same("photo fetched without the key", "JPEG", new String(places.photo("places/ChIJone/photos/p1", 160), "UTF-8"));
                check.ok("photo scaled by Google", last(server).path.endsWith("/img/one.jpg"));
                check.fails("a photo name is checked", () -> places.photo("../maps-key", 160));
                places.details("ChIJone");
                check.ok("details ask for photos", last(server).header("X-Goog-FieldMask").contains(",photos"));
                places.search("coffee", tempe, null, true, 4.5);
                check.ok("search asks for photos and the phone", last(server).header("X-Goog-FieldMask").contains("places.photos")
                    && last(server).header("X-Goog-FieldMask").contains("places.nationalPhoneNumber"));
                Map<String, Object> filtered = Json.object(Json.parse(last(server).body));
                check.ok("open now sent", Json.flag(filtered, "openNow"));
                check.close("rating floor sent", 4.5, Json.number(filtered, "minRating"), 1e-9);
                places.search("coffee", tempe, null);
                check.ok("no filters, none sent", !Json.object(Json.parse(last(server).body)).containsKey("openNow"));

                check.same("a short link followed", base + "/maps/place/Joe%27s/@33.4,-111.9,17z", places.expand(base + "/short/AbC"));
                check.same("a full link as it is", "https://maps.google.com/?q=x", places.expand("https://maps.google.com/?q=x"));
                check.fails("only Google is followed", "not a Google Maps link", () -> places.expand("https://example.com/maps"));

                Places stranger = new Places(base + "/cc", "wrong", base + "/v1");
                check.fails("a bad token says so", "login", () -> stranger.search("coffee", null, null));
            } finally {
                server.close();
            }
            TestServer empty = new TestServer(request -> request.path.endsWith("/maps-key")
                ? TestServer.Reply.of(404, "application/json", "{\"error\":\"none\"}")
                : TestServer.Reply.json("{}"));
            try {
                String base = empty.uri("").toString();
                check.fails("no key on the command center", "PUT /maps-key",
                    () -> new Places(base, "tok", base).search("coffee", null, null));
            } finally {
                empty.close();
            }
            TestServer nothing = new TestServer(request -> request.path.endsWith("/maps-key")
                ? TestServer.Reply.json("{\"key\":\"k\"}")
                : TestServer.Reply.json("{}"));
            try {
                String base = nothing.uri("").toString();
                check.fails("nowhere found", "Couldn't find", () -> new Places(base, "tok", base).locate("Atlantis"));
                check.same("no results", 0, new Places(base, "tok", base).search("coffee", null, null).places.size());
            } finally {
                nothing.close();
            }
        });
        if (!check.report()) System.exit(1);
    }

    static Place place(String json) {
        return new Place(Json.object(Json.parse(json)));
    }

    /** The text under a heading, or null when there is no such section. */
    static String text(List<Place.Section> sections, String title) {
        for (Place.Section section : sections) {
            if (title == null ? section.title == null : title.equals(section.title)) return section.text;
        }
        return null;
    }

    static TestServer.Request last(TestServer server) {
        return server.seen.get(server.seen.size() - 1);
    }
}
