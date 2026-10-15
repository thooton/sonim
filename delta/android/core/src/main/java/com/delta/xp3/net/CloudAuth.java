package com.delta.xp3.net;

import java.io.IOException;

/**
 * One cloud account's access token, fetched from the command center.
 *
 * The phone holds no login of its own. A subscription login's refresh token
 * is replaced every time it is used, so only one party may renew it, and that
 * is the command center: this asks it for a token, keeps it until shortly
 * before it expires, and asks again. After the cloud refuses a token, the
 * next request names it, so the command center renews the login rather than
 * handing the same token back (unless someone else has renewed it already).
 */
public final class CloudAuth {

    /** Where tokens come from: the command center, or a fixed one in tests. */
    public interface Source {
        /** A token, renewed first if `refused` (or null) is still the current one. */
        Grant fetch(String refused) throws IOException, InterruptedException;
    }

    /** An access token and when it expires, in epoch milliseconds (0 if unknown). */
    public static final class Grant {
        public final String access;
        public final long expiresAt;

        public Grant(String access, long expiresAt) {
            this.access = access;
            this.expiresAt = expiresAt;
        }
    }

    private static final long SKEW_MS = 5L * 60L * 1000L;

    private final Source source;
    private String access;
    private long expiresAt;
    /** The token the cloud turned down, until a new one has been fetched. */
    private String refused;

    public CloudAuth(Source source) {
        this.source = source;
    }

    /** The tokens of the account `account`, from the command center. */
    public static CloudAuth of(final Center center, final String account) {
        return new CloudAuth(new Source() {
            @Override
            public Grant fetch(String refused) throws IOException, InterruptedException {
                return center.token(account, refused);
            }
        });
    }

    /** Always the same token, which never expires. */
    public static CloudAuth fixed(final String token) {
        return new CloudAuth(new Source() {
            @Override
            public Grant fetch(String refused) {
                return new Grant(token, 0);
            }
        });
    }

    /** The bearer token, fetching a new one first if it is due to expire or was refused. */
    public synchronized String token() throws IOException, InterruptedException {
        boolean good = access != null && refused == null
            && (expiresAt <= 0 || System.currentTimeMillis() < expiresAt - SKEW_MS);
        if (good) return access;
        Grant grant = source.fetch(refused);
        access = grant.access;
        expiresAt = grant.expiresAt;
        refused = null;
        return access;
    }

    /** Called after a 401, so the retry asks for a renewed token rather than repeating a refused one. */
    public synchronized void invalidate() {
        if (access != null) refused = access;
    }
}
