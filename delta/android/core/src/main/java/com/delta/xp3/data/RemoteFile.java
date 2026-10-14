package com.delta.xp3.data;

import java.util.Locale;

/**
 * One file on the command center: what an agent pushed with `delta push`.
 *
 * The same name is pushed again and again as an app is fixed, so a name alone
 * does not say whether the copy on the phone is the latest. Size and the
 * server's modified time together do, which is what `sameAs` compares.
 */
public final class RemoteFile {

    public final String name;
    public final long size;
    /** Epoch milliseconds, as the command center reports them. */
    public final long modified;

    public RemoteFile(String name, long size, long modified) {
        if (!validName(name)) throw new IllegalArgumentException("Not a file name");
        this.name = name;
        this.size = size;
        this.modified = modified;
    }

    /**
     * The command center's own rule (a plain name, no leading dot, nothing
     * that could be a path), checked again here because the name becomes a
     * file in the app's storage.
     */
    public static boolean validName(String name) {
        if (name == null || name.length() == 0 || name.length() > 255 || name.startsWith(".")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c < 32 || c == 127) return false;
        }
        return true;
    }

    /**
     * The `n`th choice of name for a file called `name`: the name itself, then
     * "name (2).ext", "name (3).ext" and so on, as Move to names a copy.
     */
    public static String numbered(String name, int n) {
        if (n <= 1) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String tail = dot > 0 ? name.substring(dot) : "";
        return stem + " (" + n + ")" + tail;
    }

    public boolean isApk() {
        return name.toLowerCase(Locale.US).endsWith(".apk");
    }

    /** Whether `other` describes the same upload of this name. */
    public boolean sameAs(RemoteFile other) {
        return other != null && name.equals(other.name) && size == other.size && modified == other.modified;
    }
}
