package com.weather.xp3;

import com.xp3.common.Http;
import com.xp3.common.Json;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The weather, from Open-Meteo (open-meteo.com): free, no key, and every
 * figure CARROT's hourly chips show. Its geocoder finds places by name or
 * ZIP code; BigDataCloud's free client endpoint names the place the phone is
 * in, which Open-Meteo can't.
 *
 * Everything is asked for in US units, which is what Forecast keeps, except
 * pressure (hPa) and visibility (feet), converted here.
 */
public final class OpenMeteo {

    private final Http http;
    private final String forecastBase, geocodeBase, reverseBase;

    public OpenMeteo(Http http) {
        this(http, "https://api.open-meteo.com", "https://geocoding-api.open-meteo.com", "https://api.bigdatacloud.net");
    }

    /** For tests: all three at a local server. */
    public OpenMeteo(Http http, String forecastBase, String geocodeBase, String reverseBase) {
        this.http = http;
        this.forecastBase = forecastBase;
        this.geocodeBase = geocodeBase;
        this.reverseBase = reverseBase;
    }

    static final String HOURLY = "temperature_2m,apparent_temperature,precipitation_probability,precipitation,"
        + "weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m,relative_humidity_2m,"
        + "dew_point_2m,pressure_msl,visibility,uv_index,is_day";

    public Forecast forecast(Place place, long nowMillis) throws IOException, InterruptedException {
        return parse(forecastBody(place), place.name, nowMillis);
    }

    /** The forecast as Open-Meteo wrote it, for keeping on disk and reading with `parse`. */
    public String forecastBody(Place place) throws IOException, InterruptedException {
        String url = forecastBase + "/v1/forecast?latitude=" + coord(place.lat) + "&longitude=" + coord(place.lon)
            + "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m"
            + "&hourly=" + HOURLY
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
            + "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch"
            + "&timezone=auto&timeformat=unixtime&forecast_days=10&forecast_hours=48";
        return get(url, "Open-Meteo");
    }

    /** Places called `query` (a name or a ZIP code), the likeliest first. */
    public List<Place> search(String query) throws IOException, InterruptedException {
        String q = query.trim();
        if (q.length() < 2) return new ArrayList<>();
        String url = geocodeBase + "/v1/search?name=" + encode(q) + "&count=10&language=en&format=json";
        return places(get(url, "Open-Meteo search"));
    }

    /** What the phone's position is called: "Portland, OR". */
    public Place named(double lat, double lon) throws IOException, InterruptedException {
        String url = reverseBase + "/data/reverse-geocode-client?latitude=" + coord(lat) + "&longitude=" + coord(lon)
            + "&localityLanguage=en";
        return reverse(get(url, "BigDataCloud"), lat, lon);
    }

    private String get(String url, String service) throws IOException, InterruptedException {
        Http.Response r = http.send(Http.Request.to(URI.create(url)).timeout(30000));
        Http.requireSuccess(r.status(), service);
        return r.body();
    }

    // ------------------------------------------------------------------
    // Reading the answers.
    // ------------------------------------------------------------------

    public static Forecast parse(String body, String placeName, long nowMillis) throws IOException {
        try {
            Map<String, Object> o = Json.object(Json.parse(body));
            int offset = (int) num(o, "utc_offset_seconds", 0);
            Map<String, Object> cur = Json.child(o, "current");
            Map<String, Object> h = Json.child(o, "hourly");
            Map<String, Object> d = Json.child(o, "daily");
            List<Object> times = Json.children(h, "time");
            List<Forecast.Hour> hours = new ArrayList<>();
            for (int i = 0; i < times.size(); i++) {
                double wind = at(h, "wind_speed_10m", i);
                Forecast.Hour hour = new Forecast.Hour((long) at(h, "time", i),
                    condition((int) at(h, "weather_code", i), at(h, "is_day", i) != 0, wind));
                hour.temp = at(h, "temperature_2m", i);
                hour.feels = at(h, "apparent_temperature", i);
                hour.pop = at(h, "precipitation_probability", i);
                hour.precip = at(h, "precipitation", i);
                hour.cloud = at(h, "cloud_cover", i);
                hour.wind = wind;
                hour.direction = at(h, "wind_direction_10m", i);
                hour.gust = at(h, "wind_gusts_10m", i);
                hour.humidity = at(h, "relative_humidity_2m", i);
                hour.dew = at(h, "dew_point_2m", i);
                hour.pressure = at(h, "pressure_msl", i) * 0.0295300;
                hour.visibility = at(h, "visibility", i) / 5280.0;
                hour.uv = at(h, "uv_index", i);
                hours.add(hour);
            }
            List<Object> dayTimes = Json.children(d, "time");
            List<Forecast.Day> days = new ArrayList<>();
            for (int i = 0; i < dayTimes.size(); i++) {
                days.add(new Forecast.Day((long) at(d, "time", i), condition((int) at(d, "weather_code", i), true, 0),
                    at(d, "temperature_2m_min", i), at(d, "temperature_2m_max", i), at(d, "precipitation_probability_max", i)));
            }
            Forecast.Now now = new Forecast.Now(
                condition((int) num(cur, "weather_code", 0), num(cur, "is_day", 1) != 0, num(cur, "wind_speed_10m", 0)),
                num(cur, "temperature_2m", 0), num(cur, "apparent_temperature", 0));
            if (Json.number(cur, "temperature_2m") == null) throw new IllegalArgumentException("no temperature");
            return new Forecast(placeName, offset, nowMillis, now, hours, days);
        } catch (RuntimeException e) {
            throw new IOException("The forecast came back unreadable.");
        }
    }

