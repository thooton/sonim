package com.hbsa.xp3;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One post: when it went up, its caption, and its pictures. Nothing else is kept. */
public final class Post {

    public final String shortcode;
    public final String url;
    /** Epoch milliseconds. */
    public final long postedAt;
    public final String caption;
    /** The cover first, then the rest of a carousel when the page lists them. */
    public final List<String> images;
    /** How many items a carousel has (it may say more than `images` holds), or 0. */
    public final int carouselCount;

    public Post(String shortcode, String url, long postedAt, String caption, List<String> images, int carouselCount) {
        this.shortcode = shortcode;
        this.url = url;
        this.postedAt = postedAt;
        this.caption = caption == null ? "" : caption;
        this.images = Collections.unmodifiableList(new ArrayList<String>(images));
        this.carouselCount = carouselCount;
    }

    Map<String, Object> toJson() {
        return Json.map(
            "shortcode", shortcode,
            "url", url,
            "posted_at", (double) postedAt,
            "caption", caption,
            "images", new ArrayList<Object>(images),
            "carousel_count", (double) carouselCount);
    }

    static Post fromJson(Map<String, Object> json) {
        List<String> images = new ArrayList<String>();
        for (Object image : Json.children(json, "images")) {
            if (image instanceof String) images.add((String) image);
        }
        Double posted = Json.number(json, "posted_at");
        Double count = Json.number(json, "carousel_count");
        return new Post(
            Json.string(json, "shortcode"),
            Json.string(json, "url"),
            posted == null ? 0 : posted.longValue(),
            Json.text(json, "caption"),
            images,
            count == null ? 0 : count.intValue());
    }

    /** The list as saved between launches. */
    public static String write(List<Post> posts) {
        List<Object> out = new ArrayList<Object>();
        for (Post post : posts) out.add(post.toJson());
        return Json.write(out);
    }

    public static List<Post> read(String json) {
        List<Post> posts = new ArrayList<Post>();
        for (Object item : Json.array(Json.parse(json))) posts.add(fromJson(Json.object(item)));
        return posts;
    }
}
