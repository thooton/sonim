package com.weather.xp3;

import com.xp3.common.Check;
import java.time.OffsetDateTime;
import java.util.List;

/** Everything in :core, checked on a desktop JVM. */
public final class SelfTest {

    /** 10:20 on Thursday 22 October 2026 in Miami (UTC-4). */
    static final long NOW = OffsetDateTime.parse("2026-10-22T10:20:00-04:00").toInstant().toEpochMilli();

    public static void main(String[] args) {
        final Check check = new Check();
        check.run("colors", () -> {
            check.same("a step is its own colour", 0xFF68FC57, Colors.temp(50));
            check.same("below the scale", 0xFF8A01C1, Colors.temp(-80));
            check.same("above it", 0xFFFFCBB0, Colors.temp(140));
            check.same("halfway", Colors.mix(0xFFF7D505, 0xFFE06F00, .5), Colors.temp(65));
            check.same("mix ends", 0xFF102030, Colors.mix(0xFF102030, 0xFFFFFFFF, 0));
            check.same("uv high", 0xFFFF3B30, Colors.uv(9));
        });
        check.run("conditions", () -> {
            check.same("sunny card", Colors.CARD_DAY, Condition.card(Condition.CLEAR_DAY));
            check.same("snow card", 0xFF61A3DE, Condition.card(Condition.SNOW));
            check.same("storm scenery", Condition.Scenery.RAIN, Condition.scenery(Condition.THUNDERSTORM));
            check.same("night effect", Condition.Effect.STARS, Condition.effect(Condition.CLEAR_NIGHT));
            check.same("by day", Condition.PARTLY_CLOUDY_DAY, Condition.daytime(Condition.PARTLY_CLOUDY_NIGHT));
        });
        check.run("units", () -> {
            check.same("f", "86°", Units.US.degrees(86));
            check.same("c", "30°", Units.METRIC.degrees(86));
            check.same("rounds", "-1°", Units.METRIC.degrees(30.5));
            check.same("mph to km/h", 16, Units.METRIC.speed(10));
            check.same("inches", ".12\"", Units.US.precip(.12));
            check.same("no rain", "0\"", Units.US.precip(0));
            check.same("mm", "3.0", Units.METRIC.precip(.12));
            check.same("inHg", "29.97", Units.US.pressure(29.97));
            check.same("hPa", "1015", Units.METRIC.pressure(29.97));
            check.same("miles", "10mi", Units.US.distance(10));
            check.same("some miles", "2.5mi", Units.US.distance(2.5));
            check.same("km", "16km", Units.METRIC.distance(10));
        });
        check.run("metrics", () -> {
            Forecast.Hour h = new Forecast.Hour(0, "rain");
            h.pop = 40; h.temp = 50; h.precip = .05; h.wind = 12;
            check.same("chance", "40%", Metric.POP.format(Metric.POP.value(h), Units.US));
            check.same("temp", "10°", Metric.TEMP.format(Metric.TEMP.value(h), Units.METRIC));
            check.close("plotted in C", 10, Metric.TEMP.plotted(50, Units.METRIC), 1e-9);
            check.same("wind", "12", Metric.WIND.format(h.wind, Units.US));
            check.same("cycles on", Metric.TEMP, Metric.VISIBILITY.next());
            check.same("and back", Metric.VISIBILITY, Metric.TEMP.previous());
            check.same("by name", Metric.UV, Metric.named("UV"));
            check.same("wind's unit", "Wind · km/h", Metric.WIND.chip(Units.METRIC));
            check.same("inches say so", "Precip", Metric.PRECIP.chip(Units.US));
            check.same("plain", "Humidity", Metric.HUMIDITY.chip(Units.US));
            check.same("unknown", Metric.TEMP, Metric.named("nope"));
        });
        check.run("forecast", () -> {
            Forecast f = Sample.of("rain", NOW, -4 * 3600);
            check.same("place", "Seattle, WA", f.place);
            List<Forecast.Hour> hours = f.hoursFrom(NOW);
            check.same("starts this hour", "10", f.hourLabel(hours.get(0).time, false)[0]);
            check.same("am", "AM", f.hourLabel(hours.get(0).time, false)[1]);
            check.same("noon", "12", f.hourLabel(hours.get(2).time, false)[0]);
            check.same("pm", "PM", f.hourLabel(hours.get(2).time, false)[1]);
            check.same("24 hour", "13", f.hourLabel(hours.get(3).time, true)[0]);
            List<Forecast.Day> days = f.daysFrom(NOW);
            check.same("ten days", 10, days.size());
            check.same("today", "TODAY", f.dayLabel(days.get(0), NOW));
            check.same("tomorrow", "FRI", f.dayLabel(days.get(1), NOW));
            check.ok("the past is gone", f.hoursFrom(NOW + 5 * 3600 * 1000L).size() == hours.size() - 5);
        });
        check.run("path data", () -> {
            PathData p = PathData.parse("M0,0 L10,0 h5 v5 Z");
            check.same("ops", 5, p.ops.size());
            check.close("h is relative", 15, p.ops.get(2)[1], 1e-6);
            check.close("v", 5, p.ops.get(3)[2], 1e-6);
            PathData a = PathData.parse("M0,10 A10,10 0 0 1 20,10");
            check.same("a half circle is two quarters", 3, a.ops.size());
            float[] mid = a.ops.get(1);
            check.close("through the top x", 10, mid[5], 1e-3);
            check.close("through the top y", 0, mid[6], 1e-3);
            float[] end = a.ops.get(2);
            check.close("ends where asked", 20, end[5], 1e-6);
            PathData flags = PathData.parse("M8.5,27 A6.5,6.5 0 0 0 9,26z");
            check.ok("closes", flags.ops.get(flags.ops.size() - 1)[0] == PathData.CLOSE);
            PathData rel = PathData.parse("m1,1 c1,0 2,0 3,0 s1,1 2,2");
            float[] s = rel.ops.get(2);
            check.close("s reflects", 5, s[1], 1e-6);
            check.close("s ends relative", 6, s[5], 1e-6);
            PathData run = PathData.parse("M0,0 a5,5 0 105,5");
            check.ok("run-together flags", run.ops.size() > 2);
            PathData exp = PathData.parse("M1e1,-2.5e-1L.5.5");
            check.close("exponent", 10, exp.ops.get(0)[1], 1e-6);
            check.close("dots split numbers", .5, exp.ops.get(1)[2], 1e-6);
        });
        check.run("open-meteo", () -> {
            String body = "{\"utc_offset_seconds\":-14400,"
                + "\"current\":{\"temperature_2m\":69.2,\"apparent_temperature\":71.6,\"weather_code\":2,\"is_day\":0,\"wind_speed_10m\":6.6},"
                + "\"hourly\":{\"time\":[1792641600,1792645200],\"temperature_2m\":[70.1,69.4],\"apparent_temperature\":[72,71],"
                + "\"precipitation_probability\":[10,40],\"precipitation\":[0,0.05],\"weather_code\":[0,61],\"cloud_cover\":[5,90],"
                + "\"wind_speed_10m\":[5,30],\"wind_direction_10m\":[90,180],\"wind_gusts_10m\":[9,40],\"relative_humidity_2m\":[80,95],"
                + "\"dew_point_2m\":[63,66],\"pressure_msl\":[1015.5,1013],\"visibility\":[52800,null],\"uv_index\":[0,1.5],\"is_day\":[0,1]},"
                + "\"daily\":{\"time\":[1792641600],\"weather_code\":[95],\"temperature_2m_max\":[81.8],\"temperature_2m_min\":[67.8],"
                + "\"precipitation_probability_max\":[57]}}";
            try (com.xp3.common.TestServer server = new com.xp3.common.TestServer(req -> {
                if (req.path.startsWith("/v1/forecast")) return com.xp3.common.TestServer.Reply.json(body);
                if (req.path.startsWith("/v1/search")) return com.xp3.common.TestServer.Reply.json(
                    "{\"results\":[{\"name\":\"Portland\",\"latitude\":45.52,\"longitude\":-122.68,\"admin1\":\"Oregon\",\"country\":\"United States\",\"country_code\":\"US\"},"
                    + "{\"name\":\"Portland\",\"latitude\":-38.34,\"longitude\":141.6,\"admin1\":\"Victoria\",\"country\":\"Australia\",\"country_code\":\"AU\"},"
                    + "{\"name\":\"Nowhere\"}]}");
                if (req.path.startsWith("/data/reverse")) return com.xp3.common.TestServer.Reply.json(
                    "{\"city\":\"Miami\",\"principalSubdivision\":\"Florida\",\"countryName\":\"United States of America\",\"countryCode\":\"US\"}");
                return com.xp3.common.TestServer.Reply.of(404, "text/plain", "no");
            })) {
                String base = server.uri("").toString().replaceAll("/$", "");
                OpenMeteo om = new OpenMeteo(new com.xp3.common.Http(), base, base, base);
                Forecast f = om.forecast(new Place("Miami, FL", "", 25.77, -80.19), NOW);
                String asked = server.seen.get(0).path;
                check.ok("US units", asked.contains("temperature_unit=fahrenheit") && asked.contains("wind_speed_unit=mph"));
                check.ok("where", asked.contains("latitude=25.7700&longitude=-80.1900"));
                check.same("place", "Miami, FL", f.place);
                check.same("now", Condition.PARTLY_CLOUDY_NIGHT, f.now.condition);
                check.close("temp", 69.2, f.now.temp, 1e-9);
                check.same("offset", -14400, f.utcOffset);
                Forecast.Hour h = f.hours.get(1);
                check.same("windy rain is rain", Condition.RAIN, h.condition);
                check.close("hPa to inHg", 29.92, h.pressure, .01);
                check.close("feet to miles", 10, f.hours.get(0).visibility, 1e-9);
                check.close("a null is nothing", 0, h.visibility, 1e-9);
                check.same("storm day", Condition.THUNDERSTORM, f.days.get(0).condition);
                check.close("low", 67.8, f.days.get(0).low, 1e-9);
                List<Place> found = om.search("Portland");
                check.same("found two", 2, found.size());
                check.same("US state", "Portland, OR", found.get(0).name);
                check.same("detail", "Oregon, United States", found.get(0).detail);
                check.same("abroad", "Portland", found.get(1).name);
                check.ok("encoded", server.seen.get(1).path.contains("name=Portland&"));
                check.same("too short", 0, om.search("P").size());
                Place here = om.named(25.77, -80.19);
                check.same("here", "Miami, FL", here.name);
            }
            check.same("clear", Condition.CLEAR_DAY, OpenMeteo.condition(1, true, 0));
            check.same("windy", Condition.WIND, OpenMeteo.condition(2, true, 30));
            check.same("snow showers", Condition.SNOW, OpenMeteo.condition(85, false, 0));
            check.same("freezing rain", Condition.SLEET, OpenMeteo.condition(66, true, 0));
            check.same("fog", Condition.FOG, OpenMeteo.condition(45, false, 0));
            check.fails("junk", () -> OpenMeteo.parse("{\"current\":{}}", "x", NOW));
        });
        check.run("places", () -> {
            Place a = new Place("Miami, FL", "Florida", 25.77, -80.19), b = new Place("Seattle, WA", "", 47.6, -122.3);
            List<Place> r = Place.remember(Place.remember(new java.util.ArrayList<Place>(), a, 3), b, 3);
            r = Place.remember(r, new Place("Miami", "", 25.7701, -80.1901), 3);
            check.same("no twice", 2, r.size());
            check.same("newest first", "Miami", r.get(0).name);
            List<Place> back = Place.list(Place.write(r));
            check.same("round trip", "Seattle, WA", back.get(1).name);
            check.ok("junk list", Place.list("[{]").isEmpty());
            check.ok("no name", Place.fromJsonString("{\"lat\":1,\"lon\":2}") == null);
        });
        System.exit(check.report() ? 0 : 1);
    }
}
