package com.list.xp3.data;

import com.xp3.common.Disk;
import com.xp3.common.Json;

import java.io.File;
import java.io.IOException;
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
        return Disk.read(new File(directory, name), MAX_FILE);
    }

    private synchronized void write(String name, String content) throws IOException {
        Disk.write(new File(directory, name), content, false);
    }
}
