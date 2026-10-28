package com.newyorker.xp3.news;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The site's addresses: which links are articles, and pictures at the phone's size. */
public final class Site {

    public static final String HOME = "https://www.newyorker.com/";
    public static final String MAGAZINE = "https://www.newyorker.com/magazine";

    private static final String HOST = "www.newyorker.com";

    /** Sections whose pages are not articles to read: sound, games, video, cartoon and photo galleries. */
    private static final String[] NOT_ARTICLES = {
        "/podcast", "/podcasts", "/audio", "/video", "/videos", "/cartoons", "/cartoon",
        "/gallery",
        "/puzzles-and-games-dept", "/crossword", "/crossword-puzzles-and-games", "/games",
        "/newsletter", "/newsletters", "/events", "/account", "/subscribe", "/search",
        "/contributors", "/about", "/tag", "/store", "/shop", "/sitemap", "/plus",
    };

    private Site() {}

    /**
     * The link made absolute on the site, without query or fragment, or null
     * when it does not lead to an article: elsewhere, a section front, or a
     * page of sound, games or video.
     */
    public static String article(String href) {
        if (href == null) return null;
        String value = href.trim();
        if (value.isEmpty()) return null;
        URI uri;
        try {
            uri = URI.create(HOME).resolve(value.replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
        if (!scheme.equals("https") && !scheme.equals("http")) return null;
        if (!host.equals(HOST) && !host.equals("newyorker.com")) return null;
        String path = uri.getRawPath();
        if (path == null) return null;
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String lower = path.toLowerCase(Locale.US);
        for (String section : NOT_ARTICLES) {
            if (lower.equals(section) || lower.startsWith(section + "/")) return null;
        }
        // An article is at least a section and a slug: /news/the-lede/some-story.
        int segments = 0;
        for (String part : lower.split("/")) if (!part.isEmpty()) segments++;
        if (segments < 2) return null;
        // An issue's own page, /magazine/2026/10/26, is a list, not an article.
        if (lower.matches("/magazine/\\d{4}/\\d{2}/\\d{2}")) return null;
        return "https://" + HOST + path;
    }

    private static final Pattern WIDTH = Pattern.compile("/w_\\d+(%2C|,)");

    /**
     * A picture from the site's image service at `width` pixels wide, which
     * it scales on request; the pages ask for 1600, a hundred times the bytes
     * the phone's 240-pixel screen can show. Anything else (a data: URI, an
     * SVG logo) is null.
     */
    public static String picture(String src, int width) {
        if (src == null) return null;
        String value = src.trim();
        if (value.startsWith("//")) value = "https:" + value;
        if (!value.startsWith("https://") && !value.startsWith("http://")) return null;
        if (value.toLowerCase(Locale.US).contains(".svg")) return null;
        Matcher matcher = WIDTH.matcher(value);
        if (matcher.find()) {
            return value.substring(0, matcher.start()) + "/w_" + width + matcher.group(1) + value.substring(matcher.end());
        }
        return value;
    }

    /** "/magazine/2026/10/26/..." as "October 26, 2026", or null for a link that is not in an issue. */
    public static String issueDate(String url) {
        if (url == null) return null;
        Matcher matcher = Pattern.compile("/magazine/(\\d{4})/(\\d{2})/(\\d{2})").matcher(url);
        if (!matcher.find()) return null;
        int month = Integer.parseInt(matcher.group(2));
        if (month < 1 || month > 12) return null;
        String[] months = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};
        return months[month - 1] + " " + Integer.parseInt(matcher.group(3)) + ", " + matcher.group(1);
    }
}
