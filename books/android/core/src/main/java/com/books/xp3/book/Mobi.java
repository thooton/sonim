package com.books.xp3.book;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Turns an EPUB into a MOBI (Mobipocket 6, the format every Kindle reads).
 *
 * The book's documents are joined, in reading order, into the one HTML file
 * a MOBI holds, with a page break between them. Their markup is cut down to
 * what Mobipocket understands: headings, paragraphs, lists, tables, italic
 * and bold (from tags, inline styles or the usual class names), images and
 * links. Links within the book become `filepos` byte offsets, written as
 * placeholders and filled in once every target's place is known. When the
 * book has contents, an HTML contents page goes first, and the guide points
 * a Kindle's "Table of Contents" at it.
 *
 * The text is cut into 4096-byte records, each PalmDOC compressed, with the
 * multibyte trailing entry that lets a reader decode a record on its own.
 * Images follow as records of their own, then the FLIS, FCIS and end
 * records. Header layouts follow calibre's MOBI 6 writer, which Kindles
 * have read for years.
 */
public final class Mobi {

    /** Readies an image for a Kindle. */
    public interface Images {
        /** The image as a JPEG or GIF a Kindle can show, or null to leave it out. */
        byte[] prepare(byte[] data);
    }

    /** Keeps JPEGs and GIFs as they are and leaves anything else out. */
    public static final Images AS_THEY_ARE = data -> isJpeg(data) || isGif(data) ? data : null;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int RECORD_SIZE = 4096;
    private static final int MOBI_HEADER = 0xE8;
    private static final int PLACEHOLDER = 10;

    private static final Set<String> VOID = set(
        "br", "hr", "img", "image", "meta", "link", "input", "wbr", "col", "area", "base", "source",
        "param", "embed", "track", "keygen");
    private static final Set<String> HIDDEN = set(
        "head", "title", "script", "style", "svg", "math", "object", "audio", "video", "canvas",
        "noscript", "template", "iframe", "select", "button", "rt", "rp");
    /** Kept under their own names. */
    private static final Set<String> KEPT = set(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "li", "dl", "dt", "dd",
        "pre", "table", "tr", "td", "th", "caption", "sub", "sup", "b", "i", "u", "s", "strike",
        "small", "big", "code", "tt", "em", "strong", "del", "ins", "a");
    private static final Set<String> AS_ITALIC = set("cite", "var", "dfn");
    private static final Set<String> AS_DIV = set(
        "div", "section", "article", "header", "footer", "aside", "main", "nav", "figure",
        "figcaption", "address", "center", "hgroup", "details", "summary");
    private static final Set<String> ALIGNED = set(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "td", "th");

    private Mobi() {}

    /**
     * Writes `epub` as a MOBI to `target`, replacing what is there. The file
     * is written beside it under a hidden name and renamed into place, so a
     * failure never leaves half a book.
     */
    public static void convert(File epub, File target, Images images) throws IOException {
        File part = new File(target.getParentFile(), "." + target.getName() + ".part");
        FileOutputStream file = new FileOutputStream(part);
        try {
            OutputStream out = new BufferedOutputStream(file, 64 * 1024);
            convert(epub, images, out);
            out.flush();
            file.getFD().sync();
        } catch (IOException | RuntimeException | Error e) {
            // Out of memory included: never leave half a book behind.
            file.close();
            part.delete();
            throw e;
        }
        file.close();
        if (!part.renameTo(target)) {
            part.delete();
            throw new IOException("Could not save " + target.getName());
        }
    }

