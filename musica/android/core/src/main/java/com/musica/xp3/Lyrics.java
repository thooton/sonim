package com.musica.xp3;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A song's lyrics, read from its SRT. Each cue is one row, its lines kept
 * together; a gap of BREAK or more between cues (or before the first) is an
 * instrumental break, a row of its own with no text.
 */
public final class Lyrics {

    /** A gap this long between cues shows as an instrumental break. */
    public static final long BREAK = 6000;

    public static final class Row {
        public final long start;
        public final long end;
        /** The cue's text, its lines joined with '\n'; null for a break. */
        public final String text;

        Row(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }

        public boolean isBreak() {
            return text == null;
        }

        @Override public String toString() {
            return start + "-" + end + (text == null ? " (break)" : " " + text);
        }
    }

    public final List<Row> rows;

    private Lyrics(List<Row> rows) {
        this.rows = Collections.unmodifiableList(rows);
    }

    public static final Lyrics NONE = new Lyrics(new ArrayList<Row>());

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    private static final Pattern TIME = Pattern.compile(
        "(\\d{1,3}):(\\d{1,2}):(\\d{1,2})(?:[,.:](\\d{1,3}))?\\s*-+>\\s*"
            + "(\\d{1,3}):(\\d{1,2}):(\\d{1,2})(?:[,.:](\\d{1,3}))?");
    private static final Pattern TAG = Pattern.compile("<[^>]*>|\\{\\\\[^}]*\\}");

    /** Reads an SRT file's bytes, whatever its encoding is likely to be. */
    public static Lyrics parse(byte[] data) {
        return parse(decode(data));
    }

    /** Reads SRT text. Anything that isn't a cue is skipped. */
    public static Lyrics parse(String srt) {
        List<long[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        String[] lines = srt.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        long[] open = null;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher m = TIME.matcher(line);
            if (m.find()) {
                if (open != null) add(times, texts, open, text);
                open = new long[] {millis(m, 1), millis(m, 5)};
                text.setLength(0);
                continue;
            }
            if (open == null) continue;
            if (line.isEmpty()) {
                add(times, texts, open, text);
                open = null;
                continue;
            }
            // A bare number followed by a time line is the next cue's index,
            // which some files run straight on to without a blank line.
            if (line.matches("\\d+") && i + 1 < lines.length && TIME.matcher(lines[i + 1]).find()) {
                continue;
            }
            String clean = TAG.matcher(line).replaceAll("").trim();
            if (clean.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(clean);
        }
        if (open != null) add(times, texts, open, text);
        return build(times, texts);
    }

    private static void add(List<long[]> times, List<String> texts, long[] time, StringBuilder text) {
        if (text.length() == 0) return;
        times.add(time);
        texts.add(text.toString());
    }

    private static long millis(Matcher m, int from) {
        long h = Long.parseLong(m.group(from));
        long min = Long.parseLong(m.group(from + 1));
        long s = Long.parseLong(m.group(from + 2));
        String frac = m.group(from + 3);
        long ms = 0;
        if (frac != null) {
            // "5" after a comma is half a second's 500, not 5 ms.
            String padded = (frac + "00").substring(0, 3);
            ms = Long.parseLong(padded);
        }
        return ((h * 60 + min) * 60 + s) * 1000 + ms;
    }

    private static Lyrics build(List<long[]> times, List<String> texts) {
        // Cues are meant to be in order, but not every file is.
        Integer[] order = new Integer[times.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int c = Long.compare(times.get(a)[0], times.get(b)[0]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        List<Row> rows = new ArrayList<>();
        long last = 0;
        for (int k = 0; k < order.length; k++) {
            long[] t = times.get(order[k]);
            long start = t[0];
            long end = Math.max(start, t[1]);
            if (start - last >= BREAK) rows.add(new Row(last, start, null));
            rows.add(new Row(start, end, texts.get(order[k])));
            last = Math.max(last, end);
        }
        return new Lyrics(rows);
    }

    /**
     * The row being sung at `ms`: the last to have started, so a line stays
     * lit until the next begins. -1 before the first row.
     */
    public int at(long ms) {
        int lo = 0;
        int hi = rows.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (rows.get(mid).start <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** The row with words in it nearest at or before `row`; -1 if none. */
    public int lineAtOrBefore(int row) {
        for (int i = Math.min(row, rows.size() - 1); i >= 0; i--) {
            if (!rows.get(i).isBreak()) return i;
        }
        return -1;
    }

    /** The first row with words after `row`; -1 if none. */
    public int lineAfter(int row) {
        for (int i = Math.max(-1, row) + 1; i < rows.size(); i++) {
            if (!rows.get(i).isBreak()) return i;
        }
        return -1;
    }

    /**
     * Text from bytes: a BOM's say if there is one, otherwise UTF-8 when it
     * is valid UTF-8, otherwise Windows-1252, which is what older Spanish
     * subtitle files mostly are.
     */
    public static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }
}
