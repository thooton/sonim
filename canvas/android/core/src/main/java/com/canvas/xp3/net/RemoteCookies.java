package com.canvas.xp3.net;

import com.xp3.common.Http;
import com.xp3.common.Json;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Keeps the service's Canvas cookie in memory for fifteen minutes, and never
 * anywhere else.
 *
 * Canvas has no student-issuable API token here, so the session cookie is the
 * credential, and a session cookie sitting in a file on a phone that gets lost
 * is a different kind of problem from one held for a quarter of an hour in the
 * memory of a running app. It is fetched on first use, and an expired entry is
 * dropped rather than tried: a stale cookie's failure mode is a login page
 * served with a 200, which is the one response that would otherwise look like
 * data.
 *
 * The clock is monotonic, so a phone correcting its wall clock over the network
 * cannot make a fresh cookie look fifteen minutes old or an old one look new.
 */
public final class RemoteCookies {

    private static final long CACHE_NANOS = TimeUnit.MINUTES.toNanos(15);

    private final URI url;
    private final String token;
    private final Http http;
    private final LongSupplier nanoTime;

    private String cached;
    private long fetchedAt;

    public RemoteCookies(Config config, Http http) {
        this(config.cookieService(), config.cookieToken(), http, () -> System.nanoTime());
    }

    RemoteCookies(URI url, String token, Http http, LongSupplier nanoTime) {
        this.url = url;
        this.token = token;
        this.http = http;
        this.nanoTime = nanoTime;
    }

    public synchronized void invalidate() {
        cached = null;
    }

    public synchronized String fetch() throws IOException, InterruptedException {
        if (cached != null && nanoTime.getAsLong() - fetchedAt < CACHE_NANOS) return cached;
        cached = null;
        Http.Request request = Http.Request.to(url)
            .timeout(30000)
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache, no-store");
        Http.Response response = http.send(request);
        int status = response.status();
        if (status == 401) {
            throw new IOException("The cookie service rejected its token; check it in Settings");
        }
        if (status == 403) {
            throw new IOException("The cookie service refused access; check its permissions");
        }
        if (status == 503) {
            throw new IOException("The cookie service is not ready; its own Canvas login may need renewing");
        }
        if (status != 200) {
            throw new IOException("Cookie service HTTP " + status + "; no Canvas request was sent");
        }
        if (!response.header("Content-Type").toLowerCase(Locale.US).contains("application/json")
            || response.body().length() > 100000) {
            throw new IOException("The cookie service replied with something else; no Canvas request was sent");
        }
        String cookie;
        try {
            cookie = Cookies.parse(Json.string(Json.object(Json.parse(response.body())), "cookie"));
            if (cookie.length() > 65536 || !hasSession(cookie)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            // Never put a malformed response, which may carry a credential, into
            // a message that ends up on screen or in a tool result.
            throw new IOException("The cookie service returned an invalid Canvas cookie; no Canvas request was sent");
        }
        cached = cookie;
        fetchedAt = nanoTime.getAsLong();
        return cookie;
    }

    private static boolean hasSession(String cookie) {
        String[] parts = cookie.split(";");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].trim().matches("canvas_session=.+")) return true;
        }
        return false;
    }
}