    static List<Place> places(String body) throws IOException {
        List<Place> out = new ArrayList<>();
        try {
            Map<String, Object> o = Json.object(Json.parse(body));
            for (Object item : Json.children(o, "results")) {
                Map<String, Object> r = Json.object(item);
                String name = Json.text(r, "name");
                Double lat = Json.number(r, "latitude"), lon = Json.number(r, "longitude");
                if (name == null || lat == null || lon == null) continue;
                String admin = Json.text(r, "admin1"), country = Json.text(r, "country"), code = Json.text(r, "country_code");
                String shown = name;
                String state = "US".equals(code) ? STATES.get(admin) : null;
                if (state != null) shown = name + ", " + state;
                StringBuilder detail = new StringBuilder();
                if (admin != null && !admin.equals(name)) detail.append(admin);
                if (country != null) detail.append(detail.length() > 0 ? ", " : "").append(country);
                out.add(new Place(shown, detail.toString(), lat, lon));
            }
        } catch (RuntimeException e) {
            throw new IOException("The places came back unreadable.");
        }
        return out;
    }

    static Place reverse(String body, double lat, double lon) throws IOException {
        try {
            Map<String, Object> o = Json.object(Json.parse(body));
            String city = Json.text(o, "city");
            if (city == null || city.isEmpty()) city = Json.text(o, "locality");
            String admin = Json.text(o, "principalSubdivision"), country = Json.text(o, "countryName");
            String code = Json.text(o, "countryCode");
            if (city == null || city.isEmpty()) {
                city = String.format(Locale.US, "%.2f, %.2f", lat, lon);
            } else if ("US".equals(code) && STATES.get(admin) != null) {
                city = city + ", " + STATES.get(admin);
            }
            StringBuilder detail = new StringBuilder();
            if (admin != null && !admin.isEmpty()) detail.append(admin);
            if (country != null && !country.isEmpty()) detail.append(detail.length() > 0 ? ", " : "").append(country);
            return new Place(city, detail.toString(), lat, lon);
        } catch (RuntimeException e) {
            throw new IOException("Couldn't tell where the phone is.");
        }
    }

    /**
     * A WMO weather code as one of CARROT's conditions. A clear or cloudy
     * hour with a strong wind is CARROT's windy sky.
     */
    static String condition(int code, boolean day, double windMph) {
        if (code <= 3 && windMph >= 25) return Condition.WIND;
        switch (code) {
            case 0: case 1: return day ? Condition.CLEAR_DAY : Condition.CLEAR_NIGHT;
            case 2: return day ? Condition.PARTLY_CLOUDY_DAY : Condition.PARTLY_CLOUDY_NIGHT;
            case 3: return day ? Condition.CLOUDY_DAY : Condition.CLOUDY_NIGHT;
            case 45: case 48: return Condition.FOG;
            case 56: case 57: case 66: case 67: return Condition.SLEET;
            case 71: case 73: case 75: case 77: case 85: case 86: return Condition.SNOW;
            case 95: case 96: case 99: return Condition.THUNDERSTORM;
            default: return code >= 51 && code <= 82 ? Condition.RAIN : (day ? Condition.CLOUDY_DAY : Condition.CLOUDY_NIGHT);
        }
    }

    private static double at(Map<String, Object> block, String key, int i) {
        List<Object> values = Json.children(block, key);
        if (values == null || i >= values.size()) return 0;
        Object v = values.get(i);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private static double num(Map<String, Object> o, String key, double fallback) {
        Double v = o == null ? null : Json.number(o, key);
        return v == null ? fallback : v;
    }

    private static String coord(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static final Map<String, String> STATES = new java.util.HashMap<>();
    static {
        String[] pairs = {
            "Alabama", "AL", "Alaska", "AK", "Arizona", "AZ", "Arkansas", "AR", "California", "CA", "Colorado", "CO",
            "Connecticut", "CT", "Delaware", "DE", "Florida", "FL", "Georgia", "GA", "Hawaii", "HI", "Idaho", "ID",
            "Illinois", "IL", "Indiana", "IN", "Iowa", "IA", "Kansas", "KS", "Kentucky", "KY", "Louisiana", "LA",
            "Maine", "ME", "Maryland", "MD", "Massachusetts", "MA", "Michigan", "MI", "Minnesota", "MN",
            "Mississippi", "MS", "Missouri", "MO", "Montana", "MT", "Nebraska", "NE", "Nevada", "NV",
            "New Hampshire", "NH", "New Jersey", "NJ", "New Mexico", "NM", "New York", "NY", "North Carolina", "NC",
            "North Dakota", "ND", "Ohio", "OH", "Oklahoma", "OK", "Oregon", "OR", "Pennsylvania", "PA",
            "Rhode Island", "RI", "South Carolina", "SC", "South Dakota", "SD", "Tennessee", "TN", "Texas", "TX",
            "Utah", "UT", "Vermont", "VT", "Virginia", "VA", "Washington", "WA", "West Virginia", "WV",
            "Wisconsin", "WI", "Wyoming", "WY", "District of Columbia", "DC",
        };
        for (int i = 0; i < pairs.length; i += 2) STATES.put(pairs[i], pairs[i + 1]);
    }
}
