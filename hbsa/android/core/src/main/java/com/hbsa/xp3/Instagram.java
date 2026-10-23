package com.hbsa.xp3;

import com.xp3.common.Http;
import com.xp3.common.Json;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The club's latest posts, read from its public profile without logging in,
 * as instagram_scraper.py does.
 *
 * Logged out, Instagram renders the 12 most recent posts into the profile
 * page as a Relay preloader payload: a `<script type="application/json">`
 * holding `polaris_ordered_timeline_connection`. If the page comes back
 * without it (Instagram sometimes sends a stripped page), the web profile
 * API the site itself calls is tried before giving up.
 */
public final class Instagram {

    public static final String ACCOUNT = "hbsaasu";
    public static final String PROFILE = "https://www.instagram.com/" + ACCOUNT + "/";
    static final String API = "https://www.instagram.com/api/v1/users/web_profile_info/?username=" + ACCOUNT;

    /** The script's desktop Chrome: without these headers the page has no posts in it. */
    static final String[][] HEADERS = {
        {"User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"},
        {"Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"},
        {"Accept-Language", "en-US,en;q=0.9"},
        {"Sec-Fetch-Dest", "document"},
        {"Sec-Fetch-Mode", "navigate"},
        {"Sec-Fetch-Site", "none"},
        {"Sec-Fetch-User", "?1"},
        {"Upgrade-Insecure-Requests", "1"},
        {"sec-ch-ua", "\"Chromium\";v=\"140\", \"Not=A?Brand\";v=\"24\""},
        {"sec-ch-ua-mobile", "?0"},
        {"sec-ch-ua-platform", "\"Linux\""},
    };
    /** The web app's own id, which the profile API wants. */
    private static final String APP_ID = "936619743392459";

    /** Media ids are snowflakes: the top 41 bits are milliseconds since this. */
    static final long EPOCH_MS = 1314220021721L;

    private static final Pattern JSON_SCRIPT =
        Pattern.compile("<script type=\"application/json\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final String TIMELINE = "polaris_ordered_timeline_connection";

    private Instagram() {}

    /** The latest posts, newest first. */
    public static List<Post> fetch(Http http) throws IOException, InterruptedException {
        String failure;
        try {
            List<Post> posts = parsePage(page(http));
            if (posts != null) return posts;
            failure = "Instagram sent a page without the posts.";
        } catch (IOException e) {
            failure = e.getMessage();
        }
        try {
            return parseApi(api(http));
        } catch (IOException | RuntimeException e) {
            throw new IOException(failure);
        }
    }

    private static String page(Http http) throws IOException, InterruptedException {
        Http.Request request = Http.Request.to(URI.create(PROFILE)).timeout(45000);
        for (String[] header : HEADERS) request.header(header[0], header[1]);
        return answer(http.send(request));
    }

    private static String api(Http http) throws IOException, InterruptedException {
        return answer(http.send(Http.Request.to(URI.create(API))
            .header("User-Agent", HEADERS[0][1])
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-IG-App-ID", APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", PROFILE)
            .timeout(45000)));
    }

    private static String answer(Http.Response response) throws IOException {
        int status = response.status();
        if (status == 429) throw new IOException("Instagram is limiting requests. Try again in a while.");
        if (status >= 300 && status < 400 || status == 401 || status == 403) {
            throw new IOException("Instagram asked for a login. This usually passes after a while.");
        }
        if (status == 404) throw new IOException("@" + ACCOUNT + " wasn't found.");
        if (status < 200 || status >= 300) throw new IOException("Instagram answered " + status + ".");
        return response.body();
    }

    /** The posts in a profile page, newest first; null if the page has none in it. */
    public static List<Post> parsePage(String html) {
        Matcher match = JSON_SCRIPT.matcher(html);
        while (match.find()) {
            String blob = match.group(1);
            if (!blob.contains(TIMELINE)) continue;
            Object timeline;
            try {
                timeline = find(Json.parse(blob), TIMELINE);
            } catch (RuntimeException e) {
                continue;
            }
            if (!(timeline instanceof Map)) continue;
            List<Post> posts = new ArrayList<Post>();
            for (Object edge : Json.children(Json.object(timeline), "edges")) {
                if (!(edge instanceof Map)) continue;
                Post post = fromNode(Json.child(Json.object(edge), "node"));
                if (post != null) posts.add(post);
            }
            return newestFirst(posts);
        }
        return null;
    }

