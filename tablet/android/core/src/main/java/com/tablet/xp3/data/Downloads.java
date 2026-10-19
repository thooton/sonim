package com.tablet.xp3.data;

import java.io.File;

/** Where a download (a transform's result, or a file as a PDF) goes on the phone. */
public final class Downloads {

    private Downloads() {}

    /**
     * The file name a document called `name` on the tablet is saved under as
     * a PDF: a slash, which names can hold, can't be in a file name.
     */
    public static String pdfName(String name) {
        String base = name.replace('/', '-').replace('\\', '-').trim();
        if (base.length() == 0 || base.equals(".") || base.equals("..")) base = "Document";
        return base.toLowerCase(java.util.Locale.US).endsWith(".pdf") ? base : base + ".pdf";
    }

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
