package com.newyorker.xp3;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.newyorker.xp3.news.Site;
import com.xp3.common.Http;

import java.io.IOException;
import java.net.URI;

/**
 * Fetching pages and pictures.
 *
 * The shared client never follows a redirect, since every other app sends a
 * login with its requests; nothing here carries one, so a redirect within the
 * site (an article moved, a trailing slash) is followed by hand, a few hops
 * at most and never off the site.
 */
final class Net {

    /** A phone's browser, so the site sends the page it sends phones. */
    private static final String AGENT =
        "Mozilla/5.0 (Linux; Android 8.1.0; XP3800) AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/120.0 Mobile Safari/537.36";

    private static final Http HTTP = new Http();

    private Net() {}

    static String page(String url) throws IOException, InterruptedException {
        String at = url;
        for (int hop = 0; hop < 4; hop++) {
            Http.Response response = HTTP.send(Http.Request.to(URI.create(at))
                .header("User-Agent", AGENT)
                .header("Accept", "text/html")
                .header("Accept-Language", "en-US,en")
                .timeout(60000));
            int status = response.status();
            if (status >= 300 && status < 400 && !response.header("Location").isEmpty()) {
                String next = URI.create(at).resolve(response.header("Location")).toString();
                if (!next.startsWith("https://www.newyorker.com/")) break;
                at = next;
                continue;
            }
            if (status == 404) throw new IOException("This story is no longer on the site.");
            if (status < 200 || status >= 300) throw new IOException("The New Yorker answered " + status + ".");
            return response.body();
        }
        throw new IOException("The site sent this story elsewhere.");
    }

    /** A picture, scaled down on decoding so it is no wider than `width` pixels. */
    static Bitmap picture(String url, int width) throws IOException, InterruptedException {
        Http.Stream stream = HTTP.stream(Http.Request.to(URI.create(url))
            .header("User-Agent", AGENT)
            .timeout(30000));
        byte[] bytes;
        try {
            if (stream.status() != 200) throw new IOException("Picture " + stream.status());
            bytes = stream.bytes(4000000, "Picture too large");
        } finally {
            stream.close();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("Not a picture");
        return bitmap;
    }

    /** The feed's pictures are asked for at this width, and resized from it as needed. */
    static final int PICTURE_WIDTH = 320;

    static String sized(String url, int width) {
        String sized = Site.picture(url, width);
        return sized == null ? url : sized;
    }
}
