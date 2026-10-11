package com.canvas.xp3.net;

import java.io.IOException;

/**
 * What goes in the Authorization header, fetched at the moment it is needed.
 *
 * An API key is a constant and could have been a string. A ChatGPT login is
 * not: its access token lasts about ten days and has to be renewed from a
 * refresh token, which means asking for one can involve a network round trip
 * and can fail. Everything downstream therefore asks for the token per request
 * rather than being handed one at construction.
 */
public interface Credentials {

    /** The bearer token, renewing it first if it is due to expire. */
    String token() throws IOException, InterruptedException;

    /** The ChatGPT account id, or null for a plain API key. */
    String account() throws IOException, InterruptedException;

    /**
     * Called when the service rejected the token, so the next request renews
     * rather than repeating a credential that has just been refused.
     */
    void invalidate();

    /** A key that never changes and never needs the network. */
    final class ApiKey implements Credentials {

        private final String key;

        public ApiKey(String key) {
            this.key = key;
        }

        @Override
        public String token() {
            return key;
        }

        @Override
        public String account() {
            return null;
        }

        @Override
        public void invalidate() {
            // There is nothing to renew: a key that is refused stays refused.
        }
    }
}
