package com.delta.xp3.data;

import com.delta.xp3.net.Config;
import com.xp3.common.Disk;
import com.xp3.common.Json;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the app writes down, in its own private directory: the command center
 * login, which session is open, the last list of sessions, a transcript per
 * session, which cloud accounts are out until when, the messages waiting for
 * a limit to lift, and which command-center files have been downloaded.
 *
 * A transcript could be rebuilt by replaying the session's whole event stream,
 * but on a phone that is a lot of sandbox logs to download every time a
 * session is opened. Saving it, with the sequence number it reached, means
 * opening one only fetches what happened since. Only the most recent few are
 * kept; an older one is replayed from the start if it is opened again.
 *
 * Every read is defensive. A file that is missing, truncated by a battery pull
 * or written by an older version is treated as absent rather than as an error.
 * Writes go to a temporary file and are renamed into place, so an interrupted
 * write leaves the previous good copy rather than half of a new one.
 */
public final class Store {

    /** Which login a bundled revision marker belongs to. */
    public static final String CENTER = "center";

    private static final String SETTINGS = "settings.json";
    private static final String LIMITS = "limits.json";
    private static final String QUEUE = "queue.json";
    private static final String OPEN = "open.txt";
    private static final String CHATS = "chats.json";
    private static final String DOWNLOADS = "downloads.json";
    private static final String SESSIONS = "sessions";

    private static final long MAX_FILE = 4000000;

    /** A session as saved: an unknown one is a null title and an empty transcript. */
    public static final class Saved {
        public final String id;
        public final String title;
        /** The cloud account it runs on, or null if that was not known when it was saved. */
        public final String account;
        public final Transcript transcript;

        public Saved(String id, String title, Transcript transcript) {
            this(id, title, null, transcript);
        }

        public Saved(String id, String title, String account, Transcript transcript) {
            this.id = id;
            this.title = title;
            this.account = account;
            this.transcript = transcript;
        }
    }

    private final File directory;

