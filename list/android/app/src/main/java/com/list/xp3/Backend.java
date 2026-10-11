package com.list.xp3;

import com.list.xp3.data.ChatTurn;
import com.list.xp3.data.Checklist;
import com.list.xp3.data.Item;
import com.list.xp3.data.Store;
import com.list.xp3.net.Agent;
import com.list.xp3.net.ClaudeAuth;
import com.list.xp3.net.ClaudeModel;
import com.list.xp3.net.Config;
import com.list.xp3.net.Http;
import com.list.xp3.net.ListTools;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Everything with a network or a disk behind it, in one place.
 *
 * The checklist itself lives here too, because two threads edit it: the
 * person from the main thread and the assistant from the chat worker. Both go
 * through the same Checklist object, whose version number is what lets a
 * stale `set_list` be refused.
 *
 * The agent is built once, from the login baked into the build. A rotated
 * login is written back so the next launch starts from the live pair.
 */
final class Backend {

    /** Where the assistant's edits are announced; called on the chat worker. */
    interface ListListener {
        void changedByAssistant();
    }

    private static final int RESTORED_TURNS = 10;
    static final String NO_LOGIN =
        "This build has no Claude login. Put claude-auth.json beside the android folder and rebuild.";

    private final Store store;
    private final Checklist checklist;
    private final ListTools tools;
    /** Null when this build has no Claude login. */
    private final Agent agent;

    Backend(File directory, final ListListener listener) {
        store = new Store(directory);
        checklist = new Checklist(store.list());
        tools = new ListTools(checklist, new Runnable() {
            @Override
            public void run() {
                listener.changedByAssistant();
            }
        });
        Config config = login();
        if (!config.chatReady()) {
            agent = null;
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
        agent = new Agent(new ClaudeModel(http, credentials), tools);
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
     * Writes down a rotated login. Called on the chat worker in the middle of
     * a request; the old refresh token no longer works, so losing this copy
     * would leave the next launch with a dead login.
     */
    private void rotated(String access, String refresh, long expiresAt) {
        try {
            store.saveSettings(Config.of(access, refresh, expiresAt));
        } catch (IOException e) {
            // The renewed login remains usable in memory for this run.
        } catch (RuntimeException e) {
            // Bookkeeping must not break a model response.
        }
    }

    boolean chatReady() {
        return agent != null;
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    Checklist checklist() {
        return checklist;
    }

    /** Writes the list down. Worker thread; callers pass a snapshot. */
    void saveList(List<Item> items) {
        try {
            store.saveList(items);
        } catch (IOException e) {
            // The list is still on screen and in memory; the next change will
            // try again.
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
     * Puts a transcript back into the model's history, so the next message is
     * answered in the context the screen is already showing. A compact
     * boundary contributes its hidden summary plus the newest exchanges;
     * without one, only the newest exchanges are restored.
     *
     * Resetting also makes the tools forget the model's last read, and makes
     * any turn still in flight unable to touch the list.
     */
    void restore(List<ChatTurn> turns) {
        tools.forget();
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

    String ask(String message, Agent.Activity activity) throws IOException, InterruptedException {
        if (agent == null) throw new IOException(NO_LOGIN);
        return agent.chat(message, activity);
    }

    String summarize() throws IOException, InterruptedException {
        if (agent == null) throw new IOException(NO_LOGIN);
        return agent.summarize(Agent.Activity.NOOP);
    }
}
