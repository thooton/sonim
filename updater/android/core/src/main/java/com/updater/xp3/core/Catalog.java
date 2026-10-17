package com.updater.xp3.core;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the latest release offers, from the `manifest.json` CI publishes with
 * it (see .github/manifest.py):
 *
 * <pre>
 * {"schema": 1, "apps": {"list": {
 *     "version": "1.1.0", "versionCode": 4, "package": "com.list.xp3",
 *     "label": "List", "apk": "list.apk", "size": 51234, "sha256": "…",
 *     "notes": [{"time": "2026-10-17T09:49:29+00:00", "text": "…", "version": "1.1.0"}]}}}
 * </pre>
 *
 * An app is only offered when its name could be a release file name and it
 * has a version and a valid package; anything else about it that is missing
 * or malformed is left out rather than refused.
 */
public final class Catalog {

    /** One app in the release. */
    public static final class Entry {
        /** The release name: `<name>.apk` is the file to download. */
        public final String name;
        /** The release's versionName. */
        public final String version;
        public final String packageName;
        /** The APK's own label, for an app not on the phone; never null. */
        public final String label;
        /** The APK's size in bytes, or -1 when the manifest doesn't say. */
        public final long size;
        /** The APK's SHA-256 in lowercase hex, or null when the manifest doesn't say. */
        public final String sha256;
        /** What's new in it, newest first. */
        public final List<Notes.Note> notes;

        public Entry(String name, String version, String packageName, String label,
                     long size, String sha256, List<Notes.Note> notes) {
            this.name = name;
            this.version = version;
            this.packageName = packageName;
            this.label = label;
            this.size = size;
            this.sha256 = sha256;
            this.notes = notes;
        }

        public String apk() {
            return name + ".apk";
        }
    }

    private Catalog() {}

    /** The apps in a manifest, sorted by name. */
    public static List<Entry> parse(String json) {
        Map<String, Object> manifest = Json.object(Json.parse(json));
        Double schema = Json.number(manifest, "schema");
        if (schema == null || schema.intValue() != 1) {
            throw new IllegalArgumentException("Unknown manifest schema");
        }
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, Object> app : Json.child(manifest, "apps").entrySet()) {
            if (!validName(app.getKey()) || !(app.getValue() instanceof Map)) continue;
            Map<String, Object> fields = Json.object(app.getValue());
            String version = Json.text(fields, "version");
            String packageName = Json.text(fields, "package");
            if (version == null || version.trim().isEmpty()) continue;
            if (packageName == null || !validPackage(packageName)) continue;
            String label = Json.text(fields, "label");
            if (label == null || label.trim().isEmpty()) label = title(app.getKey());
            Double size = Json.number(fields, "size");
            String sha256 = Json.text(fields, "sha256");
            if (sha256 != null && !sha256.matches("[0-9a-fA-F]{64}")) sha256 = null;
            out.add(new Entry(app.getKey(), version.trim(), packageName, label.trim(),
                size != null ? size.longValue() : -1,
                sha256 != null ? sha256.toLowerCase(Locale.ROOT) : null,
                notes(Json.children(fields, "notes"))));
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private static List<Notes.Note> notes(List<Object> items) {
        List<Notes.Note> out = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> note = Json.object(item);
            String text = Json.text(note, "text");
            if (text == null || text.trim().isEmpty()) continue;
            out.add(new Notes.Note(Json.instant(note, "time"), text.trim(), Json.text(note, "version")));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    private static String title(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * A name that is safe both as the last part of a URL and as a file name
     * on the phone: letters, digits, `_` and `-`, not too long.
     */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean validPackage(String name) {
        return name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
}
