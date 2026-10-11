package com.list.xp3.data;

import com.list.xp3.net.Config;
import com.list.xp3.net.Json;

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
 * The three things the app writes down, in its own private directory: the
 * Claude login, the list, and the chat.
 *
 * Every read is defensive. A file that is missing, truncated by a battery pull
 * or written by an older version is treated as absent rather than as an error.
 * Writes go to a temporary file and are renamed into place, so an interrupted
 * write leaves the previous good copy rather than half of a new one.
 */
public final class Store {

    private static final String SETTINGS = "settings.json";
    private static final String BUNDLED_REVISION = "bundled-setup.txt";
    private static final String LIST = "list.json";
    private static final String TRANSCRIPT = "chat.json";

    /** Enough conversation to be worth keeping, little enough to load instantly. */
    private static final int MAX_TURNS = 200;
    private static final long MAX_FILE = 4000000;

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
    // The list
    // ------------------------------------------------------------------

    /**
     * The saved list. An item that cannot be read is skipped rather than
     * costing the rest of the list.
     */
    public List<Item> list() {
        List<Item> items = new ArrayList<Item>();
        String json = read(LIST);
        if (json == null) return items;
        try {
            for (Object entry : Json.children(Json.object(Json.parse(json)), "items")) {
                if (!(entry instanceof Map)) continue;
                Map<String, Object> fields = Json.object(entry);
                String text = Json.text(fields, "text");
                if (text == null || items.size() >= Checklist.MAX_ITEMS) continue;
                try {
                    items.add(new Item(text, Json.flag(fields, "done")));
                } catch (IllegalArgumentException skipped) {
                    // Empty or oversized text from some older version.
                }
            }
        } catch (RuntimeException e) {
            return new ArrayList<Item>();
        }
        return items;
    }

    public void saveList(List<Item> items) throws IOException {
        List<Object> saved = new ArrayList<Object>();
        for (Item item : items) {
            saved.add(Json.map("text", item.text, "done", Boolean.valueOf(item.done)));
        }
        write(LIST, Json.write(Json.map("items", saved)), false);
    }

    // ------------------------------------------------------------------
    // The transcript
    // ------------------------------------------------------------------

    public List<ChatTurn> transcript() {
        List<ChatTurn> turns = new ArrayList<ChatTurn>();
        String json = read(TRANSCRIPT);
        if (json == null) return turns;
        try {
            for (Object entry : Json.children(Json.object(Json.parse(json)), "turns")) {
                Map<String, Object> turn = Json.object(entry);
                String text = Json.text(turn, "text");
                Double at = Json.number(turn, "at");
                if (text == null || at == null) continue;
                turns.add(
                    Json.flag(turn, "compacted")
                        ? ChatTurn.compaction(text, (long) at.doubleValue())
                        : new ChatTurn(Json.flag(turn, "me"), text, (long) at.doubleValue())
                );
            }
        } catch (RuntimeException e) {
            return new ArrayList<ChatTurn>();
        }
        return turns;
    }

    public void saveTranscript(List<ChatTurn> turns) throws IOException {
        List<Object> saved = new ArrayList<Object>();
        int from = Math.max(0, turns.size() - MAX_TURNS);
        for (int i = from; i < turns.size(); i++) {
            ChatTurn turn = turns.get(i);
            saved.add(Json.map(
                "me", Boolean.valueOf(turn.fromUser),
                "text", turn.text,
                "at", Long.valueOf(turn.when),
                "compacted", Boolean.valueOf(turn.isCompaction())
            ));
        }
        write(TRANSCRIPT, Json.write(Json.map("turns", saved)), false);
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    /*
     * Synchronized because more than one thread writes here: a rotated login
     * is saved from the chat worker while the list is saved from the save
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