    public Store(File directory) {
        this.directory = directory;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    public Config settings() {
        String json = read(SETTINGS);
        if (json == null) return Config.blank();
        try {
            return Config.parse(json);
        } catch (RuntimeException e) {
            return Config.blank();
        }
    }

    public void saveSettings(Config config) throws IOException {
        write(SETTINGS, config.toJson(), true);
    }

    /** The bundled login of kind `part` already adopted by this installation, if any. */
    public String bundledRevision(String part) {
        String revision = read(revisionFile(part));
        return revision == null ? "" : revision.trim();
    }

    public void saveBundledRevision(String part, String revision) throws IOException {
        write(revisionFile(part), revision == null ? "" : revision.trim(), false);
    }

    private static String revisionFile(String part) {
        if (!CENTER.equals(part)) throw new IllegalArgumentException("Unknown login");
        return "bundled-" + part + ".txt";
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    /** The session left open, or null for a new one not yet started. */
    public String openSession() {
        String id = read(OPEN);
        id = id == null ? null : id.trim();
        return Chat.validId(id) ? id : null;
    }

    public void saveOpenSession(String id) throws IOException {
        write(OPEN, Chat.validId(id) ? id : "", false);
    }

    /** The Chats tab as it was last fetched, for showing before the network answers. */
    public List<Chat> chats() {
        List<Chat> chats = new ArrayList<Chat>();
        String json = read(CHATS);
        if (json == null) return chats;
        try {
            for (Object value : Json.array(Json.parse(json))) {
                if (!(value instanceof Map)) continue;
                Map<String, Object> saved = Json.object(value);
                String id = Json.text(saved, "id");
                if (!Chat.validId(id)) continue;
                Double updated = Json.number(saved, "updated");
                chats.add(new Chat(id, Json.text(saved, "title"), Json.text(saved, "status"),
                    updated == null ? 0 : updated.longValue(), Json.text(saved, "account")));
            }
        } catch (RuntimeException e) {
            chats.clear();
        }
        return chats;
    }

    public void saveChats(List<Chat> chats) throws IOException {
        List<Object> saved = new ArrayList<Object>();
        for (Chat chat : chats) {
            Map<String, Object> entry = Json.map("id", chat.id, "title", chat.title, "status", chat.status,
                "updated", Long.valueOf(chat.updated));
            if (chat.account != null) entry.put("account", chat.account);
            saved.add(entry);
        }
        write(CHATS, Json.write(saved), false);
    }

    public Saved session(String id) {
        if (!Chat.validId(id)) return new Saved(null, null, new Transcript());
        String json = read(SESSIONS + "/" + id + ".json");
        if (json == null) return new Saved(id, null, new Transcript());
        try {
            Map<String, Object> saved = Json.object(Json.parse(json));
            if (!id.equals(Json.text(saved, "id"))) return new Saved(id, null, new Transcript());
            List<Entry> entries = new ArrayList<Entry>();
            for (Object value : Json.children(saved, "entries")) {
                if (!(value instanceof Map)) continue;
                Map<String, Object> entry = Json.object(value);
                Double kind = Json.number(entry, "kind");
                String text = Json.text(entry, "text");
                if (kind == null || text == null) continue;
                try {
                    Double pictures = Json.number(entry, "pictures");
                    entries.add(new Entry(kind.intValue(), text, Json.text(entry, "id"),
                        pictures == null ? 0 : pictures.intValue()));
                } catch (IllegalArgumentException skipped) {
                    // A kind from some other version.
                }
            }
            Double sequence = Json.number(saved, "sequence");
            Transcript transcript = new Transcript(
                entries,
                sequence == null ? 0 : sequence.longValue(),
                Json.flag(saved, "working")
            );
            return new Saved(id, Json.text(saved, "title"), Json.text(saved, "account"), transcript);
        } catch (RuntimeException e) {
            return new Saved(id, null, new Transcript());
        }
    }

    /** Saves a session's transcript. A null id (a session not yet created) saves nothing. */
    public void saveSession(String id, String title, Transcript transcript) throws IOException {
        saveSession(id, title, null, transcript);
    }

    public void saveSession(String id, String title, String account, Transcript transcript) throws IOException {
        if (!Chat.validId(id)) return;
        List<Object> entries = new ArrayList<Object>();
        for (Entry entry : transcript.entries()) {
            Map<String, Object> saved = Json.map("kind", Integer.valueOf(entry.kind), "text", entry.text, "id", entry.id);
            if (entry.pictures > 0) saved.put("pictures", Integer.valueOf(entry.pictures));
            entries.add(saved);
        }
        write(SESSIONS + "/" + id + ".json", Json.write(Json.map(
            "id", id,
            "title", title,
            "account", account,
            "sequence", Long.valueOf(transcript.sequence()),
            "working", Boolean.valueOf(transcript.working()),
            "entries", entries
        )), false);
    }

    /**
     * Forgets all but the `keep` most recently saved transcripts, never the one
     * named `open`, and returns the ids of the messages in what is left that
     * carried pictures, so the pictures nobody can see any more can go too.
     */
    public synchronized Set<String> prune(int keep, String open) {
        File folder = new File(directory, SESSIONS);
        File[] files = folder.listFiles();
        Set<String> pictured = new HashSet<String>();
        if (files == null) return pictured;
        Arrays.sort(files, (a, b) -> {
            long x = a.lastModified();
            long y = b.lastModified();
            return x == y ? 0 : x > y ? -1 : 1;
        });
        int kept = 0;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".json")) {
                file.delete();
                continue;
            }
            String id = name.substring(0, name.length() - ".json".length());
            if (!id.equals(open) && kept >= keep) {
                file.delete();
                continue;
            }
            if (!id.equals(open)) kept++;
            for (Entry entry : session(id).transcript.entries()) {
                if (entry.pictures > 0 && entry.id != null) pictured.add(entry.id);
            }
        }
        return pictured;
    }

    // ------------------------------------------------------------------
    // Usage limits
    // ------------------------------------------------------------------

    /** Until when each cloud account is known to be out, in epoch milliseconds, by id. */
    public Map<String, Long> limits() {
        Map<String, Long> limits = new LinkedHashMap<String, Long>();
        String json = read(LIMITS);
        if (json == null) return limits;
        try {
            Map<String, Object> saved = Json.object(Json.parse(json));
            for (String account : saved.keySet()) {
                Double until = Json.number(saved, account);
                if (until != null) limits.put(account, Long.valueOf(until.longValue()));
            }
        } catch (RuntimeException e) {
            limits.clear();
        }
        return limits;
    }

    public void saveLimits(Map<String, Long> limits) throws IOException {
        write(LIMITS, Json.write(new LinkedHashMap<String, Object>(limits)), false);
    }

    /** The messages waiting for their account's limit to lift, in the order they were written. */
    public List<Queued> queue() {
        List<Queued> queue = new ArrayList<Queued>();
        String json = read(QUEUE);
        if (json == null) return queue;
        try {
            for (Object value : Json.array(Json.parse(json))) {
                if (!(value instanceof Map)) continue;
                Map<String, Object> saved = Json.object(value);
                Double at = Json.number(saved, "at");
                Double pictures = Json.number(saved, "pictures");
                Double tries = Json.number(saved, "tries");
                List<String> files = new ArrayList<String>();
                for (Object name : Json.children(saved, "files")) {
                    if (name instanceof String && RemoteFile.validName((String) name)) files.add((String) name);
                }
                try {
                    queue.add(new Queued(Json.text(saved, "session"), Json.text(saved, "uuid"),
                        Json.text(saved, "text"), files, pictures == null ? 0 : pictures.intValue(),
                        at == null ? 0 : at.longValue(), tries == null ? 0 : tries.intValue()));
                } catch (IllegalArgumentException skipped) {
                    // Not one this version can send.
                }
            }
        } catch (RuntimeException e) {
            queue.clear();
        }
        return queue;
    }

    public void saveQueue(List<Queued> queue) throws IOException {
        List<Object> saved = new ArrayList<Object>();
        for (Queued message : queue) {
            saved.add(Json.map("session", message.session, "uuid", message.uuid, "text", message.text,
                "files", new ArrayList<Object>(message.files), "pictures", Integer.valueOf(message.pictures),
                "at", Long.valueOf(message.at), "tries", Integer.valueOf(message.tries)));
        }
        write(QUEUE, Json.write(saved), false);
    }

    // ------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------

    /** The command-center files with a copy on the phone, as they were when fetched. */
    public Map<String, RemoteFile> downloads() {
        Map<String, RemoteFile> downloads = new LinkedHashMap<String, RemoteFile>();
        String json = read(DOWNLOADS);
        if (json == null) return downloads;
        try {
            for (Object value : Json.array(Json.parse(json))) {
                if (!(value instanceof Map)) continue;
                Map<String, Object> saved = Json.object(value);
                String name = Json.text(saved, "name");
                Double size = Json.number(saved, "size");
                Double modified = Json.number(saved, "modified");
                if (!RemoteFile.validName(name) || size == null || modified == null) continue;
                downloads.put(name, new RemoteFile(name, size.longValue(), modified.longValue()));
            }
        } catch (RuntimeException e) {
            downloads.clear();
        }
        return downloads;
    }

    public void saveDownloads(Map<String, RemoteFile> downloads) throws IOException {
        List<Object> saved = new ArrayList<Object>();
        for (RemoteFile file : downloads.values()) {
            saved.add(Json.map("name", file.name, "size", Long.valueOf(file.size),
                "modified", Long.valueOf(file.modified)));
        }
        write(DOWNLOADS, Json.write(saved), false);
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    /*
     * Synchronized because more than one thread writes here: the settings
     * are saved from a network worker once an old login has been handed over,
     * while a session is saved from the save worker. They do not share a temporary file, but the directory check and
     * rename are simpler to reason about one at a time.
     */

    private synchronized String read(String name) {
        return Disk.read(new File(directory, name), MAX_FILE);
    }

    private synchronized void write(String name, String content, boolean secret) throws IOException {
        Disk.write(new File(directory, name), content, secret);
    }
}
