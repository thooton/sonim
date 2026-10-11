package com.canvas.xp3;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.ChatTurn;
import com.canvas.xp3.data.Store;
import com.canvas.xp3.data.Sync;
import com.canvas.xp3.net.Agent;
import com.canvas.xp3.net.Canvas;
import com.canvas.xp3.net.ClaudeAuth;
import com.canvas.xp3.net.ClaudeModel;
import com.canvas.xp3.net.CodexAuth;
import com.canvas.xp3.net.Config;
import com.canvas.xp3.net.Credentials;
import com.canvas.xp3.net.Http;
import com.canvas.xp3.net.Model;
import com.canvas.xp3.net.Tools;

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
 * The client, the agent and the conversation are all rebuilt when the settings
 * change. That is the simplest correct answer to a Canvas address or a key
 * being edited half way through a session, and it is cheap: none of them holds
 * anything but a cookie that should be dropped anyway when the account it
 * belongs to has been changed underneath it.
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
    private volatile Agent agent;

    Backend(File directory) {
        store = new Store(directory);
        config = store.settings();
        // Adopt a bundled setup on first launch. An explicitly supplied local
        // OAuth file also gets one adoption on an existing installation, which
        // is how installing a newly credentialed APK replaces an older
        // access-token-only setup. Its revision marker survives sign-out and
        // token rotation, so the stale copy in the APK is never re-imported.
        String bundledRevision = Defaults.revision();
        boolean revisedBundle = bundledRevision.length() > 0
            && !bundledRevision.equals(store.bundledRevision());
        if (Defaults.available() && (!store.hasSettings() || revisedBundle)) {
            try {
                Config bundled = Defaults.config();
                store.saveSettings(bundled);
                config = bundled;
                if (bundledRevision.length() > 0) {
                    try {
                        store.saveBundledRevision(bundledRevision);
                    } catch (IOException ignored) {
                        // The credentials are installed; at worst this bundle
                        // is adopted once more on the next process start.
                    }
                }
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
        // The agent being replaced owns a thread pool for its tool calls.
        if (agent != null) agent.shutdown();
        if (!config.canvasReady()) {
            canvas = null;
            sync = null;
            agent = null;
            return;
        }
        canvas = new Canvas(config, http);
        sync = new Sync(canvas);
        if (!config.chatReady()) {
            agent = null;
            return;
        }
        if (config.claude()) {
            Credentials credentials = new ClaudeAuth(http, config.accessToken(),
                config.refreshToken(), config.expiresAt(), new ClaudeAuth.Rotated() {
                    @Override
                    public void store(String access, String refresh, long expiresAt) {
                        claudeRotated(access, refresh, expiresAt);
                    }
                });
            agent = new Agent(
                new ClaudeModel(config, http, credentials),
                new Tools(canvas, config.zone())
            );
        } else {
            Credentials credentials = config.credentials(http, new CodexAuth.Rotated() {
                @Override
                public void store(String access, String refresh, String account) {
                    rotated(access, refresh, account);
                }
            });
            agent = new Agent(
                new Model(config, http, credentials),
                new Tools(canvas, config.zone())
            );
        }
    }

    /**
     * Writes down a renewed ChatGPT login.
     *
     * Called from whichever thread was making a model request, and deliberately
     * does not rebuild anything: the credentials object that just rotated is
     * the one the running agent is holding, and replacing the agent underneath
     * a turn in flight would abandon it. Only the settings on disk and the
     * config in memory need to catch up.
     */
    private synchronized void rotated(String access, String refresh, String account) {
        try {
            Config next = config
                .with(Config.ACCESS_TOKEN, access)
                .with(Config.REFRESH_TOKEN, refresh)
                .with(Config.ACCOUNT_ID, account);
            store.saveSettings(next);
            config = next;
        } catch (IOException e) {
            // The renewed token is live in memory either way; losing the copy
            // on disk costs one more renewal on the next launch.
        } catch (RuntimeException e) {
            // Same: never let bookkeeping break a turn that is working.
        }
    }

    /** Writes down a rotated Claude OAuth login without replacing a live turn. */
    private synchronized void claudeRotated(String access, String refresh, long expiresAt) {
        try {
            Config next = config
                .with(Config.ACCESS_TOKEN, access)
                .with(Config.REFRESH_TOKEN, refresh)
                .with(Config.EXPIRES_AT, String.valueOf(expiresAt));
            store.saveSettings(next);
            config = next;
        } catch (IOException e) {
            // The renewed credentials remain usable in memory for this run.
        } catch (RuntimeException e) {
            // Bookkeeping must not break a model response.
        }
    }

    ClaudeAuth.Login beginClaudeLogin() {
        return ClaudeAuth.begin();
    }

    /** Exchanges the browser result, saves it, and rebuilds chat for Claude. */
    void finishClaudeLogin(ClaudeAuth.Login login, String input)
        throws IOException, InterruptedException {
        final String[] values = new String[2];
        final long[] expiry = new long[1];
        ClaudeAuth.exchange(http, login, input, new ClaudeAuth.Rotated() {
            @Override
            public void store(String access, String refresh, long expiresAt) {
                values[0] = access;
                values[1] = refresh;
                expiry[0] = expiresAt;
            }
        });
        Config next = config
            .with(Config.ACCESS_TOKEN, values[0])
            .with(Config.REFRESH_TOKEN, values[1])
            .with(Config.EXPIRES_AT, String.valueOf(expiry[0]));
        saveSettings(next);
    }

    Config config() {
        return config;
    }

    boolean canvasReady() {
        return config.canvasReady();
    }

    boolean chatReady() {
        return config.chatReady();
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

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    List<ChatTurn> transcript() {
        return store.transcript();
    }

    void saveTranscript(List<ChatTurn> turns) {
        try {
            store.saveTranscript(turns);
        } catch (IOException e) {
            // The conversation is still on screen; losing its copy on disk is
            // not worth interrupting it for.
        }
    }

    /**
     * Puts a transcript read back from disk into the model's history, so the
     * first question after a relaunch is answered in the context the screen
     * above it is already showing. A compact boundary contributes its hidden
     * summary plus the newest exchanges; without one, only the newest exchanges
     * are restored so a long transcript is not resent in full.
     */
    void restore(List<ChatTurn> turns) {
        if (agent == null) return;
        agent.reset();
        int compactedAt = -1;
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).isCompaction()) {
                compactedAt = i;
                agent.seedCompaction(turns.get(i).text);
                break;
            }
        }
        int from = Math.max(0, turns.size() - RESTORED_TURNS);
        if (compactedAt >= from) from = compactedAt + 1;
        for (int i = from; i < turns.size(); i++) {
            ChatTurn turn = turns.get(i);
            if (!turn.isMessage()) continue;
            agent.seed(turn.fromUser, turn.text);
        }
    }

    private static final int RESTORED_TURNS = 10;

    void resetChat() {
        if (agent != null) agent.reset();
    }

    String ask(String question, Agent.Activity activity) throws IOException, InterruptedException {
        Agent current = agent;
        if (current == null) throw new IOException("No model is set up yet");
        return current.chat(question, activity);
    }

    String summarize(Agent.Activity activity) throws IOException, InterruptedException {
        Agent current = agent;
        if (current == null) throw new IOException("No model is set up yet");
        return current.summarize(activity);
    }
}