    /** The MOBI file's bytes. */
    static byte[] convert(File epub, Images images) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        convert(epub, images, out);
        return out.toByteArray();
    }

    private static void convert(File epub, Images images, OutputStream out) throws IOException {
        ZipFile zip = Epub.open(epub);
        try {
            new Writer(zip, Epub.readPackage(zip), images).write(out);
        } finally {
            zip.close();
        }
    }

    // ------------------------------------------------------------------
    // The text
    // ------------------------------------------------------------------

    private static final class Frame {
        final String name;
        final String close;
        final boolean hidden;
        final boolean pre;

        Frame(String name, String close, boolean hidden, boolean pre) {
            this.name = name;
            this.close = close;
            this.hidden = hidden;
            this.pre = pre;
        }
    }

    private static final class Writer {
        final ZipFile zip;
        final Epub.Package pkg;
        final Images images;
        final Bytes html = new Bytes();
        /** Where each document and each element with an id begins, by key(). */
        final Map<String, Integer> targets = new HashMap<String, Integer>();
        /** Placeholders to fill: where the digits are, and the key they point at. */
        final List<Integer> patchAt = new ArrayList<Integer>();
        final List<String> patchTo = new ArrayList<String>();
        final Set<String> documents = new HashSet<String>();
        /** Image paths in the book to their record index (1-based); 0 for one left out. */
        final Map<String, Integer> imageIndex = new HashMap<String, Integer>();
        final List<byte[]> imageData = new ArrayList<byte[]>();
        boolean hasCover;

        Writer(ZipFile zip, Epub.Package pkg, Images images) {
            this.zip = zip;
            this.pkg = pkg;
            this.images = images;
        }

        void write(OutputStream out) throws IOException {
            if (pkg.spine.isEmpty()) throw new IOException("This book has no text to convert");
            Map<String, Integer> byPath = new HashMap<String, Integer>();
            for (int i = 0; i < pkg.spine.size(); i++) {
                byPath.put(pkg.spine.get(i).path, Integer.valueOf(i));
                documents.add(pkg.spine.get(i).path.toLowerCase(Locale.ROOT));
            }
            Epub.Item cover = pkg.coverImage();
            if (cover != null) hasCover = image(cover.path) == 1;
            List<Book.Link> links = Epub.contents(zip, pkg, byPath);

            html.append("<html><head><guide>");
            if (!links.isEmpty()) {
                html.append("<reference title=\"Table of Contents\" type=\"toc\" ");
                filepos("#contents");
                html.append(" />");
            }
            html.append("<reference title=\"Beginning\" type=\"text\" ");
            filepos(key(pkg.spine.get(0).path, null));
            html.append(" /></guide></head><body>");

            if (!links.isEmpty()) {
                targets.put("#contents", Integer.valueOf(html.size));
                html.append("<h2>Contents</h2>");
                for (Book.Link link : links) {
                    String label = link.title.replaceAll("[\\s\\u00A0]+", " ").trim();
                    if (label.length() == 0) continue;
                    html.append("<p>");
                    for (int d = 0; d < link.depth; d++) html.append("    ");
                    html.append("<a ");
                    filepos(key(pkg.spine.get(link.chapter).path, link.anchor));
                    html.append(">").append(escape(label)).append("</a></p>");
                }
                html.append("<mbp:pagebreak />");
            }

            for (int i = 0; i < pkg.spine.size(); i++) {
                Epub.Item item = pkg.spine.get(i);
                if (i > 0) html.append("<mbp:pagebreak />");
                String start = key(item.path, null);
                if (!targets.containsKey(start)) targets.put(start, Integer.valueOf(html.size));
                byte[] data = Epub.bytes(zip, item.path);
                if (data != null) document(Epub.text(data), item.path);
            }
            html.append("</body></html>");
            patch();
            assemble(html.toByteArray(), out);
        }

        /** One spine document's body, as Mobipocket HTML. */
        void document(String source, String path) throws IOException {
            String base = Epub.directory(path);
            Markup markup = new Markup(source);
            boolean inBody = source.toLowerCase(Locale.ROOT).indexOf("<body") < 0;
            Deque<Frame> open = new ArrayDeque<Frame>();
            while (markup.next()) {
                if (markup.type == Markup.START) {
                    String name = markup.name;
                    if ("body".equals(name)) {
                        inBody = true;
                        anchor(path, markup.attr("id"));
                        continue;
                    }
                    if (!inBody || "html".equals(name)) continue;
                    start(markup, path, base, open);
                } else if (markup.type == Markup.END) {
                    if ("body".equals(markup.name) || "html".equals(markup.name)) {
                        closeAll(open);
                        inBody = false;
                    } else if (inBody) {
                        end(markup.name, open);
                    }
                } else if (markup.type == Markup.TEXT && inBody) {
                    Frame top = open.peek();
                    if (top != null && top.hidden) continue;
                    String text = markup.text;
                    if (top == null || !top.pre) text = text.replaceAll("[ \\t\\r\\n\\f]+", " ");
                    html.append(escape(text));
                }
            }
            closeAll(open);
        }

        void start(Markup markup, String path, String base, Deque<Frame> open) throws IOException {
            String name = markup.name;
            Frame parent = open.peek();
            boolean parentHidden = parent != null && parent.hidden;
            String style = lower(markup.attr("style")).replace(" ", "");
            boolean hidden = parentHidden || HIDDEN.contains(name) || style.contains("display:none")
                || markup.attr("hidden") != null;

            if ("img".equals(name) || "image".equals(name)) {
                // Images are kept even inside an SVG, where EPUB covers often sit.
                String src = markup.attr("img".equals(name) ? "src" : "href");
                if (src == null || src.startsWith("data:")) return;
                int index = image(Epub.resolve(base, src));
                if (index > 0) html.append("<img recindex=\"").append(pad(index, 5)).append("\" />");
                return;
            }
            if (!hidden) {
                anchor(path, markup.attr("id"));
                if ("a".equals(name)) anchor(path, markup.attr("name"));
            }
            if (VOID.contains(name)) {
                if (hidden) return;
                if ("br".equals(name)) html.append("<br />");
                else if ("hr".equals(name)) html.append("<hr />");
                return;
            }

            StringBuilder close = new StringBuilder();
            if (!hidden) {
                String tag = KEPT.contains(name) ? name
                    : AS_ITALIC.contains(name) ? "i"
                    : AS_DIV.contains(name) ? "div"
                    : null;
                String classes = lower(markup.attr("class"));
                boolean italic = !"i".equals(tag) && !"em".equals(tag)
                    && (style.contains("font-style:italic") || style.contains("font-style:oblique")
                        || classes.contains("italic"));
                boolean bold = !"b".equals(tag) && !"strong".equals(tag)
                    && (style.contains("font-weight:bold") || style.contains("font-weight:700")
                        || classes.contains("bold"));
                if (tag != null) {
                    html.append("<").append(tag);
                    if ("a".equals(tag)) link(markup.attr("href"), path, base);
                    if (ALIGNED.contains(tag)) {
                        String align = align(name, lower(markup.attr("align")), style);
                        if (align != null) html.append(" align=\"").append(align).append("\"");
                    }
                    if ("td".equals(tag) || "th".equals(tag)) {
                        attribute("colspan", digits(markup.attr("colspan")));
                        attribute("rowspan", digits(markup.attr("rowspan")));
                    }
                    if ("ol".equals(tag)) attribute("start", digits(markup.attr("start")));
                    html.append(">");
                }
                if (bold) html.append("<b>");
                if (italic) html.append("<i>");
                if (italic) close.append("</i>");
                if (bold) close.append("</b>");
                if (tag != null) close.append("</").append(tag).append(">");
            }
            boolean pre = "pre".equals(name) || (parent != null && parent.pre);
            Frame frame = new Frame(name, close.toString(), hidden, pre);
            if (markup.selfClosing) html.append(frame.close);
            else open.push(frame);
        }

        void end(String name, Deque<Frame> open) {
            boolean found = false;
            for (Frame frame : open) {
                if (frame.name.equals(name)) {
                    found = true;
                    break;
                }
            }
            // An end tag with no start is ignored, as a browser would.
            if (!found) return;
            while (!open.isEmpty()) {
                Frame frame = open.pop();
                html.append(frame.close);
                if (frame.name.equals(name)) return;
            }
        }

        void closeAll(Deque<Frame> open) {
            while (!open.isEmpty()) html.append(open.pop().close);
        }

        void link(String href, String path, String base) {
            if (href == null || href.length() == 0) return;
            String lower = href.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("mailto:")) {
                html.append(" href=\"").append(escape(href)).append("\"");
                return;
            }
            int hash = href.indexOf('#');
            String target = hash == 0 ? path : Epub.resolve(base, hash > 0 ? href.substring(0, hash) : href);
            // A link out of the reading order, to a note left out, say, goes nowhere.
            if (!documents.contains(target.toLowerCase(Locale.ROOT))) return;
            String anchor = hash >= 0 ? Epub.decode(href.substring(hash + 1)) : null;
            html.append(" ");
            filepos(key(target, anchor));
        }

        void anchor(String path, String id) {
            if (id == null || id.length() == 0) return;
            String key = key(path, id);
            if (!targets.containsKey(key)) targets.put(key, Integer.valueOf(html.size));
        }

        void attribute(String name, String value) {
            if (value != null) html.append(" ").append(name).append("=\"").append(value).append("\"");
        }

        void filepos(String key) {
            html.append("filepos=");
            patchAt.add(Integer.valueOf(html.size));
            patchTo.add(key);
            html.append("0000000000");
        }

        /** Fills every filepos in; a missing anchor falls back to its document's start. */
        void patch() {
            for (int i = 0; i < patchAt.size(); i++) {
                String key = patchTo.get(i);
                Integer at = targets.get(key);
                if (at == null) {
                    int hash = key.indexOf('#', 1);
                    if (hash > 0) at = targets.get(key.substring(0, hash));
                }
                html.set(patchAt.get(i).intValue(), pad(at == null ? 0 : at.intValue(), PLACEHOLDER));
            }
        }

        /** The record index of the image at `path`, adding it on first use; 0 if it can't be used. */
        int image(String path) throws IOException {
            String key = path.toLowerCase(Locale.ROOT);
            Integer known = imageIndex.get(key);
            if (known != null) return known.intValue();
            int index = 0;
            byte[] data = Epub.bytes(zip, path);
            byte[] ready = data == null ? null : images.prepare(data);
            if (ready != null && ready.length > 0) {
                imageData.add(ready);
                index = imageData.size();
            }
            imageIndex.put(key, Integer.valueOf(index));
            return index;
        }

        // --------------------------------------------------------------
        // The file
        // --------------------------------------------------------------

        void assemble(byte[] text, OutputStream out) throws IOException {
            List<byte[]> records = new ArrayList<byte[]>();
            records.add(null);
            for (int at = 0; at < text.length; at += RECORD_SIZE) {
                int n = Math.min(RECORD_SIZE, text.length - at);
                int overlap = overlap(text, at, n);
                byte[] packed = PalmDoc.compress(text, at, n);
                byte[] record = Arrays.copyOf(packed, packed.length + overlap + 1);
                System.arraycopy(text, at + n, record, packed.length, overlap);
                record[record.length - 1] = (byte) overlap;
                records.add(record);
            }
            int lastText = records.size() - 1;
            int firstImage = imageData.isEmpty() ? -1 : records.size();
            records.addAll(imageData);
            int lastContent = records.size() - 1;
            int flis = records.size();
            records.add(FLIS);
            int fcis = records.size();
            records.add(fcis(text.length));
            records.add(EOF);
            records.set(0, recordZero(text.length, lastText, firstImage, lastContent, flis, fcis));
            database(records, out);
        }

        byte[] recordZero(int textLength, int lastText, int firstImage, int lastContent, int flis, int fcis) {
            String title = pkg.title == null ? "Untitled" : pkg.title.replaceAll("\\s+", " ").trim();
            byte[] name = title.getBytes(UTF8);
            byte[] exth = exth(title);

            Out out = new Out();
            // The PalmDOC header.
            out.u16(2).u16(0).u32(textLength).u16(lastText).u16(RECORD_SIZE).u16(0).u16(0);
            // The MOBI header.
            out.ascii("MOBI").u32(MOBI_HEADER).u32(2).u32(65001).u32(new Random().nextInt()).u32(6);
            for (int i = 0; i < 10; i++) out.u32(0xFFFFFFFF);
            out.u32(lastText + 1);
            out.u32(16 + MOBI_HEADER + exth.length).u32(name.length);
            out.u32(locale(pkg.language)).u32(0).u32(0);
            out.u32(6);
            out.u32(firstImage < 0 ? 0xFFFFFFFF : firstImage);
            out.u32(0).u32(0).u32(0).u32(0);
            out.u32(0x50);
            out.zeros(32);
            out.u32(0xFFFFFFFF);
            out.u32(0xFFFFFFFF).u32(0xFFFFFFFF).u32(0).u32(0);
            out.zeros(8);
            out.u16(1).u16(lastContent);
            out.u32(1);
            out.u32(fcis).u32(1).u32(flis).u32(1);
            out.zeros(8);
            out.u32(0xFFFFFFFF).u32(0).u32(0xFFFFFFFF).u32(0xFFFFFFFF);
            // Extra record data: the multibyte trailing entry, nothing else.
            out.u32(1);
            out.u32(0xFFFFFFFF);
            out.bytes(exth);
            out.bytes(name);
            out.zeros(2);
            while (out.size() % 4 != 0) out.zeros(1);
            return out.toByteArray();
        }

        byte[] exth(String title) {
            List<byte[]> entries = new ArrayList<byte[]>();
            String id = Long.toHexString(new Random().nextLong() & Long.MAX_VALUE);
            if (pkg.author != null) entries.add(entry(100, pkg.author.getBytes(UTF8)));
            entries.add(entry(113, id.getBytes(UTF8)));
            if (hasCover) {
                entries.add(entry(201, new Out().u32(0).toByteArray()));
                entries.add(entry(203, new Out().u32(0).toByteArray()));
            }
            entries.add(entry(501, "EBOK".getBytes(UTF8)));
            entries.add(entry(503, title.getBytes(UTF8)));
            entries.add(entry(504, id.getBytes(UTF8)));
            if (pkg.language != null) entries.add(entry(524, pkg.language.getBytes(UTF8)));
            int length = 12;
            for (byte[] entry : entries) length += entry.length;
            Out out = new Out().ascii("EXTH").u32(length).u32(entries.size());
            for (byte[] entry : entries) out.bytes(entry);
            while (out.size() % 4 != 0) out.zeros(1);
            return out.toByteArray();
        }

        void database(List<byte[]> records, OutputStream file) throws IOException {
            String title = pkg.title == null ? "Untitled" : pkg.title;
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < title.length() && name.length() < 31; i++) {
                char c = title.charAt(i);
                name.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ? c : '_');
            }
            byte[] palmName = Arrays.copyOf(name.toString().getBytes(UTF8), 32);
            long now = System.currentTimeMillis() / 1000;
            Out out = new Out();
            out.bytes(palmName);
            out.u16(0).u16(0).u32((int) now).u32((int) now).u32(0).u32(0).u32(0).u32(0);
            out.ascii("BOOK").ascii("MOBI");
            out.u32(2 * records.size() - 1).u32(0).u16(records.size());
            int offset = 78 + 8 * records.size() + 2;
            for (int i = 0; i < records.size(); i++) {
                out.u32(offset).u32(2 * i);
                offset += records.get(i).length;
            }
            out.zeros(2);
            out.writeTo(file);
            for (byte[] record : records) file.write(record);
        }
    }

    /** How many bytes of the next record finish the character the record ends in. */
    static int overlap(byte[] text, int at, int length) {
        int end = at + length;
        if (end >= text.length) return 0;
        int lead = end - 1;
        while (lead > at && lead > end - 4 && (text[lead] & 0xC0) == 0x80) lead--;
        int c = text[lead] & 0xFF;
        int size = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return Math.max(0, Math.min(lead + size - end, text.length - end));
    }

    private static final byte[] FLIS = {
        'F', 'L', 'I', 'S', 0, 0, 0, 8, 0, 0x41, 0, 0, 0, 0, 0, 0,
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 1, 0, 3, 0, 0, 0, 3, 0, 0, 0, 1,
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
    };

    private static final byte[] EOF = {(byte) 0xE9, (byte) 0x8E, 0x0D, 0x0A};

    private static byte[] fcis(int textLength) {
        return new Out().ascii("FCIS").u32(0x14).u32(0x10).u32(1).u32(textLength)
            .u32(0).u32(0x20).u32(8).u16(1).u16(1).u32(0).toByteArray();
    }

    private static byte[] entry(int type, byte[] data) {
        return new Out().u32(type).u32(8 + data.length).bytes(data).toByteArray();
    }

    /** Mobipocket's language code: a Windows LANGID, by the language's primary subtag. */
    static int locale(String language) {
        if (language == null) return 0;
        String primary = language.trim().toLowerCase(Locale.ROOT);
        int dash = primary.indexOf('-');
        if (dash >= 0) primary = primary.substring(0, dash);
        String[] codes = {
            "en", "9", "fr", "12", "de", "7", "es", "10", "it", "16", "nl", "19", "pt", "22",
            "ru", "25", "ja", "17", "zh", "4", "sv", "29", "da", "6", "no", "20", "nb", "20",
            "fi", "11", "pl", "21", "cs", "5", "el", "8", "he", "13", "hu", "14", "ko", "18",
            "tr", "31", "ar", "1", "ca", "3", "la", "0",
        };
        for (int i = 0; i < codes.length; i += 2) {
            if (codes[i].equals(primary)) return Integer.parseInt(codes[i + 1]);
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    public static boolean isJpeg(byte[] data) {
        return data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
    }

    public static boolean isGif(byte[] data) {
        return data.length > 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8';
    }

    private static String key(String path, String anchor) {
        String file = path.toLowerCase(Locale.ROOT);
        return anchor == null || anchor.length() == 0 ? file : file + "#" + anchor;
    }

    private static String align(String name, String attribute, String style) {
        if ("center".equals(name)) return "center";
        String value = attribute;
        int at = style.indexOf("text-align:");
        if (at >= 0) {
            int end = style.indexOf(';', at);
            value = style.substring(at + 11, end < 0 ? style.length() : end);
        }
        if ("center".equals(value) || "right".equals(value) || "justify".equals(value)) return value;
        return null;
    }

    private static String digits(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() == 0 || trimmed.length() > 4) return null;
        for (int i = 0; i < trimmed.length(); i++) if (!Character.isDigit(trimmed.charAt(i))) return null;
        return trimmed;
    }

    private static String pad(int value, int width) {
        StringBuilder out = new StringBuilder(Integer.toString(value));
        while (out.length() < width) out.insert(0, '0');
        return out.toString();
    }

    static String escape(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String with = c == '&' ? "&amp;" : c == '<' ? "&lt;" : c == '>' ? "&gt;" : c == '"' ? "&quot;" : null;
            if (with == null) {
                if (out != null) out.append(c);
                continue;
            }
            if (out == null) out = new StringBuilder(text.length() + 16).append(text, 0, i);
            out.append(with);
        }
        return out == null ? text : out.toString();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static Set<String> set(String... names) {
        return new HashSet<String>(Arrays.asList(names));
    }

    /** UTF-8 text that grows, and whose placeholders can be overwritten. */
    private static final class Bytes {
        byte[] data = new byte[64 * 1024];
        int size;

        Bytes append(String text) {
            byte[] bytes = text.getBytes(UTF8);
            if (size + bytes.length > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, size + bytes.length));
            }
            System.arraycopy(bytes, 0, data, size, bytes.length);
            size += bytes.length;
            return this;
        }

        void set(int at, String ascii) {
            for (int i = 0; i < ascii.length(); i++) data[at + i] = (byte) ascii.charAt(i);
        }

        byte[] toByteArray() {
            return Arrays.copyOf(data, size);
        }
    }

    /** Big-endian fields, as PalmOS wrote them. */
    private static final class Out extends ByteArrayOutputStream {
        Out u16(int value) {
            write(value >> 8);
            write(value);
            return this;
        }

        Out u32(int value) {
            write(value >>> 24);
            write(value >>> 16);
            write(value >>> 8);
            write(value);
            return this;
        }

        Out ascii(String text) {
            for (int i = 0; i < text.length(); i++) write(text.charAt(i));
            return this;
        }

        Out zeros(int count) {
            for (int i = 0; i < count; i++) write(0);
            return this;
        }

        Out bytes(byte[] data) {
            write(data, 0, data.length);
            return this;
        }
    }
}
