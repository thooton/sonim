package com.books.xp3.book;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads a plain text book, such as a Project Gutenberg .txt.
 *
 * Paragraphs are found the way a reader would find them: separated by blank
 * lines when the file has them (lines inside a paragraph, hard-wrapped at 70
 * columns, are joined back up), else one per line. A short line on its own
 * that looks like "Chapter 7", "PART TWO" or "XIV." starts a new chapter.
 * Project Gutenberg's licence header and footer are left out.
 */
public final class PlainText {

    /** Larger files are refused rather than run the phone out of memory. */
    private static final long MAX_FILE = 24L * 1024 * 1024;

    private static final Pattern HEADING = Pattern.compile(
        "(?i)^(chapter|book|part|volume|act|canto|prologue|epilogue|preface|foreword|introduction|afterword|appendix|letter)\\b.{0,50}$");
    private static final Pattern NUMBERED = Pattern.compile("^(?:[IVXLC]{1,8}|\\d{1,3})\\.?$");
    private static final Pattern SCENE = Pattern.compile("^[*\u2022~#\\-=\\s]{3,}$");

    private PlainText() {}

    public static Epub.Info info(File file) throws IOException {
        String text = read(file);
        Header header = header(text);
        return new Epub.Info(header.title, header.author);
    }

    public static Book read(File file, String fallbackTitle) throws IOException {
        return parse(read(file), fallbackTitle);
    }

    static Book parse(String raw, String fallbackTitle) {
        Header header = header(raw);
        String body = raw.substring(header.bodyStart, header.bodyEnd);

        List<String> blocks = paragraphs(body);
        List<Chapter> chapters = new ArrayList<Chapter>();
        Chapter current = new Chapter();
        for (String block : blocks) {
            if (SCENE.matcher(block).matches()) {
                current.sceneBreak();
                continue;
            }
            if (isHeading(block)) {
                // Headings in a row (a contents list, or "CHAPTER I" over its
                // name) stay together; text after them starts the next one,
                // as does a heading the contents list already named.
                if (current.hasBody() || current.hasHeading(block)) {
                    chapters.add(current);
                    current = new Chapter();
                }
                current.append(block, 0, Paragraph.HEADING, 2, true);
                current.end();
                continue;
            }
            current.append(block, 0, Paragraph.BODY, 0, false);
            current.end();
        }
        if (!current.isEmpty()) chapters.add(current);
        String title = header.title != null ? header.title : fallbackTitle;
        return Book.assemble(title, header.author, chapters, null);
    }

    private static boolean isHeading(String block) {
        if (block.length() > 60 || block.indexOf('\n') >= 0) return false;
        if (HEADING.matcher(block).matches() || NUMBERED.matcher(block).matches()) return true;
        // An upper-case line of a few words, such as "THE RETURN": but not
        // one that ends a sentence, which is more likely shouting.
        boolean letters = false;
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (Character.isLowerCase(c)) return false;
            if (Character.isLetter(c)) letters = true;
        }
        char last = block.charAt(block.length() - 1);
        return letters && block.length() <= 40 && last != '!' && last != '?' && last != ',';
    }

    /** The file's paragraphs, each on one line with its white space tidied. */
    static List<String> paragraphs(String text) {
        String[] lines = text.trim().split("\n", -1);
        int blank = 0;
        int filled = 0;
        for (String line : lines) {
            if (line.trim().length() == 0) blank++;
            else filled++;
        }
        // Blank lines between paragraphs, or a paragraph per line?
        boolean byBlankLines = blank > 0 && blank * 8 >= filled;
        List<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() == 0) {
                flush(current, out);
                continue;
            }
            if (!byBlankLines) {
                flush(current, out);
                current.append(trimmed);
                flush(current, out);
                continue;
            }
            if (current.length() > 0) current.append(' ');
            current.append(trimmed);
        }
        flush(current, out);
        return out;
    }

    private static void flush(StringBuilder current, List<String> out) {
        if (current.length() == 0) return;
        out.add(current.toString().replaceAll("[ \t\u000B\f]+", " ").trim());
        current.setLength(0);
    }

    // ------------------------------------------------------------------
    // Project Gutenberg's header and footer
    // ------------------------------------------------------------------

    static final class Header {
        String title;
        String author;
        int bodyStart;
        int bodyEnd;
    }

    static Header header(String text) {
        Header header = new Header();
        header.bodyEnd = text.length();
        int start = markerLine(text, "*** START OF", 0);
        if (start < 0) start = markerLine(text, "***START OF", 0);
        if (start >= 0) {
            String head = text.substring(0, start);
            header.title = field(head, "Title:");
            header.author = field(head, "Author:");
            int lineEnd = text.indexOf('\n', start);
            header.bodyStart = lineEnd < 0 ? text.length() : lineEnd + 1;
            int end = markerLine(text, "*** END OF", header.bodyStart);
            if (end < 0) end = markerLine(text, "***END OF", header.bodyStart);
            if (end < 0) end = markerLine(text, "End of the Project Gutenberg", header.bodyStart);
            if (end < 0) end = markerLine(text, "End of Project Gutenberg", header.bodyStart);
            if (end >= 0) header.bodyEnd = end;
        }
        return header;
    }

    /** The start of the first line at or after `from` that begins with `marker`, ignoring case. */
    private static int markerLine(String text, String marker, int from) {
        String lower = marker.toLowerCase(Locale.ROOT);
        int at = from;
        while (at < text.length()) {
            int end = text.indexOf('\n', at);
            if (end < 0) end = text.length();
            String line = text.substring(at, end).trim();
            if (line.toLowerCase(Locale.ROOT).startsWith(lower)) return at;
            at = end + 1;
        }
        return -1;
    }

    private static String field(String head, String name) {
        for (String line : head.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, name, 0, name.length())) {
                String value = trimmed.substring(name.length()).trim();
                return value.length() == 0 ? null : Chapter.clip(value, 120);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Bytes to text
    // ------------------------------------------------------------------

    static String read(File file) throws IOException {
        if (file.length() > MAX_FILE) throw new IOException(file.getName() + " is too large to open");
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(16, file.length()));
            byte[] buffer = new byte[16384];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return decode(out.toByteArray());
        } finally {
            in.close();
        }
    }

    /** UTF-8 if the bytes are valid UTF-8, else Windows-1252; line ends made '\n'. */
    static String decode(byte[] data) {
        int skip = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            skip = 3;
        }
        String text;
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            text = new String(data, 2, data.length - 2, Charset.forName("UTF-16LE"));
        } else if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            text = new String(data, 2, data.length - 2, Charset.forName("UTF-16BE"));
        } else {
            CharsetDecoder strict = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                CharBuffer chars = strict.decode(ByteBuffer.wrap(data, skip, data.length - skip));
                text = chars.toString();
            } catch (CharacterCodingException e) {
                text = new String(data, skip, data.length - skip, Charset.forName("windows-1252"));
            }
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
