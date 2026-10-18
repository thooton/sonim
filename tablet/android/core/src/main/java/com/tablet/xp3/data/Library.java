package com.tablet.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What is on the tablet, from the command center's listing (GET
 * /remarkable): every document not in the trash, most recently edited
 * first, and the folders a document can be moved to.
 *
 * The shelf is flat, as the proposal's is: a document's folder is a fact
 * about it ("History 210"), not a place to go into.
 */
public final class Library {

    /** The top of the tablet, as Move and Details name it. */
    public static final String TOP = "My files";
    static final String TRASH = "trash";

    /** A folder a document can go in: its id ("" for the top) and its path in words. */
    public static final class Folder {
        public final String id;
        public final String path;

        Folder(String id, String path) {
            this.id = id;
            this.path = path;
        }
    }

    public final List<Doc> docs;
    public final List<Folder> folders;

    private Library(List<Doc> docs, List<Folder> folders) {
        this.docs = docs;
        this.folders = folders;
    }

    public static Library empty() {
        List<Folder> top = new ArrayList<>();
        top.add(new Folder("", TOP));
        return new Library(new ArrayList<Doc>(), top);
    }

    /** Throws IllegalArgumentException when the listing isn't one. */
    public static Library parse(String json) {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object o : Json.array(Json.parse(json))) {
            Map<String, Object> item = Json.object(o);
            String id = Json.text(item, "id");
            if (id == null || id.length() == 0) continue;
            byId.put(id, item);
            items.add(item);
        }
        List<Doc> docs = new ArrayList<>();
        List<Folder> folders = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String id = Json.text(item, "id");
            String parent = orEmpty(Json.text(item, "parent"));
            if (trashed(parent, byId)) continue;
            String type = orEmpty(Json.text(item, "type"));
            if ("folder".equals(type)) {
                folders.add(new Folder(id, path(id, byId)));
                continue;
            }
            Double pages = Json.number(item, "pages");
            Double size = Json.number(item, "size");
            Doc doc = new Doc(id, name(item), type, pages == null ? 0 : pages.intValue(),
                size == null ? 0 : size.longValue(), Json.instant(item, "modified"), parent);
            doc.folder = parent.length() == 0 ? TOP : path(parent, byId);
            docs.add(doc);
        }
        Collections.sort(docs, (a, b) -> Long.compare(b.modified, a.modified));
        Collections.sort(folders, (a, b) -> a.path.compareToIgnoreCase(b.path));
        folders.add(0, new Folder("", TOP));
        return new Library(docs, folders);
    }

    public Doc find(String id) {
        for (Doc doc : docs) if (doc.id.equals(id)) return doc;
        return null;
    }

    public int indexOf(String id) {
        for (int i = 0; i < docs.size(); i++) if (docs.get(i).id.equals(id)) return i;
        return -1;
    }

    public Folder folder(String id) {
        for (Folder f : folders) if (f.id.equals(id)) return f;
        return null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String name(Map<String, Object> item) {
        String name = Json.text(item, "name");
        return name == null || name.trim().length() == 0 ? "Untitled" : name;
    }

    /** In the trash itself or in a folder that is; a loop or a missing folder ends the walk. */
    private static boolean trashed(String parent, Map<String, Map<String, Object>> byId) {
        for (int n = 0; parent.length() > 0 && n < 50; n++) {
            if (TRASH.equals(parent)) return true;
            Map<String, Object> up = byId.get(parent);
            if (up == null) return false;
            parent = orEmpty(Json.text(up, "parent"));
        }
        return false;
    }

    /** "School / Math", from the folder's own name up. */
    private static String path(String id, Map<String, Map<String, Object>> byId) {
        List<String> names = new ArrayList<>();
        String at = id;
        for (int n = 0; at.length() > 0 && n < 50; n++) {
            Map<String, Object> folder = byId.get(at);
            if (folder == null) break;
            names.add(0, name(folder));
            at = orEmpty(Json.text(folder, "parent"));
        }
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) out.append(" / ");
            out.append(name);
        }
        return out.length() == 0 ? TOP : out.toString();
    }
}
