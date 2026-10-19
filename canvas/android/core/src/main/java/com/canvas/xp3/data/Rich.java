package com.canvas.xp3.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formatting in a typed answer: bold, italic, underline, bulleted and
 * numbered lines, the toolbar of Canvas's own editor.
 *
 * A draft keeps an answer as one string, so formatting is written into it as
 * markers: `**bold**`, `*italic*`, `++underline++`, a backslash before a
 * literal `*`, `+` or `\`, and a line starting "• " or "1. " for a list. A
 * marker toggles its style rather than opening a pair, and every line starts
 * plain: the editor writes what it shows character by character, and a
 * dropped marker can never restyle the rest of an answer.
 *
 * What a student pastes is a different matter. It is real Markdown (from a
 * notes app, a chat, a website), so `fromMarkdown` reads it with Markdown's
 * rules and keeps what Canvas's editor can show, and `fromHtml` does the same
 * for rich text by way of `Html`.
 */
public final class Rich {

    /** The mime type of a typed answer kept in this form; older drafts are text/plain. */
    public static final String MIME = "text/markdown";

    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int UNDERLINE = 4;
    static final int[] STYLES = { BOLD, ITALIC, UNDERLINE };

    /** What starts a bulleted line, in the editor and in a draft alike. */
    public static final String BULLET = "• ";

    private static final Pattern NUMBERED = Pattern.compile("^(\\d{1,9})\\. ");

    private Rich() {}

    /** One style over a stretch of text: [start, end). */
    public static final class Run {
        public final int start;
        public final int end;
        public final int style;

        public Run(int start, int end, int style) {
            this.start = start;
            this.end = end;
            this.style = style;
        }
    }

    /** Text and the styles over it, ready to become spans. */
    public static final class Styled {
        public final String text;
        public final List<Run> runs;

        Styled(String text, List<Run> runs) {
            this.text = text;
            this.runs = runs;
        }
    }

    // ------------------------------------------------------------------
    // A draft's own form
    // ------------------------------------------------------------------

    public static Styled parse(String stored) {
        Builder out = new Builder();
        int style = 0;
        int[] open = new int[STYLES.length];
        String s = stored == null ? "" : stored;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            int toggle = 0;
            if (c == '\\' && next != 0 && next != '\n') {
                out.text.append(next);
                i++;
                continue;
            } else if (c == '\n') {
                out.close(style, open);
                style = 0;
                out.text.append(c);
                continue;
            } else if (c == '*' && next == '*') {
                toggle = BOLD;
                i++;
            } else if (c == '*') {
                toggle = ITALIC;
            } else if (c == '+' && next == '+') {
                toggle = UNDERLINE;
                i++;
            } else {
                out.text.append(c);
                continue;
            }
            int bit = index(toggle);
            if ((style & toggle) != 0) out.run(open[bit], toggle);
            else open[bit] = out.text.length();
            style ^= toggle;
        }
        out.close(style, open);
        return out.styled();
    }

    /** The draft form of text with these styles over it. */
    public static String write(String text, List<Run> runs) {
        int[] mask = new int[text.length()];
        if (runs != null) {
            for (Run run : runs) {
                for (int k = Math.max(0, run.start); k < Math.min(text.length(), run.end); k++) mask[k] |= run.style;
            }
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int on = 0;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '\n') {
                markers(out, on);
                on = 0;
                out.append(c);
                continue;
            }
            if (mask[k] != on) {
                markers(out, on ^ mask[k]);
                on = mask[k];
            }
            if (c == '\\' || c == '*' || c == '+') out.append('\\');
            out.append(c);
        }
        markers(out, on);
        return out.toString();
    }

    /** Text typed before answers had formatting, in the draft form. */
    public static String literal(String text) {
        return write(text == null ? "" : text, null);
    }

    /** The answer without its formatting: a .txt upload, a word count. */
    public static String plain(String stored) {
        return parse(stored).text;
    }

    /**
     * The answer as Canvas's editor would have written it: paragraphs (a
     * single line break kept as <br>), lists, and strong, em and u inside.
     */
    public static String html(String stored) {
        StringBuilder out = new StringBuilder();
        String block = null;
        for (String line : (stored == null ? "" : stored).split("\n", -1)) {
            Styled styled = parse(line);
            String text = styled.text;
            if (text.trim().length() == 0) {
                block = close(out, block);
                continue;
            }
            Matcher numbered = NUMBERED.matcher(text);
            if (text.startsWith(BULLET) || numbered.find()) {
                boolean bullet = text.startsWith(BULLET);
                String kind = bullet ? "ul" : "ol";
                if (!kind.equals(block)) {
                    block = close(out, block);
                    out.append('<').append(kind);
                    if (!bullet && !"1".equals(numbered.group(1))) {
                        out.append(" start=\"").append(Long.parseLong(numbered.group(1))).append('"');
                    }
                    out.append('>');
                    block = kind;
                }
                out.append("<li>");
                inline(out, styled, bullet ? BULLET.length() : numbered.end());
                out.append("</li>");
            } else {
                if ("p".equals(block)) {
                    out.append("<br>");
                } else {
                    block = close(out, block);
                    out.append("<p>");
                    block = "p";
                }
                inline(out, styled, 0);
            }
        }
        close(out, block);
        return out.toString();
    }

    private static String close(StringBuilder out, String block) {
        if (block != null) out.append("</").append(block).append('>');
        return null;
    }

    private static void inline(StringBuilder out, Styled styled, int from) {
        String text = styled.text;
        int start = from;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        int[] mask = new int[text.length()];
        for (Run run : styled.runs) {
            for (int k = run.start; k < run.end; k++) mask[k] |= run.style;
        }
        int k = start;
        while (k < end) {
            int m = mask[k];
            int stop = k;
            while (stop < end && mask[stop] == m) stop++;
            if ((m & BOLD) != 0) out.append("<strong>");
            if ((m & ITALIC) != 0) out.append("<em>");
            if ((m & UNDERLINE) != 0) out.append("<u>");
            out.append(Draft.escape(text.substring(k, stop)));
            if ((m & UNDERLINE) != 0) out.append("</u>");
            if ((m & ITALIC) != 0) out.append("</em>");
            if ((m & BOLD) != 0) out.append("</strong>");
            k = stop;
        }
    }

    private static void markers(StringBuilder out, int bits) {
        if ((bits & BOLD) != 0) out.append("**");
        if ((bits & UNDERLINE) != 0) out.append("++");
        if ((bits & ITALIC) != 0) out.append('*');
    }

    private static int index(int style) {
        return style == BOLD ? 0 : style == ITALIC ? 1 : 2;
    }

    // ------------------------------------------------------------------
    // What a student pastes
    // ------------------------------------------------------------------

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)(\\s+#+)?\\s*$");
    private static final Pattern RULE = Pattern.compile("^([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern LIST_BULLET = Pattern.compile("^[-*+•]\\s+(\\[[ xX]\\]\\s+)?");
    private static final Pattern LIST_NUMBER = Pattern.compile("^(\\d{1,9})[.)]\\s+");

    /**
     * Pasted Markdown in the draft form: emphasis, underline (++ or <u>),
     * lists and headings (as bold lines) kept; code, quotes, rules and links
     * reduced to their text. Text with no Markdown in it comes through as it
     * was.
     */
    public static String fromMarkdown(String markdown) {
        Builder out = new Builder();
        boolean fence = false;
        boolean first = true;
        String source = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        for (String raw : source.split("\n", -1)) {
            String line = raw;
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fence = !fence;
                continue;
            }
            if (!first) out.text.append('\n');
            first = false;
            if (fence) {
                out.text.append(line);
                continue;
            }
            boolean quoted = trimmed.startsWith(">");
            while (trimmed.startsWith(">")) trimmed = trimmed.substring(1).trim();
            int style = 0;
            Matcher m;
            if (RULE.matcher(trimmed).matches()) {
                continue;
            } else if ((m = HEADING.matcher(trimmed)).matches()) {
                trimmed = m.group(1);
                style = BOLD;
            } else if ((m = LIST_BULLET.matcher(trimmed)).find()) {
                out.text.append(BULLET);
                trimmed = trimmed.substring(m.end());
            } else if ((m = LIST_NUMBER.matcher(trimmed)).find()) {
                out.text.append(m.group(1)).append(". ");
                trimmed = trimmed.substring(m.end());
            } else {
                // An ordinary line keeps its indent; only list markers and
                // headings say the leading spaces were Markdown's.
                if (!quoted) trimmed = stripRight(line);
            }
            emphasis(trimmed, style, out);
        }
        return write(out.text.toString(), out.runs);
    }

    /**
     * Pasted rich text (a web page, a document) in the draft form. `Html`
     * already reads untrusted HTML into Markdown for the reader; underline,
     * which the reader has no use for, is carried through it as a pair of
     * private-use characters.
     */
    public static String fromHtml(String html) {
        String marked = html.replaceAll("(?i)<(u|ins)(\\s[^>]*)?>", "")
            .replaceAll("(?i)</(u|ins)\\s*>", "");
        String markdown = Html.toMarkdown(marked);
        return fromMarkdown(markdown.replace("", "<u>").replace("", "</u>"));
    }

    private static String stripRight(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    private static final String[] DELIMITERS = { "***", "___", "**", "__", "++", "~~", "*", "_" };

    private static int styleOf(String delimiter) {
        if (delimiter.length() == 3) return BOLD | ITALIC;
        if (delimiter.equals("++")) return UNDERLINE;
        if (delimiter.equals("~~")) return 0;
        return delimiter.length() == 2 ? BOLD : ITALIC;
    }

    private static final String[][] TAGS = {
        { "<u>", "</u>" }, { "<ins>", "</ins>" }, { "<b>", "</b>" }, { "<strong>", "</strong>" },
        { "<i>", "</i>" }, { "<em>", "</em>" },
    };

    private static int styleOfTag(String open) {
        if (open.equals("<u>") || open.equals("<ins>")) return UNDERLINE;
        if (open.equals("<b>") || open.equals("<strong>")) return BOLD;
        return ITALIC;
    }

    /** Markdown's inline rules, as far as an answer needs them. */
    private static void emphasis(String s, int style, Builder out) {
        int i = 0;
        StringBuilder plain = new StringBuilder();
        String lower = s.toLowerCase(Locale.US);
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && isPunctuation(s.charAt(i + 1))) {
                plain.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    out.add(plain, style);
                    out.add(s.substring(i + 1, end), style);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '!' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                i++;
                continue;
            }
            if (c == '[') {
                int label = s.indexOf("](", i + 1);
                int end = label < 0 ? -1 : s.indexOf(')', label + 2);
                if (end > 0) {
                    out.add(plain, style);
                    String text = s.substring(i + 1, label);
                    String url = s.substring(label + 2, end).trim();
                    emphasis(text, style, out);
                    if (url.length() > 0 && !url.equals(text)) out.add(" (" + url + ")", style);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '<') {
                boolean matched = false;
                for (String[] tag : TAGS) {
                    if (!lower.startsWith(tag[0], i)) continue;
                    int end = lower.indexOf(tag[1], i + tag[0].length());
                    if (end < 0) continue;
                    out.add(plain, style);
                    emphasis(s.substring(i + tag[0].length(), end), style | styleOfTag(tag[0]), out);
                    i = end + tag[1].length();
                    matched = true;
                    break;
                }
                if (matched) continue;
            }
            if (c == '*' || c == '_' || c == '+' || c == '~') {
                boolean matched = false;
                for (String d : DELIMITERS) {
                    if (!s.startsWith(d, i)) continue;
                    int close = closer(s, i, d);
                    if (close < 0) continue;
                    out.add(plain, style);
                    emphasis(s.substring(i + d.length(), close), style | styleOf(d), out);
                    i = close + d.length();
                    matched = true;
                    break;
                }
                if (matched) continue;
                // A run of markers that opened nothing is text, all of it, so
                // "**" is not tried again as two single stars.
                int run = i;
                while (run < s.length() && s.charAt(run) == c) run++;
                plain.append(s, i, run);
                i = run;
                continue;
            }
            plain.append(c);
            i++;
        }
        out.add(plain, style);
    }

    /**
     * Where the delimiter opened at `open` closes, or -1. An opener has text
     * straight after it and a closer straight before it, and underscores
     * only count at the edge of a word, so snake_case and 2 * 3 * 4 stay
     * as they were typed.
     */
    private static int closer(String s, int open, String d) {
        char c = d.charAt(0);
        boolean under = c == '_';
        int after = open + d.length();
        if (after >= s.length() || Character.isWhitespace(s.charAt(after))) return -1;
        if (under && open > 0 && Character.isLetterOrDigit(s.charAt(open - 1))) return -1;
        int j = after + 1;
        while (j < s.length()) {
            if (s.charAt(j) != c) {
                j++;
                continue;
            }
            int run = j;
            while (run < s.length() && s.charAt(run) == c) run++;
            int length = run - j;
            // A closing run can end two emphases at once ("*a **b***"): the
            // inner one takes the first of it, this one the last.
            int at = length == d.length() ? j : length > d.length() && d.length() < 3 ? run - d.length() : -1;
            if (at >= 0 && !Character.isWhitespace(s.charAt(j - 1))
                && !(under && run < s.length() && Character.isLetterOrDigit(s.charAt(run)))) {
                return at;
            }
            j = run;
        }
        return -1;
    }

    private static boolean isPunctuation(char c) {
        return c < 128 && !Character.isLetterOrDigit(c) && !Character.isWhitespace(c);
    }

    private static final class Builder {
        final StringBuilder text = new StringBuilder();
        final List<Run> runs = new ArrayList<>();

        void add(CharSequence part, int style) {
            if (part.length() == 0) return;
            int start = text.length();
            text.append(part);
            for (int bit : STYLES) {
                if ((style & bit) != 0) runs.add(new Run(start, text.length(), bit));
            }
            if (part instanceof StringBuilder) ((StringBuilder) part).setLength(0);
        }

        void run(int start, int style) {
            if (text.length() > start) runs.add(new Run(start, text.length(), style));
        }

        void close(int style, int[] open) {
            for (int bit : STYLES) {
                if ((style & bit) != 0) run(open[index(bit)], bit);
            }
        }

        Styled styled() {
            return new Styled(text.toString(), runs);
        }
    }
}
