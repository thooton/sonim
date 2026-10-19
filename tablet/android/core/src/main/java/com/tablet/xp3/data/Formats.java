package com.tablet.xp3.data;

/**
 * The formats a transform job makes (transformFormats on the command
 * center), in the order they are listed. Every job makes a PDF, which goes
 * on the tablet; the user ticks the others when starting one.
 */
public final class Formats {

    public static final String PDF = "pdf";
    public static final String JPG = "jpg";
    public static final String[] ALL = {PDF, "docx", "md", "txt", JPG};
    /** What the Formats and Download sheets call them. */
    public static final String[] LABELS = {"PDF", "Word", "Markdown", "Text", "Images"};

    private Formats() {}

    /** Where `format` is in ALL, or -1. */
    public static int index(String format) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].equals(format)) return i;
        return -1;
    }

    /** The format of a file in Files, by its extension ("jpeg" is "jpg"), or "". */
    public static String of(String file) {
        int dot = file.lastIndexOf('.');
        String ext = dot < 0 ? "" : file.substring(dot + 1).toLowerCase(java.util.Locale.US);
        if (ext.equals("jpeg")) ext = JPG;
        return index(ext) < 0 ? "" : ext;
    }
}
