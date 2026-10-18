package com.tablet.xp3.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The phone's folders, for Upload: in each, its folders, then the files the
 * tablet takes (PDFs and ePubs), each group by name. Hidden ones are left out.
 */
public final class Browse {

    private Browse() {}

    /** Whether the tablet takes a file of this name. */
    public static boolean takes(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".pdf") || n.endsWith(".epub");
    }

    public static List<File> list(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return Collections.emptyList();
        Arrays.sort(all, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        List<File> out = new ArrayList<>();
        for (File f : all) if (f.isDirectory() && !f.getName().startsWith(".")) out.add(f);
        for (File f : all) if (f.isFile() && !f.getName().startsWith(".") && takes(f.getName())) out.add(f);
        return out;
    }

    /** What a file is called on the tablet: its name without the extension. */
    public static String title(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
