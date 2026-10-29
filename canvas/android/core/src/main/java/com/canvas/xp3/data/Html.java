package com.canvas.xp3.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canvas assignment bodies, turned into the Markdown the detail page renders.
 *
 * Teachers write assignments in Canvas's rich text editor, so what arrives is
 * HTML: paragraphs, headings, lists, the occasional table, and a great deal of
 * markup pasted out of Word. The app already has a renderer that turns a small
 * Markdown dialect into real views with hanging indents and quote rules, so the
 * job here is translation into that dialect rather than a second renderer.
 *
 * Everything the dialect does not have degrades rather than disappears. A table
 * becomes one line per row with its cells separated, because a table 240 pixels
 * wide is unreadable however it is drawn but the words in it are still the
 * assignment. An image becomes its alt text, since the app fetches no files. A
 * nested list flattens, because the renderer has one indent and a second one
 * would cost a third of the width.
 *
 * Nothing here executes or fetches anything: script and style content is
 * dropped, and an attribute is only ever read for a link's address or an
 * image's description.
 */
public final class Html {

    private Html() {}

    /**
     * Converts a Canvas HTML body to Markdown. Plain text passes through, which
     * is what a teacher who typed into the editor without formatting produces.
     */
    public static String toMarkdown(String html) {
        if (html == null) return "";
        if (html.indexOf('<') < 0 && html.indexOf('&') < 0) return html.trim();
        return new Converter(html).run();
    }

    private static final class Converter {

        private final String source;
        private int i;

        private final List<String> lines = new ArrayList<>();
        private final StringBuilder line = new StringBuilder();
        private String marker = "";
        private String quote = "";

        /** Open lists, innermost last: 0 for a bullet list, else the next number. */
        private final List<int[]> lists = new ArrayList<>();
        private int listDepth;
        private int preDepth;
        private int quoteDepth;
        private final StringBuilder pre = new StringBuilder();

        Converter(String source) {
            this.source = source;
        }

        String run() {
            while (i < source.length()) {
                char c = source.charAt(i);
                if (c != '<') {
                    int next = source.indexOf('<', i);
                    if (next < 0) next = source.length();
                    text(source.substring(i, next));
                    i = next;
                    continue;
                }
                if (source.startsWith("<!--", i)) {
                    int end = source.indexOf("-->", i);
                    i = end < 0 ? source.length() : end + 3;
                    continue;
                }
                if (source.startsWith("<!", i) || source.startsWith("<?", i)) {
                    int end = source.indexOf('>', i);
                    i = end < 0 ? source.length() : end + 1;
                    continue;
                }
                Tag tag = tag();
                if (tag == null) {
                    // A stray '<' that opens nothing is a character, not markup.
                    text("<");
                    i++;
                    continue;
                }
                apply(tag);
            }
            flushBlock();
            return join();
        }

        // ----------------------------------------------------------------
        // Scanning
        // ----------------------------------------------------------------

        private Tag tag() {
            int start = i;
            int end = source.indexOf('>', start);
            if (end < 0) return null;
            String inside = source.substring(start + 1, end);
            boolean closing = inside.startsWith("/");
            if (closing) inside = inside.substring(1);
            if (inside.endsWith("/")) inside = inside.substring(0, inside.length() - 1);
            String name = inside.trim();
            int space = firstSpace(name);
            String attributes = space < 0 ? "" : name.substring(space + 1);
            name = (space < 0 ? name : name.substring(0, space)).toLowerCase(Locale.US);
            if (name.length() == 0 || !Character.isLetter(name.charAt(0))) return null;
            i = end + 1;
            return new Tag(name, closing, attributes);
        }

        private static int firstSpace(String value) {
            for (int j = 0; j < value.length(); j++) {
                if (Character.isWhitespace(value.charAt(j))) return j;
            }
            return -1;
        }

        /** The value of one attribute, quoted or not. */
        private static String attribute(String attributes, String name) {
            String lower = attributes.toLowerCase(Locale.US);
            int at = 0;
            while (true) {
                at = lower.indexOf(name, at);
                if (at < 0) return null;
                boolean startsWord = at == 0 || Character.isWhitespace(lower.charAt(at - 1));
                int after = at + name.length();
                while (after < lower.length() && Character.isWhitespace(lower.charAt(after))) after++;
                if (startsWord && after < lower.length() && lower.charAt(after) == '=') {
                    int from = after + 1;
                    while (from < attributes.length() && Character.isWhitespace(attributes.charAt(from))) from++;
                    if (from >= attributes.length()) return null;
                    char quoteChar = attributes.charAt(from);
                    if (quoteChar == '"' || quoteChar == '\'') {
                        int close = attributes.indexOf(quoteChar, from + 1);
                        if (close < 0) return null;
                        return entities(attributes.substring(from + 1, close));
                    }
                    int close = from;
                    while (close < attributes.length() && !Character.isWhitespace(attributes.charAt(close))) close++;
                    return entities(attributes.substring(from, close));
                }
                at += name.length();
            }
        }

