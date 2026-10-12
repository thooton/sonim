package com.delta.xp3.data;

import com.delta.xp3.net.Config;
import com.delta.xp3.net.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the app writes down, in its own private directory: the logins, which
 * session is open, the last list of sessions, a transcript per session, and
 * which command-center files have been downloaded.
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
    public static final String CLOUD = "cloud";
    public static final String CENTER = "center";

    private static final String SETTINGS = "settings.json";
    private static final String OPEN = "open.txt";
    private static final String CHATS = "chats.json";
    private static final String DOWNLOADS = "downloads.json";
    private static final String SESSIONS = "sessions";

    private static final long MAX_FILE = 4000000;

    /** A session as saved: an unknown one is a null title and an empty transcript. */
    public static final class Saved {
        public final String id;
        public final String title;
        public final Transcript transcript;

        public Saved(String id, String title, Transcript transcript) {
            this.id = id;
            this.title = title;
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
        // Installs from before the white-label rename have the old marker name.
        if (revision == null && CLOUD.equals(part)) revision = read("bundled-claude.txt");
        return revision == null ? "" : revision.trim();
    }

    public void saveBundledRevision(String part, String revision) throws IOException {
        write(revisionFile(part), revision == null ? "" : revision.trim(), false);
    }

    private static String revisionFile(String part) {
        if (!CLOUD.equals(part) && !CENTER.equals(part)) throw new IllegalArgumentException("Unknown login");
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
                    updated == null ? 0 : updated.longValue()));
            }
        } catch (RuntimeException e) {
            chats.clear();
        }
        return chats;
    }

    public void saveChats(List<Chat> chats) throws IOException {
        List<Object> saved = new ArrayList<Object>();
        for (Chat chat : chats) {
            saved.add(Json.map("id", chat.id, "title", chat.title, "status", chat.status,
                "updated", Long.valueOf(chat.updated)));
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
            return new Saved(id, Json.text(saved, "title"), transcript);
        } catch (RuntimeException e) {
            return new Saved(id, null, new Transcript());
        }
    }

    /** Saves a session's transcript. A null id (a session not yet created) saves nothing. */
    public void saveSession(String id, String title, Transcript transcript) throws IOException {
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
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = a.lastModified();
                long y = b.lastModified();
                return x == y ? 0 : x > y ? -1 : 1;
            }
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
     * Synchronized because more than one thread writes here: a rotated login
     * is saved from a network worker while a session is saved from the save
     * worker. They do not share a temporary file, but the directory check and
     * rename are simpler to reason about one at a time.
     */

    private synchronized String read(String name) {
        File file = new File(directory, name);
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_FILE) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return new String(out.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            close(in);
        }
    }

    private synchronized void write(String name, String content, boolean secret) throws IOException {
        File target = new File(directory, name);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not open the app's storage");
        }
        File temporary = new File(parent, target.getName() + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(temporary);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            if (out instanceof FileOutputStream) ((FileOutputStream) out).getFD().sync();
        } finally {
            close(out);
        }
        if (secret) {
            // The app's own directory is already private, but a file holding a
            // login should not be readable even if the directory's mode is
            // ever loosened by a backup or a restore.
            temporary.setReadable(false, false);
            temporary.setReadable(true, true);
            temporary.setWritable(false, false);
            temporary.setWritable(true, true);
        }
        if (!temporary.renameTo(target)) {
            // Some filesystems will not replace an existing file on rename.
            if (!target.delete() || !temporary.renameTo(target)) {
                temporary.delete();
                throw new IOException("Could not save to the app's storage");
            }
        }
    }

    private static void close(java.io.Closeable stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // A close that fails on a stream already written is not a new failure.
        }
    }
}
