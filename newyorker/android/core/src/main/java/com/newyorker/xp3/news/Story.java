package com.newyorker.xp3.news;

import com.xp3.common.Json;

import java.util.Map;

/** One story as a list shows it: where it is, and what its card says. Fields may be "". */
public final class Story {

    public final String url;
    public final String rubric;
    public final String hed;
    public final String dek;
    public final String byline;
    /** A picture at the phone's size, or "". */
    public final String image;

    public Story(String url, String rubric, String hed, String dek, String byline, String image) {
        this.url = url;
        this.rubric = orEmpty(rubric);
        this.hed = orEmpty(hed);
        this.dek = orEmpty(dek);
        this.byline = orEmpty(byline);
        this.image = orEmpty(image);
    }

    static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    public Map<String, Object> toJson() {
        return Json.map("url", url, "rubric", rubric, "hed", hed, "dek", dek, "byline", byline, "image", image);
    }

    public static Story fromJson(Map<String, Object> json) {
        return new Story(Json.string(json, "url"), Json.text(json, "rubric"), Json.text(json, "hed"),
            Json.text(json, "dek"), Json.text(json, "byline"), Json.text(json, "image"));
    }
}
