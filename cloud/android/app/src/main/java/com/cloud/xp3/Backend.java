package com.cloud.xp3;

import com.cloud.xp3.data.Store;
import com.cloud.xp3.data.Transcript;
import com.cloud.xp3.net.ClaudeAuth;
import com.cloud.xp3.net.Cloud;
import com.cloud.xp3.net.Config;
import com.cloud.xp3.net.Http;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything with a network or a disk behind it, in one place.
 *
 * The cloud client is built once, from the login baked into the build. A
 * rotated login is written back so the next launch starts from the live pair.
 */
final class Backend {

    static final String NO_LOGIN =
        "This build has no Claude login. Put claude-auth.json beside the android folder and rebuild.";

    private final Store store;
    /** Null when this build has no Claude login. */
    private final Cloud cloud;

    Backend(File directory) {
        store = new Store(directory);
        Config config = login();
        if (!config.ready()) {
            cloud = null;
            return;
        }
        Http http = new Http();
        ClaudeAuth credentials = new ClaudeAuth(http, config.accessToken(),
            config.refreshToken(), config.expiresAt(), new ClaudeAuth.Rotated() {
                @Override
                public void store(String access, String refresh, long expiresAt) {
                    rotated(access, refresh, expiresAt);
                }
            });
        cloud = new Cloud(http, credentials);
    }

    /**
     * The login to use: the saved one, unless this APK carries a bundle it has
     * not adopted yet. The revision marker is what stops the stale copy in the
     * APK replacing a login that has rotated since it was installed.
     */
    private Config login() {
        String revision = Defaults.revision();
        if (!Defaults.available() || revision.equals(store.bundledRevision())) {
            return store.settings();
        }
        Config bundled;
        try {
            bundled = Defaults.config();
        } catch (RuntimeException e) {
            return store.settings();
        }
        try {
            store.saveSettings(bundled);
            store.saveBundledRevision(revision);
        } catch (IOException e) {
            // Still good for this run; the next launch adopts it again.
        }
        return bundled;
    }

    /**
     * Writes down a rotated login. Called on a network worker in the middle of
     * a request; the old refresh token no longer works, so losing this copy
     * would leave the next launch with a dead login.
     */
    private void rotated(String access, String refresh, long expiresAt) {
        try {
            store.saveSettings(Config.of(access, refresh, expiresAt));
        } catch (IOException e) {
            // The renewed login remains usable in memory for this run.
        } catch (RuntimeException e) {
            // Bookkeeping must not break a request.
        }
    }

    boolean ready() {
        return cloud != null;
    }

    // ------------------------------------------------------------------
    // The saved session
    // ------------------------------------------------------------------

    Store.Saved session() {
        return store.session();
    }

    /** Worker thread; callers pass a snapshot, never the live transcript. */
    void save(String id, String title, Transcript snapshot) {
        try {
            store.saveSession(id, title, snapshot);
        } catch (IOException e) {
            // Still on screen and in memory; the next change tries again.
        }
    }

    // ------------------------------------------------------------------
    // The cloud. All blocking; call from a worker.
    // ------------------------------------------------------------------

    String create(String title, String uuid, String text, List<File> pictures)
        throws IOException, InterruptedException {
        Cloud cloud = cloud();
        return cloud.create(title, uuid, text, read(pictures));
    }

    void send(String session, String uuid, String text, List<File> pictures)
        throws IOException, InterruptedException {
        Cloud cloud = cloud();
        cloud.send(session, uuid, text, read(pictures));
    }

    private static List<byte[]> read(List<File> pictures) throws IOException {
        List<byte[]> jpegs = new ArrayList<byte[]>();
        for (File picture : pictures) {
            try {
                jpegs.add(Pictures.read(picture));
            } catch (IOException e) {
                throw new IOException("A picture could not be read back");
            }
        }
        return jpegs;
    }

    void interrupt(String session) throws IOException, InterruptedException {
        cloud().interrupt(session);
    }

    void answer(String session, String requestId, boolean allow, Map<String, Object> input)
        throws IOException, InterruptedException {
        cloud().answer(session, requestId, allow, input);
    }

    /** A link for the session, not yet started. Null without a login. */
    Link link(String session, long after, Link.Listener listener) {
        return cloud == null ? null : new Link(cloud, session, after, listener);
    }

    private Cloud cloud() throws IOException {
        if (cloud == null) throw new IOException(NO_LOGIN);
        return cloud;
    }
}