        // ----------------------------------------------------------------
        // Tags
        // ----------------------------------------------------------------

        private void apply(Tag tag) {
            String name = tag.name;

            if (name.equals("script") || name.equals("style") || name.equals("head")) {
                if (!tag.closing) skipTo(name);
                return;
            }

            if (name.equals("pre")) {
                if (tag.closing) closePre();
                else openPre();
                return;
            }
            if (preDepth > 0) {
                // Inside a code block only <br> and the closing tag mean
                // anything; the rest is text somebody wrote about code.
                if (name.equals("br")) pre.append('\n');
                return;
            }

            if (name.equals("br")) {
                // In a list item a break continues the item: a bullet split
                // over two rows would read as two pieces of work.
                if (listDepth > 0) space();
                else flushLine();
                return;
            }
            if (name.equals("hr")) {
                flushBlock();
                lines.add(quote + "---");
                flushBlock();
                return;
            }
            if (name.equals("img")) {
                String alt = attribute(tag.attributes, "alt");
                if (alt != null && alt.trim().length() > 0) text("(image: " + alt.trim() + ")");
                return;
            }

            if (heading(name) > 0) {
                flushBlock();
                if (!tag.closing) marker = "######".substring(6 - heading(name)) + " ";
                return;
            }

            if (name.equals("ul") || name.equals("ol")) {
                // A list nested inside another one is flattened onto the same
                // level, so it must not open a new block: a blank line here
                // would split one list into two with a gap down the middle.
                if (listDepth > 0) flushLine();
                else flushBlock();
                if (tag.closing) {
                    if (!lists.isEmpty()) lists.remove(lists.size() - 1);
                    listDepth = Math.max(0, listDepth - 1);
                } else {
                    lists.add(new int[] { name.equals("ol") ? 1 : 0 });
                    listDepth++;
                }
                return;
            }
            if (name.equals("li")) {
                flushLine();
                if (!tag.closing) marker = bullet();
                return;
            }

            if (name.equals("blockquote")) {
                flushBlock();
                if (tag.closing) quoteDepth = Math.max(0, quoteDepth - 1);
                else quoteDepth++;
                quote = quoteDepth > 0 ? "> " : "";
                return;
            }

            if (name.equals("td") || name.equals("th")) {
                // Cells run along one line with a separator, so a row of a
                // table reads as a row rather than as several paragraphs.
                if (!tag.closing && line.length() > 0) line.append(" · ");
                return;
            }
            if (name.equals("tr")) {
                flushLine();
                if (!tag.closing) marker = "- ";
                return;
            }

            if (BLOCKS.contains(name)) {
                flushBlock();
                return;
            }

            if (name.equals("strong") || name.equals("b")) {
                emphasis("**");
                return;
            }
            if (name.equals("em") || name.equals("i")) {
                emphasis("*");
                return;
            }
            if (name.equals("code")) {
                emphasis("`");
                return;
            }
            if (name.equals("a")) {
                link(tag);
            }
        }

        private void skipTo(String name) {
            String close = "</" + name;
            int end = source.toLowerCase(Locale.US).indexOf(close, i);
            i = end < 0 ? source.length() : Math.min(source.length(), source.indexOf('>', end) + 1);
        }

        private void openPre() {
            flushBlock();
            preDepth++;
            pre.setLength(0);
        }

        private void closePre() {
            if (preDepth == 0) return;
            preDepth--;
            if (preDepth > 0) return;
            lines.add(quote + "```");
            String[] rows = pre.toString().split("\n", -1);
            int first = 0;
            int last = rows.length;
            while (first < last && rows[first].trim().isEmpty()) first++;
            while (last > first && rows[last - 1].trim().isEmpty()) last--;
            for (int r = first; r < last; r++) lines.add(quote + rows[r]);
            lines.add(quote + "```");
            flushBlock();
        }

        /**
         * Emphasis markers are only written when there is something between
         * them: Canvas bodies are full of empty <strong></strong> pairs left by
         * an editor, and `****` renders as four asterisks in anything.
         */
        private void emphasis(String mark) {
            if (line.length() > 0 && line.toString().endsWith(mark)) {
                line.setLength(line.length() - mark.length());
                return;
            }
            line.append(mark);
        }

        private void link(Tag tag) {
            if (tag.closing) {
                closeLink();
                return;
            }
            String href = attribute(tag.attributes, "href");
            if (href == null || href.trim().length() == 0 || href.trim().startsWith("javascript:")) {
                pendingHref = null;
                linkStart = -1;
                return;
            }
            // Parentheses in the address would end the link early where the
            // renderer looks for the closing one.
            pendingHref = href.trim().replace("(", "%28").replace(")", "%29");
            linkStart = line.length();
            line.append('[');
        }

