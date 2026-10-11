package com.cloud.xp3.data;

import com.cloud.xp3.net.Config;
import com.cloud.xp3.net.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The two things the app writes down, in its own private directory: the
 * Claude login, and the one session with its transcript.
 *
 * The transcript could be rebuilt by replaying the session's whole event
 * stream, but on a phone that is a lot of sandbox logs to download every time
 * the app opens. Saving it, with the sequence number it reached, means a
 * launch only fetches what happened since.
 *
 * Every read is defensive. A file that is missing, truncated by a battery pull
 * or written by an older version is treated as absent rather than as an error.
 * Writes go to a temporary file and are renamed into place, so an interrupted
 * write leaves the previous good copy rather than half of a new one.
 */
public final class Store {

    private static final String SETTINGS = "settings.json";
    private static final String BUNDLED_REVISION = "bundled-setup.txt";
    private static final String SESSION = "session.json";

    private static final long MAX_FILE = 4000000;

    /** The session as saved: no session at all is a null id and an empty transcript. */
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

    /** The local OAuth bundle already adopted by this installation, if any. */
    public String bundledRevision() {
        String revision = read(BUNDLED_REVISION);
        return revision == null ? "" : revision.trim();
    }

    public void saveBundledRevision(String revision) throws IOException {
        write(BUNDLED_REVISION, revision == null ? "" : revision.trim(), false);
    }

    // ------------------------------------------------------------------
    // The session
    // ------------------------------------------------------------------

    public Saved session() {
        String json = read(SESSION);
        if (json == null) return new Saved(null, null, new Transcript());
        try {
            Map<String, Object> saved = Json.object(Json.parse(json));
            String id = Json.text(saved, "id");
            if (id != null && !id.startsWith("session_")) id = null;
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
                id != null && Json.flag(saved, "working")
            );
            return new Saved(id, Json.text(saved, "title"), transcript);
        } catch (RuntimeException e) {
            return new Saved(null, null, new Transcript());
        }
    }

    public void saveSession(String id, String title, Transcript transcript) throws IOException {
        List<Object> entries = new ArrayList<Object>();
        for (Entry entry : transcript.entries()) {
            Map<String, Object> saved = Json.map("kind", Integer.valueOf(entry.kind), "text", entry.text, "id", entry.id);
            if (entry.pictures > 0) saved.put("pictures", Integer.valueOf(entry.pictures));
            entries.add(saved);
        }
        write(SESSION, Json.write(Json.map(
            "id", id,
            "title", title,
            "sequence", Long.valueOf(transcript.sequence()),
            "working", Boolean.valueOf(transcript.working()),
            "entries", entries
        )), false);
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    /*
     * Synchronized because more than one thread writes here: a rotated login
     * is saved from a network worker while the session is saved from the save
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
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not open the app's storage");
        }
        File target = new File(directory, name);
        File temporary = new File(directory, name + ".tmp");
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
