package com.list.xp3.data;

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
 * The one thing the app writes down, in its own private directory: the list.
 *
 * Every read is defensive. A file that is missing, truncated by a battery pull
 * or written by an older version is treated as absent rather than as an error.
 * Writes go to a temporary file and are renamed into place, so an interrupted
 * write leaves the previous good copy rather than half of a new one.
 */
public final class Store {

    private static final String LIST = "list.json";

    /**
     * What older versions kept for the chat assistant: its Claude login and
     * the conversation. Nothing reads them now, and a login should not linger.
     */
    private static final String[] RETIRED = {
        "settings.json", "settings.json.tmp", "bundled-setup.txt", "bundled-setup.txt.tmp",
        "chat.json", "chat.json.tmp",
    };
    private static final long MAX_FILE = 4000000;

    private final File directory;

    public Store(File directory) {
        this.directory = directory;
    }

    /** Deletes the files older versions kept for the chat assistant. */
    public synchronized void forgetChat() {
        for (String name : RETIRED) new File(directory, name).delete();
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
        write(LIST, Json.write(Json.map("items", saved)));
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    /*
     * Synchronized so the directory check and rename happen one at a time,
     * whichever thread asks.
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

    private synchronized void write(String name, String content) throws IOException {
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
