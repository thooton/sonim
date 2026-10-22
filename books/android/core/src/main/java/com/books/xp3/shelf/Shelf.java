package com.books.xp3.shelf;

import com.books.xp3.book.Book;
import com.books.xp3.book.Epub;
import com.books.xp3.book.PlainText;
import com.xp3.common.Disk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The library: every book found on the phone, what it is called, and how far
 * into it the reader has got.
 *
 * Books are not copied anywhere; they are read where they lie, in the Books
 * folders the app is given (the phone's and the SD card's), subfolders
 * included, and nowhere else. What is kept
 * here, in the app's private directory, is a small index: each book's title
 * and author (so a scan only opens books it has not seen, or that changed)
 * and the place it was left at.
 *
 * The index is one line per book, tab separated. A file that is missing or
 * damaged is treated as empty: the worst a battery pull can do is cost the
 * reading positions written since the last good save, since writes go to a
 * temporary file that is renamed into place.
 */
public final class Shelf {

    private static final String HEADER = "books-shelf 1";
    private static final int MAX_DEPTH = 5;
    private static final int MAX_FILES = 5000;

    /** One book on the shelf. */
    public static final class Entry {
        public final String path;
        public long size;
        public long modified;
        public String title;
        public String author;
        /** Where reading was left, as a Book offset; -1 if never opened. */
        public long position = -1;
        /** The book's length, as a Book counts it; 0 until it has been opened. */
        public long length;
        /** When it was last opened, in milliseconds since the epoch; 0 if never. */
        public long opened;
        /** Set by scan() for a book whose file is there now. */
        boolean present;

        Entry(String path) {
            this.path = path;
        }

        public File file() {
            return new File(path);
        }

        public boolean isNew() {
            return position < 0;
        }

        /** How much has been read, 0 to 100. */
        public int percent() {
            if (position <= 0 || length <= 0) return 0;
            return (int) Math.min(100, Math.max(0, position * 100 / length));
        }

        public boolean finished() {
            return length > 0 && position >= length;
        }

        /** A title to show: the book's own, else its file name made presentable. */
        public String displayTitle() {
            if (title != null && title.length() > 0) return title;
            String name = new File(path).getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            return name.replace('_', ' ').trim();
        }
    }

    private final File index;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();

    public Shelf(File directory) {
        this.index = new File(directory, "shelf.tsv");
        load();
    }

    /** Whether `file` is something this app can open. */
    public static boolean isBook(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return (name.endsWith(".epub") || name.endsWith(".txt")) && !name.startsWith(".");
    }

