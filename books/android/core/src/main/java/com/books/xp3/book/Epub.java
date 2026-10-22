package com.books.xp3.book;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads EPUB 2 and 3 books: the package (title, author, reading order), the
 * table of contents (the EPUB 3 nav document, else the EPUB 2 NCX), the
 * cover image, and the text of every document in the spine.
 *
 * Nothing here trusts the book. A missing file, an entry with a wrongly cased
 * or percent-encoded name, or a document that is not well formed is worked
 * around where possible and skipped where not; only a file that is not an
 * EPUB at all is an error.
 */
public final class Epub {

    /** The most any one file inside a book may inflate to. */
    private static final int MAX_ENTRY = 16 * 1024 * 1024;

    private Epub() {}

    /** What the library shows for a book: its title and author. */
    public static Info info(File file) throws IOException {
        ZipFile zip = open(file);
        try {
            Package pkg = readPackage(zip);
            return new Info(pkg.title, pkg.author);
        } finally {
            zip.close();
        }
    }

    /** The cover image's bytes, or null if the book has none that can be found. */
    public static byte[] cover(File file) throws IOException {
        ZipFile zip = open(file);
        try {
            Package pkg = readPackage(zip);
            Item item = pkg.coverImage();
            return item == null ? null : bytes(zip, item.path);
        } finally {
            zip.close();
        }
    }

    /** The whole book. */
    public static Book read(File file) throws IOException {
        ZipFile zip = open(file);
        try {
            Package pkg = readPackage(zip);
            List<Chapter> chapters = new ArrayList<Chapter>();
            Map<String, Integer> byPath = new HashMap<String, Integer>();
            for (Item item : pkg.spine) {
                Chapter chapter = new Chapter();
                byte[] data = bytes(zip, item.path);
                if (data != null) Html.read(text(data), chapter);
                byPath.put(item.path, Integer.valueOf(chapters.size()));
                chapters.add(chapter);
            }
            List<Book.Link> links = contents(zip, pkg, byPath);
            return Book.assemble(pkg.title, pkg.author, chapters, links);
        } finally {
            zip.close();
        }
    }

    public static final class Info {
        public final String title;
        public final String author;

        Info(String title, String author) {
            this.title = title;
            this.author = author;
        }
    }

    // ------------------------------------------------------------------
    // The package document
    // ------------------------------------------------------------------

    static final class Item {
        final String id;
        final String path;
        final String type;
        final String properties;

        Item(String id, String path, String type, String properties) {
            this.id = id;
            this.path = path;
            this.type = type;
            this.properties = properties;
        }

        boolean isImage() {
            return type.startsWith("image/") && !type.contains("svg");
        }
    }

    static final class Package {
        String title;
        String author;
        /** The book's dc:language, as written; null if it has none. */
        String language;
        String coverId;
        String ncxId;
        final Map<String, Item> manifest = new HashMap<String, Item>();
        final List<Item> manifestOrder = new ArrayList<Item>();
        final List<Item> spine = new ArrayList<Item>();

        Item coverImage() {
            for (Item item : manifestOrder) {
                if (item.isImage() && hasProperty(item.properties, "cover-image")) return item;
            }
            if (coverId != null) {
                Item item = manifest.get(coverId);
                if (item != null && item.isImage()) return item;
            }
            for (Item item : manifestOrder) {
                String lower = (item.id + " " + item.path).toLowerCase(Locale.ROOT);
                if (item.isImage() && lower.contains("cover")) return item;
            }
            return null;
        }

        Item nav() {
            for (Item item : manifestOrder) {
                if (hasProperty(item.properties, "nav")) return item;
            }
            return null;
        }
    }

