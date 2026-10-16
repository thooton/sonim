package com.source.xp3.net;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a path from a music job into one that is safe to create under the
 * phone's Music folder: no way out of it ("..", absolute paths), and no
 * characters the SD card's FAT file system refuses.
 */
public final class Names {

    private Names() {}

    /** The path's parts, each a usable file or folder name; empty when nothing usable is left. */
    public static List<String> parts(String path) {
        List<String> out = new ArrayList<String>();
        if (path == null) return out;
        for (String raw : path.replace('\\', '/').split("/")) {
            String part = clean(raw);
            if (part.length() == 0 || part.equals(".") || part.equals("..")) continue;
            out.add(part);
        }
        return out;
    }

    static String clean(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            out.append(c < 32 || "\\:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String name = out.toString().trim();
        // FAT drops trailing dots and spaces itself, which would make two
        // different names land on one file.
        while (name.endsWith(".") && !name.equals(".") && !name.equals("..")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        if (name.length() > 200) name = name.substring(0, 200);
        return name;
    }

    /** "name (2).ext", "name (3).ext" and so on: the n-th alternative to a taken name. */
    public static String numbered(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + " (" + n + ")";
        return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }
}