    /** A timeline node, as the page's payload has it. */
    static Post fromNode(Map<String, Object> node) {
        String code = Json.text(node, "code");
        if (code == null || code.isEmpty()) return null;
        long posted = snowflake(node.get("pk"));
        if (posted == 0) {
            Double taken = Json.number(node, "taken_at");
            if (taken != null) posted = taken.longValue() * 1000;
        }
        boolean reel = "clips".equals(Json.text(node, "product_type"));
        String caption = Json.text(Json.child(node, "caption"), "text");

        List<String> images = new ArrayList<String>();
        add(images, image(node));
        for (Object item : Json.children(node, "carousel_media")) {
            if (item instanceof Map) add(images, image(Json.object(item)));
        }
        Double count = Json.number(node, "carousel_media_count");
        return new Post(code, "https://www.instagram.com/" + (reel ? "reel" : "p") + "/" + code + "/",
            posted, caption, images, count == null ? 0 : count.intValue());
    }

    /** `display_uri`, or failing that the first of `image_versions2`'s candidates. */
    private static String image(Map<String, Object> node) {
        String uri = Json.text(node, "display_uri");
        if (uri != null && !uri.isEmpty()) return uri;
        List<Object> candidates = Json.children(Json.child(node, "image_versions2"), "candidates");
        if (!candidates.isEmpty() && candidates.get(0) instanceof Map) {
            return Json.text(Json.object(candidates.get(0)), "url");
        }
        return null;
    }

    /** The posts in the profile API's answer, newest first. */
    public static List<Post> parseApi(String json) {
        Map<String, Object> user = Json.child(Json.child(Json.object(Json.parse(json)), "data"), "user");
        Map<String, Object> media = Json.child(user, "edge_owner_to_timeline_media");
        if (!media.containsKey("edges")) throw new IllegalArgumentException("No posts in the answer");
        List<Post> posts = new ArrayList<Post>();
        for (Object edge : Json.children(media, "edges")) {
            if (!(edge instanceof Map)) continue;
            Map<String, Object> node = Json.child(Json.object(edge), "node");
            String code = Json.text(node, "shortcode");
            if (code == null || code.isEmpty()) continue;
            Double taken = Json.number(node, "taken_at_timestamp");
            String caption = null;
            List<Object> captions = Json.children(Json.child(node, "edge_media_to_caption"), "edges");
            if (!captions.isEmpty() && captions.get(0) instanceof Map) {
                caption = Json.text(Json.child(Json.object(captions.get(0)), "node"), "text");
            }
            List<String> images = new ArrayList<String>();
            add(images, Json.text(node, "display_url"));
            List<Object> children = Json.children(Json.child(node, "edge_sidecar_to_children"), "edges");
            for (Object child : children) {
                if (child instanceof Map) add(images, Json.text(Json.child(Json.object(child), "node"), "display_url"));
            }
            boolean reel = "clips".equals(Json.text(node, "product_type"));
            posts.add(new Post(code, "https://www.instagram.com/" + (reel ? "reel" : "p") + "/" + code + "/",
                taken == null ? 0 : taken.longValue() * 1000, caption, images, children.size()));
        }
        return newestFirst(posts);
    }

    private static void add(List<String> images, String url) {
        if (url != null && !url.isEmpty() && !images.contains(url)) images.add(url);
    }

    /** Pinned posts come first on the profile whatever their age, so sort by date. */
    private static List<Post> newestFirst(List<Post> posts) {
        Collections.sort(posts, (a, b) -> Long.compare(b.postedAt, a.postedAt));
        return posts;
    }

    /** When a media id was made, or 0 if it isn't one. */
    static long snowflake(Object pk) {
        if (!(pk instanceof String)) return 0;
        String id = ((String) pk).trim();
        int cut = id.indexOf('_');
        if (cut >= 0) id = id.substring(0, cut);
        try {
            return (new java.math.BigInteger(id).shiftRight(23).longValue()) + EPOCH_MS;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Depth-first search for the first value stored under `key`. */
    static Object find(Object value, String key) {
        Iterable<?> children;
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.containsKey(key) && map.get(key) != null) return map.get(key);
            children = map.values();
        } else if (value instanceof List) {
            children = (List<?>) value;
        } else {
            return null;
        }
        for (Object child : children) {
            Object found = find(child, key);
            if (found != null) return found;
        }
        return null;
    }
}