    static Package readPackage(ZipFile zip) throws IOException {
        String opfPath = null;
        byte[] container = bytes(zip, "META-INF/container.xml");
        if (container != null) {
            Markup markup = new Markup(text(container));
            while (markup.next()) {
                if (markup.type == Markup.START && "rootfile".equals(markup.name)) {
                    String path = markup.attr("full-path");
                    if (path != null && path.length() > 0) {
                        opfPath = decode(path);
                        break;
                    }
                }
            }
        }
        if (opfPath == null || find(zip, opfPath) == null) opfPath = firstWithSuffix(zip, ".opf");
        if (opfPath == null) throw new IOException("Not an EPUB: it has no package document");
        byte[] opf = bytes(zip, opfPath);
        if (opf == null) throw new IOException("Not an EPUB: its package document cannot be read");

        Package pkg = new Package();
        String base = directory(opfPath);
        Markup markup = new Markup(text(opf));
        List<String> spineIds = new ArrayList<String>();
        String reading = null;
        StringBuilder value = new StringBuilder();
        while (markup.next()) {
            if (markup.type == Markup.START) {
                String name = markup.name;
                if ("title".equals(name) || "creator".equals(name) || "language".equals(name)) {
                    reading = name;
                    value.setLength(0);
                } else if ("meta".equals(name)) {
                    if ("cover".equals(markup.attr("name"))) pkg.coverId = markup.attr("content");
                } else if ("item".equals(name)) {
                    String id = markup.attr("id");
                    String href = markup.attr("href");
                    if (id == null || href == null) continue;
                    String type = markup.attr("media-type");
                    Item item = new Item(id, resolve(base, href),
                        type == null ? "" : type.toLowerCase(Locale.ROOT),
                        markup.attr("properties"));
                    pkg.manifest.put(id, item);
                    pkg.manifestOrder.add(item);
                } else if ("spine".equals(name)) {
                    pkg.ncxId = markup.attr("toc");
                } else if ("itemref".equals(name)) {
                    String idref = markup.attr("idref");
                    if (idref != null && !"no".equals(markup.attr("linear"))) spineIds.add(idref);
                }
            } else if (markup.type == Markup.TEXT && reading != null) {
                value.append(markup.text);
            } else if (markup.type == Markup.END && reading != null && reading.equals(markup.name)) {
                String found = value.toString().replaceAll("\\s+", " ").trim();
                if (found.length() > 0) {
                    if ("title".equals(reading) && pkg.title == null) pkg.title = found;
                    if ("creator".equals(reading) && pkg.author == null) pkg.author = found;
                    if ("language".equals(reading) && pkg.language == null) pkg.language = found;
                }
                reading = null;
            }
        }
        for (String id : spineIds) {
            Item item = pkg.manifest.get(id);
            if (item == null) continue;
            String type = item.type;
            // Images and the like can be in the spine; only documents are read.
            if (type.length() > 0 && !type.contains("html") && !type.contains("xml")) continue;
            pkg.spine.add(item);
        }
        return pkg;
    }

    // ------------------------------------------------------------------
    // Contents
    // ------------------------------------------------------------------

    static List<Book.Link> contents(ZipFile zip, Package pkg, Map<String, Integer> byPath)
        throws IOException {
        List<Book.Link> links = new ArrayList<Book.Link>();
        Item nav = pkg.nav();
        if (nav != null) {
            byte[] data = bytes(zip, nav.path);
            if (data != null) readNav(text(data), directory(nav.path), byPath, links);
        }
        if (links.isEmpty()) {
            Item ncx = pkg.ncxId == null ? null : pkg.manifest.get(pkg.ncxId);
            if (ncx == null) {
                for (Item item : pkg.manifestOrder) {
                    if (item.type.contains("dtbncx") || item.path.toLowerCase(Locale.ROOT).endsWith(".ncx")) {
                        ncx = item;
                        break;
                    }
                }
            }
            if (ncx != null) {
                byte[] data = bytes(zip, ncx.path);
                if (data != null) readNcx(text(data), directory(ncx.path), byPath, links);
            }
        }
        return links;
    }

    /** The EPUB 3 navigation document's `<nav epub:type="toc">`. */
    static void readNav(String document, String base, Map<String, Integer> byPath, List<Book.Link> links) {
        Markup markup = new Markup(document);
        int navDepth = 0;
        boolean inToc = false;
        int listDepth = 0;
        String href = null;
        StringBuilder label = null;
        while (markup.next()) {
            if (markup.type == Markup.START) {
                if ("nav".equals(markup.name)) {
                    navDepth++;
                    String type = markup.attr("type");
                    if (!inToc && type != null && hasProperty(type, "toc")) {
                        inToc = true;
                        navDepth = 1;
                    }
                } else if (inToc && "ol".equals(markup.name)) {
                    listDepth++;
                } else if (inToc && "a".equals(markup.name) && !markup.selfClosing) {
                    href = markup.attr("href");
                    label = new StringBuilder();
                }
            } else if (markup.type == Markup.TEXT && label != null) {
                label.append(markup.text);
            } else if (markup.type == Markup.END) {
                if ("a".equals(markup.name) && label != null) {
                    if (href != null) add(links, label.toString(), Math.max(0, listDepth - 1), base, href, byPath);
                    label = null;
                    href = null;
                } else if (inToc && "ol".equals(markup.name)) {
                    listDepth--;
                } else if ("nav".equals(markup.name) && inToc) {
                    if (--navDepth <= 0) return;
                }
            }
        }
    }