        private void closeLink() {
            if (pendingHref == null) return;
            String href = pendingHref;
            pendingHref = null;
            int start = linkStart;
            linkStart = -1;
            // The opening bracket went into a line that has since been flushed,
            // so there is nothing here to close.
            if (start < 0 || start >= line.length() || line.charAt(start) != '[') return;

            String label = line.substring(start + 1);
            if (label.indexOf(']') >= 0) {
                // A label with a bracket in it cannot be written as a link at
                // all: the renderer would stop at the first one and print the
                // rest of the address as text. Keep the words, lose the link.
                line.deleteCharAt(start);
                return;
            }
            // A link with nothing between its tags is a URL somebody pasted
            // into Canvas, which the editor wraps and leaves empty. Its address
            // becomes its label so it is still something to read rather than a
            // pair of empty brackets.
            if (label.length() == 0) line.append(href);
            line.append("](").append(href).append(')');
        }

        private String pendingHref;
        private int linkStart = -1;

        private String bullet() {
            if (lists.isEmpty()) return "- ";
            int[] state = lists.get(lists.size() - 1);
            if (state[0] == 0) return "- ";
            return (state[0]++) + ". ";
        }

        private static int heading(String name) {
            if (name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '1' && name.charAt(1) <= '6') {
                return name.charAt(1) - '0';
            }
            return 0;
        }

        private static final Set<String> BLOCKS = new HashSet<>(Arrays.asList("p", "div", "section", "article",
            "header", "footer", "main", "table", "tbody", "thead", "tfoot", "figure", "figcaption", "dl", "dt", "dd",
            "form", "iframe"));

        // ----------------------------------------------------------------
        // Text and lines
        // ----------------------------------------------------------------

        private void text(String raw) {
            String decoded = entities(raw);
            if (preDepth > 0) {
                pre.append(decoded);
                return;
            }
            // Outside a code block HTML collapses runs of whitespace, and a
            // Canvas body is mostly newlines and indentation between tags.
            for (int j = 0; j < decoded.length(); j++) {
                char c = decoded.charAt(j);
                if (c == ' ') {
                    line.append(' ');
                } else if (Character.isWhitespace(c)) {
                    space();
                } else {
                    line.append(c);
                }
            }
        }

        private void space() {
            if (line.length() > 0 && line.charAt(line.length() - 1) != ' ') line.append(' ');
        }

        private void flushLine() {
            // A link left open across a block boundary can never be closed: its
            // opening bracket is in the line going out now and its address
            // would arrive in the next one. Take the bracket back out and give
            // up on the link rather than printing a stray one.
            if (linkStart >= 0) {
                if (linkStart < line.length() && line.charAt(linkStart) == '[') {
                    line.deleteCharAt(linkStart);
                }
                linkStart = -1;
                pendingHref = null;
            }
            String content = line.toString().trim();
            line.setLength(0);
            String prefix = quote + marker;
            marker = "";
            if (content.length() == 0) return;
            lines.add(prefix + content);
        }

        private void flushBlock() {
            flushLine();
            if (!lines.isEmpty() && lines.get(lines.size() - 1).length() > 0) lines.add("");
        }

        private String join() {
            StringBuilder out = new StringBuilder();
            boolean blank = true;
            for (String text : lines) {
                boolean empty = text.trim().isEmpty();
                if (empty && blank) continue; // Never more than one blank line.
                out.append(text).append('\n');
                blank = empty;
            }
            return out.toString().trim();
        }
    }

    private static final class Tag {

        final String name;
        final boolean closing;
        final String attributes;

        Tag(String name, boolean closing, String attributes) {
            this.name = name;
            this.closing = closing;
            this.attributes = attributes;
        }
    }

    /**
     * The character entities that actually turn up in Canvas bodies, plus every
     * numeric one. An unknown named entity is left as it was written rather
     * than dropped, because "&foo;" on screen is at least the truth.
     */
    static String entities(String value) {
        if (value.indexOf('&') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int end = value.indexOf(';', i + 1);
            if (end < 0 || end - i > 12) {
                out.append(c);
                i++;
                continue;
            }
            String name = value.substring(i + 1, end);
            String replacement = entity(name);
            if (replacement == null) {
                out.append(c);
                i++;
            } else {
                out.append(replacement);
                i = end + 1;
            }
        }
        return out.toString();
    }

    private static String entity(String name) {
        if (name.startsWith("#")) {
            try {
                int code = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                    ? Integer.parseInt(name.substring(2), 16)
                    : Integer.parseInt(name.substring(1));
                if (code <= 0 || code > 0x10FFFF) return null;
                return new String(Character.toChars(code));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return NAMED.get(name);
    }

    private static final Map<String, String> NAMED = new HashMap<>();

    static {
        String[] pairs = { "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ",
            "ndash", "–", "mdash", "—", "hellip", "…", "lsquo", "‘", "rsquo", "’", "ldquo", "“", "rdquo", "”",
            "bull", "•", "middot", "·", "deg", "°", "times", "×", "frac12", "½", "rarr", "→" };
        for (int i = 0; i < pairs.length; i += 2) NAMED.put(pairs[i], pairs[i + 1]);
    }
}
