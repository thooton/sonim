package com.source.xp3.data;

import com.xp3.common.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What has been saved to the phone, newest first: the Downloads screen's
 * finished list, and how a result found again shows as saved. Kept as JSON in
 * the app's private files; the files themselves are in Music and Books.
 */
public final class History {

    /** How many downloads are remembered. */
    public static final int LIMIT = 100;

    /** One finished download. */
    public static final class Entry {
        /** A music result's slsk:// link, or "book:" with a book's id and hash. */
        public final String key;
        /** Music.SONG, Music.ALBUM or "book". */
        public final String kind;
        public final String title;
        /** The folders it came from, as the sharer named them, or a book's author. */
        public final String folders;
        /** Where its files were saved, absolute. */
        public final List<String> files;
        public final long bytes;
        /** When it finished, in milliseconds since 1970. */
        public final long time;

        public Entry(String key, String kind, String title, String folders, List<String> files, long bytes, long time) {
            this.key = key;
            this.kind = kind;
            this.title = title;
            this.folders = folders;
            this.files = Collections.unmodifiableList(new ArrayList<String>(files));
            this.bytes = bytes;
            this.time = time;
        }

        Map<String, Object> toMap() {
            return Json.map("key", key, "kind", kind, "title", title, "folders", folders,
                "files", new ArrayList<Object>(files), "bytes", bytes, "time", time);
        }

        static Entry of(Map<String, Object> map) {
            String key = Json.text(map, "key");
            String kind = Json.text(map, "kind");
            if (key == null || key.length() == 0 || kind == null) return null;
            List<String> files = new ArrayList<String>();
            for (Object file : Json.children(map, "files")) if (file instanceof String) files.add((String) file);
            Double bytes = Json.number(map, "bytes");
            Double time = Json.number(map, "time");
            return new Entry(key, kind, text(map, "title"), text(map, "folders"), files,
                bytes == null ? 0 : bytes.longValue(), time == null ? 0 : time.longValue());
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** The latest download of `key`, or null. */
    public Entry find(String key) {
        for (Entry entry : entries) if (entry.key.equals(key)) return entry;
        return null;
    }

    /** Puts `entry` first, in place of an earlier download of the same thing. */
    public void add(Entry entry) {
        remove(entry.key);
        entries.add(0, entry);
        while (entries.size() > LIMIT) entries.remove(entries.size() - 1);
    }

    public void remove(String key) {
        for (int i = entries.size() - 1; i >= 0; i--) if (entries.get(i).key.equals(key)) entries.remove(i);
    }

    public String toJson() {
        List<Object> list = new ArrayList<Object>();
        for (Entry entry : entries) list.add(entry.toMap());
        return Json.write(Json.map("downloads", list));
    }

    /** Whatever can be read back from `json`; nothing at all when it is unreadable. */
    public static History parse(String json) {
        History history = new History();
        if (json == null || json.length() == 0) return history;
        try {
            for (Object value : Json.children(Json.object(Json.parse(json)), "downloads")) {
                if (!(value instanceof Map)) continue;
                Entry entry = Entry.of(Json.object(value));
                if (entry != null && history.entries.size() < LIMIT) history.entries.add(entry);
            }
        } catch (RuntimeException e) {
            return new History();
        }
        return history;
    }

    private static String text(Map<String, Object> map, String key) {
        String value = Json.text(map, key);
        return value == null ? "" : value;
    }
}
