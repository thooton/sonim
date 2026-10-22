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
        System.exit(check.report() ? 0 : 1);
    }
}
