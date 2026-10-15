package com.canvas.xp3;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Store;
import com.canvas.xp3.data.Sync;
import com.canvas.xp3.net.Canvas;
import com.canvas.xp3.net.Config;
import com.canvas.xp3.net.Http;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Everything with a network or a disk behind it, in one place.
 *
 * The screens above this are drawn on the main thread and nothing here may be
 * called from it except the settings and the cached read, which is why those
 * two are the only methods that do not throw IOException: the compiler ends up
 * enforcing most of the rule.
 *
 * The client is rebuilt when the settings change. That is the simplest
 * correct answer to a Canvas address or a token being edited half way through
 * a session, and it is cheap: it holds nothing but a cookie that should be
 * dropped anyway when the account it belongs to has been changed underneath it.
 */
final class Backend {

    private final Store store;
    private final Http http = new Http();

    // Written on the main thread when the settings change, read on the workers.
    // Each background method takes its own copy first, so a save landing part
    // way through a fetch means that fetch finishes against the client it
    // started with rather than against half of each.
    private volatile Config config;
    private volatile Canvas canvas;
    private volatile Sync sync;

    Backend(File directory) {
        store = new Store(directory);
        // Versions with the chat kept a conversation and a model login here;
        // neither is used any more, and the login should not linger.
        store.forgetChat();
        config = store.settings();
        // Adopt a bundled setup on first launch only, so signing out of it
        // stays signed out.
        if (Defaults.available() && !store.hasSettings()) {
            try {
                Config bundled = Defaults.config();
                store.saveSettings(bundled);
                config = bundled;
            } catch (IOException e) {
                // Could not write it down; the values are still good for this
                // run, and the next launch will simply try again.
                config = safeDefaults();
            } catch (RuntimeException e) {
                // A bundled setup this version cannot read is not a reason to
                // refuse to start.
                config = Config.blank();
            }
        }
        build();
    }

    private static Config safeDefaults() {
        try {
            return Defaults.config();
        } catch (RuntimeException e) {
            return Config.blank();
        }
    }

    /** Whether this build has a setup baked into it at all. */
    boolean hasBundledSetup() {
        return Defaults.available();
    }

    /** Puts the bundled setup back, after signing out or editing something wrong. */
    void restoreBundledSetup() throws IOException {
        if (!Defaults.available()) return;
        saveSettings(Defaults.config());
    }

    private void build() {
        if (!config.canvasReady()) {
            canvas = null;
            sync = null;
            return;
        }
        canvas = new Canvas(config, http);
        sync = new Sync(canvas);
    }

    Config config() {
        return config;
    }

    boolean canvasReady() {
        return config.canvasReady();
    }

    /** Saves settings and rebuilds everything that was built from the old ones. */
    void saveSettings(Config next) throws IOException {
        store.saveSettings(next);
        config = next;
        build();
    }

    /**
     * Forgets the account.
     *
     * A blank settings file is written rather than none at all, so that a build
     * with a setup baked into it stays signed out instead of quietly signing
     * itself back in on the next launch. Settings offers a way back.
     */
    void signOut() {
        store.clear();
        config = Config.blank();
        try {
            store.saveSettings(config);
        } catch (IOException e) {
            // Nothing more to do: the credentials are gone from memory and the
            // files are deleted, which is what was actually asked for.
        }
        build();
    }

    // ------------------------------------------------------------------
    // Work
    // ------------------------------------------------------------------

    /** The last sync as it was written down, or null. Safe on the main thread. */
    Sync.Result cached() {
        return store.cache();
    }

    /**
     * A fresh sync. Deliberately does not write the cache.
     *
     * What goes on disk is the list as the screen ends up holding it, which is
     * not the same thing as what came back from Canvas: instructions fetched
     * earlier are carried across onto the new records first, and a save from
     * here would land before that and drop every one of them. So the caller
     * saves, once, when it has finished deciding what the list is.
     */
    Sync.Result refresh() throws IOException, InterruptedException {
        Sync current = sync;
        if (current == null) throw new IOException("Canvas is not set up yet");
        return current.run();
    }

    /** One assignment's instructions, stored on it. */
    String details(Assignment item) throws IOException, InterruptedException {
        Sync current = sync;
        if (current == null) throw new IOException("Canvas is not set up yet");
        String markdown = current.details(item);
        item.setDetails(markdown);
        return markdown;
    }

    /**
     * Writes the work list down as it currently stands.
     *
     * Every caller passes a copy taken on the main thread, so this is always a
     * snapshot of what somebody could actually see rather than of whatever two
     * background fetches happened to have finished.
     */
    void saveWork(List<Assignment> work, Sync.Result context) {
        long fetchedAt = context == null ? System.currentTimeMillis() : context.fetchedAt;
        List<String> unavailable = context == null
            ? new java.util.ArrayList<String>()
            : context.unavailable;
        try {
            store.saveCache(new Sync.Result(work, unavailable, fetchedAt));
        } catch (IOException e) {
            // A cache that could not be written is a slower launch next time,
            // not a reason to disturb what is on screen.
        }
    }
}