    /** Reads the whole book, choosing the reader by its file name. */
    public static Book open(File file) throws IOException {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".txt")) {
            String title = file.getName();
            return PlainText.read(file, title.substring(0, title.length() - 4).replace('_', ' '));
        }
        return Epub.read(file);
    }

    /** The cover image's bytes, or null. Plain text has none. */
    public static byte[] cover(File file) throws IOException {
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) return null;
        return Epub.cover(file);
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    /**
     * Looks through `folders` and their subfolders for books, reads the
     * title and author of any it has not seen (or that changed), and returns
     * the library: books being read, most recent first, then the rest by
     * title. A book anywhere else is not in the library.
     */
    public List<Entry> scan(List<File> folders) {
        List<File> found = new ArrayList<File>();
        for (File folder : folders) walk(folder, 0, found);

        synchronized (this) {
            for (Entry entry : entries.values()) entry.present = false;
        }
        for (File file : found) {
            String path = file.getAbsolutePath();
            Entry entry;
            synchronized (this) {
                entry = entries.get(path);
            }
            long size = file.length();
            long modified = file.lastModified();
            if (entry != null && entry.size == size && entry.modified == modified && entry.title != null) {
                entry.present = true;
                continue;
            }
            Epub.Info info = describe(file);
            synchronized (this) {
                if (entry == null) {
                    entry = new Entry(path);
                    entries.put(path, entry);
                } else if (entry.size != size) {
                    // A different file under the same name: start it afresh.
                    entry.position = -1;
                    entry.length = 0;
                }
                entry.size = size;
                entry.modified = modified;
                // An empty title, not null, so a book without one is not read
                // again on every scan; the file name is shown instead.
                String title = info == null ? null : clean(info.title);
                entry.title = title == null ? "" : title;
                entry.author = info == null ? null : clean(info.author);
                entry.present = true;
            }
        }
        save();
        return books();
    }

    /** The books found by the last scan, in library order. */
    public synchronized List<Entry> books() {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry entry : entries.values()) if (entry.present) out.add(entry);
        Collections.sort(out, (a, b) -> {
            if (a.opened != b.opened) return a.opened > b.opened ? -1 : 1;
            int byTitle = a.displayTitle().compareToIgnoreCase(b.displayTitle());
            return byTitle != 0 ? byTitle : a.path.compareTo(b.path);
        });
        return out;
    }

    private static Epub.Info describe(File file) {
        try {
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) return PlainText.info(file);
            return Epub.info(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String clean(String value) {
        if (value == null) return null;
        String out = value.replaceAll("[\\s\\u00A0]+", " ").trim();
        return out.length() == 0 ? null : out;
    }

    private static void walk(File directory, int depth, List<File> found) {
        if (depth > MAX_DEPTH || found.size() >= MAX_FILES) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".")) continue;
            if (child.isDirectory()) {
                walk(child, depth + 1, found);
            } else if (isBook(child) && child.length() > 0) {
                found.add(child);
                if (found.size() >= MAX_FILES) return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Reading positions
    // ------------------------------------------------------------------

    /** The entry for `path`, or null. */
    public synchronized Entry get(String path) {
        return entries.get(path);
    }

    /** Records where reading got to. Call save() to write it down. */
    public synchronized void progress(String path, long position, long length, long now) {
        Entry entry = entries.get(path);
        if (entry == null) {
            File file = new File(path);
            entry = new Entry(path);
            entry.size = file.length();
            entry.modified = file.lastModified();
            entry.present = file.exists();
            entries.put(path, entry);
        }
        entry.position = Math.max(0, position);
        entry.length = length;
        entry.opened = now;
    }

    /** Puts a book back to unread. */
    public synchronized void forget(String path) {
        Entry entry = entries.get(path);
        if (entry == null) return;
        entry.position = -1;
        entry.length = 0;
        entry.opened = 0;
    }

    /** Takes a book off the shelf, place and all: its file has been deleted. */
    public synchronized void remove(String path) {
        entries.remove(path);
    }

    // ------------------------------------------------------------------
    // The index file
    // ------------------------------------------------------------------

    private synchronized void load() {
        entries.clear();
        String text = Disk.read(index, 8 * 1024 * 1024);
        if (text == null) return;
        String[] lines = text.split("\n");
        if (lines.length == 0 || !lines[0].equals(HEADER)) return;
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split("\t", -1);
            if (fields.length < 8) continue;
            try {
                Entry entry = new Entry(unescape(fields[0]));
                entry.size = Long.parseLong(fields[1]);
                entry.modified = Long.parseLong(fields[2]);
                entry.title = unescape(fields[3]);
                entry.author = fields[4].length() == 0 ? null : unescape(fields[4]);
                entry.position = Long.parseLong(fields[5]);
                entry.length = Long.parseLong(fields[6]);
                entry.opened = Long.parseLong(fields[7]);
                entry.present = new File(entry.path).isFile();
                entries.put(entry.path, entry);
            } catch (NumberFormatException skipped) {
                // A damaged line costs only that book's place.
            }
        }
    }

    /** Writes the index down; false if it could not be. */
    public synchronized boolean save() {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Entry entry : entries.values()) {
            // A book that has gone and was never opened is not worth keeping.
            if (!entry.present && entry.opened == 0) continue;
            out.append(escape(entry.path)).append('\t')
                .append(entry.size).append('\t')
                .append(entry.modified).append('\t')
                .append(entry.title == null ? "" : escape(entry.title)).append('\t')
                .append(entry.author == null ? "" : escape(entry.author)).append('\t')
                .append(entry.position).append('\t')
                .append(entry.length).append('\t')
                .append(entry.opened).append('\n');
        }
        try {
            Disk.write(index, out.toString(), false);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c == '\t') out.append("\\t");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else out.append(c);
        }
        return out.toString();
    }

    static String unescape(String value) {
        if (value.indexOf('\\') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 't') out.append('\t');
            else if (next == 'n') out.append('\n');
            else if (next == 'r') out.append('\r');
            else out.append(next);
        }
        return out.toString();
    }
}
