package com.tablet.xp3.data;

import java.io.File;

/** Where a downloaded transform result goes on the phone. */
public final class Downloads {

    private Downloads() {}

    /**
     * `name` in `dir`, or "name (2).ext" and so on when that is taken, so a
     * download never replaces a file already there.
     */
    public static File free(File dir, String name) {
        String base = new File(name).getName();
        if (base.length() == 0 || base.equals(".") || base.equals("..")) base = "Transform";
        File f = new File(dir, base);
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int n = 2; f.exists() || new File(f.getPath() + ".part").exists(); n++) f = new File(dir, stem + " (" + n + ")" + ext);
        return f;
    }
}
