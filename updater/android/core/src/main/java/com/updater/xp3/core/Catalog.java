package com.updater.xp3.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the latest release offers, from the two files CI publishes with it.
 *
 * `versions.txt` has a line `<app> = <versionName>` per APK, and
 * `packages.txt` a line `<app> = <package name>`; `<app>` is the APK's name
 * in the release without `.apk` (see .github/workflows/build-apks.yml). An
 * app is only offered when both files name it.
 */
public final class Catalog {

    /** One app in the release. */
    public static final class Entry {
        /** The release name: `<name>.apk` is the file to download. */
        public final String name;
        /** The release's versionName. */
        public final String version;
        public final String packageName;

        public Entry(String name, String version, String packageName) {
            this.name = name;
            this.version = version;
            this.packageName = packageName;
        }

        public String apk() {
            return name + ".apk";
        }
    }

    private Catalog() {}

    /**
     * The `name = value` lines of one file, in order. Blank lines and lines
     * starting with `#` are skipped, and so is any line whose name couldn't be
     * a release file name or whose value is empty.
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String name = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!validName(name) || value.isEmpty()) continue;
            out.put(name, value);
        }
        return out;
    }

    /** The apps both files name, sorted by name. */
    public static List<Entry> join(Map<String, String> versions, Map<String, String> packages) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, String> version : versions.entrySet()) {
            String packageName = packages.get(version.getKey());
            if (packageName == null || !validPackage(packageName)) continue;
            out.add(new Entry(version.getKey(), version.getValue(), packageName));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
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