    /** The EPUB 2 NCX's navMap. */
    static void readNcx(String document, String base, Map<String, Integer> byPath, List<Book.Link> links) {
        Markup markup = new Markup(document);
        int depth = -1;
        boolean inText = false;
        boolean inNavMap = false;
        StringBuilder label = new StringBuilder();
        while (markup.next()) {
            if (markup.type == Markup.START) {
                if ("navmap".equals(markup.name)) inNavMap = true;
                else if (!inNavMap) continue;
                else if ("navpoint".equals(markup.name)) {
                    depth++;
                    label.setLength(0);
                } else if ("text".equals(markup.name)) {
                    inText = true;
                } else if ("content".equals(markup.name)) {
                    String src = markup.attr("src");
                    if (src != null) add(links, label.toString(), Math.max(0, depth), base, src, byPath);
                }
            } else if (markup.type == Markup.TEXT && inText) {
                label.append(markup.text);
            } else if (markup.type == Markup.END) {
                if ("text".equals(markup.name)) inText = false;
                else if ("navpoint".equals(markup.name)) depth--;
                else if ("navmap".equals(markup.name)) inNavMap = false;
            }
        }
    }

    private static void add(List<Book.Link> links, String label, int depth, String base, String href,
                            Map<String, Integer> byPath) {
        int hash = href.indexOf('#');
        String path = resolve(base, hash >= 0 ? href.substring(0, hash) : href);
        String anchor = hash >= 0 ? decode(href.substring(hash + 1)) : null;
        Integer chapter = byPath.get(path);
        if (chapter == null && hash == 0) return;
        if (chapter == null) {
            // Names in the contents and the manifest can differ in case.
            for (Map.Entry<String, Integer> entry : byPath.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(path)) {
                    chapter = entry.getValue();
                    break;
                }
            }
        }
        if (chapter == null) return;
        links.add(new Book.Link(label, depth, chapter.intValue(), anchor));
    }

    // ------------------------------------------------------------------
    // Paths, entries and text
    // ------------------------------------------------------------------

    static ZipFile open(File file) throws IOException {
        try {
            return new ZipFile(file);
        } catch (IOException e) {
            // A file that can't be read at all is not a broken book: say so.
            try {
                new FileInputStream(file).close();
            } catch (FileNotFoundException unreadable) {
                throw new FileNotFoundException("Can\u2019t read " + file.getName());
            }
            throw new IOException("Not an EPUB: " + file.getName() + " is not a zip file");
        }
    }

    static String directory(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    /** `href` taken relative to the directory `base`, with `..` and `.` folded away. */
    static String resolve(String base, String href) {
        String decoded = decode(href);
        String joined = decoded.startsWith("/") ? decoded.substring(1) : base + decoded;
        List<String> parts = new ArrayList<String>();
        for (String part : joined.split("/")) {
            if (part.length() == 0 || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(part);
        }
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append('/');
            out.append(part);
        }
        return out.toString();
    }

    static String decode(String href) {
        if (href.indexOf('%') < 0) return href;
        try {
            return URLDecoder.decode(href.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return href;
        }
    }

    private static ZipEntry find(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);
        if (entry != null) return entry;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry candidate = entries.nextElement();
            if (candidate.getName().equalsIgnoreCase(path)) return candidate;
        }
        return null;
    }

    private static String firstWithSuffix(ZipFile zip, String suffix) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.toLowerCase(Locale.ROOT).endsWith(suffix)) return name;
        }
        return null;
    }

    /** An entry's bytes, or null if it is missing or too large. */
    static byte[] bytes(ZipFile zip, String path) throws IOException {
        ZipEntry entry = find(zip, path);
        if (entry == null || entry.isDirectory()) return null;
        InputStream in = zip.getInputStream(entry);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                entry.getSize() > 0 && entry.getSize() < MAX_ENTRY ? (int) entry.getSize() : 8192);
            byte[] buffer = new byte[16384];
            int total = 0;
            for (int n; (n = in.read(buffer)) > 0; ) {
                total += n;
                if (total > MAX_ENTRY) return null;
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** A document's text, in the encoding its byte-order mark or XML declaration names. */
    static String text(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, Charset.forName("UTF-8"));
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, Charset.forName("UTF-16BE"));
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, Charset.forName("UTF-16LE"));
        }
        String head = new String(data, 0, Math.min(data.length, 200), Charset.forName("ISO-8859-1"));
        Charset charset = Charset.forName("UTF-8");
        int declared = head.indexOf("encoding=");
        if (head.startsWith("<?xml") && declared > 0 && declared + 10 < head.length()) {
            char quote = head.charAt(declared + 9);
            int end = head.indexOf(quote, declared + 10);
            if (end > 0) {
                try {
                    charset = Charset.forName(head.substring(declared + 10, end).trim());
                } catch (RuntimeException e) {
                    // An encoding this phone does not know: UTF-8 is the best guess.
                }
            }
        }
        return new String(data, charset);
    }

    static boolean hasProperty(String properties, String wanted) {
        if (properties == null) return false;
        for (String property : properties.trim().split("\\s+")) {
            if (property.equals(wanted)) return true;
        }
        return false;
    }
}
