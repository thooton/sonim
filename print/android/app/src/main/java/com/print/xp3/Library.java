package com.print.xp3;

import android.content.Context;
import android.os.Environment;
import com.xp3.common.ui.Card;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The PDFs on the phone, by the places people keep them (screen 02):
 * Downloads, Documents, what came over Bluetooth, the SD card, and the
 * phone's storage as plain folders. Reads storage, so off the main thread.
 */
final class Library {

    private Library() {}

    static final String RECENT = "recent";
    static final String DOWNLOADS = "downloads";
    static final String DOCUMENTS = "documents";
    static final String BLUETOOTH = "bluetooth";
    static final String CARD = "card";
    static final String PHONE = "phone";

    /** A place to look: its folders, searched to some depth. */
    static final class Location {
        final String id;
        final String name;
        final int icon;
        final List<File> roots;
        /** Phone storage is browsed folder by folder rather than listed whole. */
        final boolean browse;

        Location(String id, String name, int icon, List<File> roots, boolean browse) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.roots = roots;
            this.browse = browse;
        }
    }

    /** A PDF on the phone. */
    static final class Pdf {
        final File file;
        final String name;
        final long size;
        final long modified;

        Pdf(File file) {
            this.file = file;
            this.name = title(file);
            this.size = file.length();
            this.modified = file.lastModified();
        }
    }

    static String title(File file) {
        String name = file.getName();
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
    }

    static boolean isPdf(File file) {
        return file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf") && !file.getName().startsWith(".");
    }

    static File phone() {
        return Environment.getExternalStorageDirectory();
    }

    /** Every location, in the proposal's order (Recent is the app's own and is added by the screen). */
    static List<Location> locations(Context context) {
        File phone = phone();
        File card = Card.root(context);
        List<Location> out = new ArrayList<>();
        out.add(new Location(DOWNLOADS, "Downloads", R.drawable.ic_download,
            existing(new File(phone, "Download"), card == null ? null : new File(card, "Download")), false));
        out.add(new Location(DOCUMENTS, "Documents", R.drawable.ic_folder,
            existing(new File(phone, "Documents"), card == null ? null : new File(card, "Documents")), false));
        out.add(new Location(BLUETOOTH, "Bluetooth", R.drawable.ic_bluetooth,
            existing(new File(phone, "bluetooth"), new File(phone, "Bluetooth"), new File(phone, "Download/Bluetooth")), false));
        if (card != null) out.add(new Location(CARD, "SD card", R.drawable.ic_sd, existing(card), false));
        out.add(new Location(PHONE, "Phone storage", R.drawable.ic_phone, existing(phone), true));
        return out;
    }

    private static List<File> existing(File... dirs) {
        List<File> out = new ArrayList<>();
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            boolean seen = false;
            for (File each : out) if (same(each, dir)) seen = true;
            if (!seen) out.add(dir);
        }
        return out;
    }

    private static boolean same(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException e) {
            return a.equals(b);
        }
    }

    /** Every PDF under the location's folders, newest first. */
    static List<Pdf> scan(Location location) {
        List<Pdf> out = new ArrayList<>();
        for (File root : location.roots) walk(root, 0, out);
        sort(out, NEWEST);
        return out;
    }

    private static void walk(File dir, int depth, List<Pdf> out) {
        if (depth > 6 || out.size() > 2000) return;
        File[] all = dir.listFiles();
        if (all == null) return;
        for (File each : all) {
            String name = each.getName();
            if (name.startsWith(".")) continue;
            if (each.isDirectory()) {
                // Apps' private data and caches hold no one's documents.
                if (depth == 0 && name.equals("Android")) continue;
                walk(each, depth + 1, out);
            } else if (isPdf(each)) {
                out.add(new Pdf(each));
            }
        }
    }

    static final int NEWEST = 0;
    static final int NAME = 1;
    static final int SIZE = 2;
    static final String[] SORTS = {"Newest first", "By name", "Largest first"};

    static void sort(List<Pdf> pdfs, int order) {
        Comparator<Pdf> by;
        if (order == NAME) {
            by = (a, b) -> T9.compare(a.name, b.name);
        } else if (order == SIZE) {
            by = (a, b) -> Long.compare(b.size, a.size);
        } else {
            by = (a, b) -> Long.compare(b.modified, a.modified);
        }
        Collections.sort(pdfs, by);
    }

    /** One folder for browsing: its folders, then its PDFs, each by name. */
    static List<File> folder(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return Collections.emptyList();
        Arrays.sort(all, (a, b) -> T9.compare(a.getName(), b.getName()));
        List<File> out = new ArrayList<>();
        for (File each : all) if (each.isDirectory() && !each.getName().startsWith(".")) out.add(each);
        for (File each : all) if (isPdf(each)) out.add(each);
        return out;
    }

    /** "412 KB", "1.2 MB". */
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
