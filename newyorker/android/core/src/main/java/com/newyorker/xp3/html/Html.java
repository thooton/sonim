package com.newyorker.xp3.html;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A forgiving HTML parser: whatever a page holds becomes a tree, never an
 * error.
 *
 * Not the HTML5 algorithm, which would be most of this app, but enough of it
 * for pages a server renders for browsers that run no script: void elements,
 * raw-text elements skipped whole, a paragraph closed by the block that
 * follows it, and an end tag with no open element to match ignored. What is
 * thrown away on the way in (scripts, styles, SVG, comments, the head) is
 * most of a page's weight, so the tree stays small enough for the phone.
 */
public final class Html {

    private static final Set<String> VOID = set(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "param", "source", "track", "wbr");

    /** Skipped whole, contents and all: none of it is read. */
    private static final Set<String> SKIPPED = set(
        "script", "style", "svg", "noscript", "template", "iframe", "textarea",
        "title", "head", "math", "select", "button", "form");

    /** Blocks that close an open paragraph, as browsers do. */
    private static final Set<String> CLOSES_P = set(
        "address", "article", "aside", "blockquote", "div", "dl", "fieldset",
        "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header",
        "hr", "main", "nav", "ol", "p", "pre", "section", "table", "ul");

    private final String source;
    private int i;

    private Html(String source) {
        this.source = source;
    }

    /** The page as a tree under one root element, named "#root". */
    public static Node parse(String source) {
        return new Html(source).document();
    }

    private Node document() {
        Node root = new Node("#root", null, null);
        List<Node> open = new ArrayList<Node>();
        open.add(root);
        StringBuilder text = new StringBuilder();
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c != '<' || i + 1 >= length) {
                text.append(c);
                i++;
                continue;
            }
            char next = source.charAt(i + 1);
            if (next == '!' || next == '?') {
                flush(text, open);
                if (source.startsWith("<!--", i)) skipPast("-->", i + 4);
                else skipPast(">", i + 2);
            } else if (next == '/') {
                flush(text, open);
                close(open);
            } else if (isLetter(next)) {
                flush(text, open);
                start(open);
            } else {
                text.append(c);
                i++;
            }
        }
        flush(text, open);
        return root;
    }

    private static boolean isLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private void skipPast(String marker, int from) {
        int end = source.indexOf(marker, from);
        i = end < 0 ? source.length() : end + marker.length();
    }

    private void flush(StringBuilder text, List<Node> open) {
        if (text.length() == 0) return;
        add(open, new Node(null, decode(text), null));
        text.setLength(0);
    }

    private static void add(List<Node> open, Node node) {
        Node parent = open.get(open.size() - 1);
        node.parent = parent;
        parent.children.add(node);
    }

    private String name(int from) {
        int end = from;
        while (end < source.length()) {
            char c = source.charAt(end);
            if (Character.isWhitespace(c) || c == '>' || c == '/') break;
            end++;
        }
        i = end;
        return source.substring(from, end).toLowerCase(Locale.US);
    }

    private void close(List<Node> open) {
        String name = name(i + 2);
        skipPast(">", i);
        for (int at = open.size() - 1; at > 0; at--) {
            if (name.equals(open.get(at).tag)) {
                while (open.size() > at) open.remove(open.size() - 1);
                return;
            }
        }
    }

    private void start(List<Node> open) {
        String name = name(i + 1);
        Map<String, String> attributes = new LinkedHashMap<String, String>();
        boolean selfClosing = attributes(attributes);
        if (SKIPPED.contains(name)) {
            if (!selfClosing && !VOID.contains(name)) skipElement(name);
            return;
        }
        if (CLOSES_P.contains(name)) closeParagraph(open);
        if (name.equals("li")) closeListItem(open);
        Node node = new Node(name, null, attributes);
        add(open, node);
        if (!selfClosing && !VOID.contains(name)) open.add(node);
    }

    /** Closes a paragraph still open in the same block, as a new block does. */
    private static void closeParagraph(List<Node> open) {
        for (int at = open.size() - 1; at > 0; at--) {
            String tag = open.get(at).tag;
            if (tag.equals("p")) {
                while (open.size() > at) open.remove(open.size() - 1);
                return;
            }
            if (!INLINE.contains(tag)) return;
        }
    }

    /** Closes the list item still open in the same list, as a new item does. */
    private static void closeListItem(List<Node> open) {
        for (int at = open.size() - 1; at > 0; at--) {
            String tag = open.get(at).tag;
            if (tag.equals("li")) {
                while (open.size() > at) open.remove(open.size() - 1);
                return;
            }
            if (tag.equals("ul") || tag.equals("ol")) return;
        }
    }

    private static final Set<String> INLINE = set(
        "a", "abbr", "b", "cite", "code", "em", "i", "q", "s", "small", "span",
        "strong", "sub", "sup", "u", "time", "mark", "del", "ins");

    /** Reads attributes up to the tag's '>', returning whether it ended "/>". */
    private boolean attributes(Map<String, String> into) {
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '>') {
                i++;
                return false;
            }
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '>') {
                i += 2;
                return true;
            }
            if (Character.isWhitespace(c) || c == '/') {
                i++;
                continue;
            }
            int start = i;
            while (i < length) {
                char d = source.charAt(i);
                if (Character.isWhitespace(d) || d == '=' || d == '>' || d == '/' && i + 1 < length && source.charAt(i + 1) == '>') break;
                i++;
            }
            String key = source.substring(start, i).toLowerCase(Locale.US);
            while (i < length && Character.isWhitespace(source.charAt(i))) i++;
            String value = "";
            if (i < length && source.charAt(i) == '=') {
                i++;
                while (i < length && Character.isWhitespace(source.charAt(i))) i++;
                if (i < length && (source.charAt(i) == '"' || source.charAt(i) == '\'')) {
                    char quote = source.charAt(i);
                    int end = source.indexOf(quote, i + 1);
                    if (end < 0) end = length;
                    value = source.substring(i + 1, end);
                    i = Math.min(length, end + 1);
                } else {
                    int from = i;
                    while (i < length && !Character.isWhitespace(source.charAt(i)) && source.charAt(i) != '>') i++;
                    value = source.substring(from, i);
                }
                value = decode(value);
            }
            if (!key.isEmpty() && !into.containsKey(key)) into.put(key, value);
        }
        return false;
    }

    /** Skips to just past the matching end tag, counting nested ones of the same name. */
    private void skipElement(String name) {
        int depth = 1;
        while (i < source.length()) {
            int lt = source.indexOf('<', i);
            if (lt < 0) {
                i = source.length();
                return;
            }
            i = lt + 1;
            boolean end = i < source.length() && source.charAt(i) == '/';
            int from = end ? i + 1 : i;
            if (!source.regionMatches(true, from, name, 0, name.length())) continue;
            int after = from + name.length();
            if (after < source.length()) {
                char c = source.charAt(after);
                if (!(Character.isWhitespace(c) || c == '>' || c == '/')) continue;
            }
            // Script and style hold text, never elements: only their end tag counts.
            if (!end && !name.equals("svg") && !name.equals("math")) continue;
            if (end) depth--;
            else if (!selfClosingAt(after)) depth++;
            if (depth == 0) {
                skipPast(">", after);
                return;
            }
        }
    }

    private boolean selfClosingAt(int from) {
        int gt = source.indexOf('>', from);
        return gt > 0 && source.charAt(gt - 1) == '/';
    }

    private static final Map<String, String> ENTITIES = new HashMap<String, String>();

    static {
        String[] pairs = {
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ",
            "mdash", "—", "ndash", "–", "hellip", "…", "lsquo", "‘",
            "rsquo", "’", "ldquo", "“", "rdquo", "”", "sbquo", "‚",
            "bdquo", "„", "laquo", "«", "raquo", "»", "shy", "­",
            "thinsp", " ", "ensp", " ", "emsp", " ", "hairsp", " ",
            "zwj", "‍", "zwnj", "‌", "copy", "©", "reg", "®",
            "trade", "™", "deg", "°", "middot", "·", "bull", "•",
            "times", "×", "divide", "÷", "frac12", "½", "frac14", "¼",
            "frac34", "¾", "sect", "§", "para", "¶", "dagger", "†",
            "Dagger", "‡", "prime", "′", "Prime", "″", "euro", "€",
            "pound", "£", "cent", "¢", "yen", "¥", "iexcl", "¡",
            "iquest", "¿", "diams", "♦", "szlig", "ß", "oelig", "œ",
            "OElig", "Œ", "aelig", "æ", "AElig", "Æ", "oslash", "ø",
            "Oslash", "Ø", "aring", "å", "Aring", "Å", "ccedil", "ç",
            "Ccedil", "Ç", "ntilde", "ñ", "Ntilde", "Ñ", "eth", "ð",
            "thorn", "þ", "minus", "−",
        };
        for (int at = 0; at < pairs.length; at += 2) ENTITIES.put(pairs[at], pairs[at + 1]);
        // The accented vowels, named as HTML names them: &eacute; &Uuml; ...
        String[][] accents = {
            {"grave", "àèìòù", "ÀÈÌÒÙ"},
            {"acute", "áéíóú", "ÁÉÍÓÚ"},
            {"circ", "âêîôû", "ÂÊÎÔÛ"},
            {"uml", "äëïöü", "ÄËÏÖÜ"},
        };
        String vowels = "aeiou";
        for (String[] accent : accents) {
            for (int v = 0; v < 5; v++) {
                ENTITIES.put(vowels.charAt(v) + accent[0], accent[1].substring(v, v + 1));
                ENTITIES.put(Character.toUpperCase(vowels.charAt(v)) + accent[0], accent[2].substring(v, v + 1));
            }
        }
        ENTITIES.put("atilde", "ã");
        ENTITIES.put("otilde", "õ");
        ENTITIES.put("yacute", "ý");
        ENTITIES.put("yuml", "ÿ");
    }

    /** Character references decoded; one it does not know is left as written. */
    static String decode(CharSequence raw) {
        String value = raw.toString();
        if (value.indexOf('&') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        int at = 0;
        while (at < value.length()) {
            char c = value.charAt(at);
            int semi = c == '&' ? value.indexOf(';', at) : -1;
            if (semi < 0 || semi - at > 10) {
                out.append(c);
                at++;
                continue;
            }
            String name = value.substring(at + 1, semi);
            String decoded = null;
            if (name.startsWith("#")) {
                try {
                    int code = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                        ? Integer.parseInt(name.substring(2), 16)
                        : Integer.parseInt(name.substring(1));
                    if (code > 0 && code <= 0x10FFFF) decoded = new String(Character.toChars(code));
                } catch (NumberFormatException ignored) {
                    // Not a number after all: kept as written.
                }
            } else {
                decoded = ENTITIES.get(name);
            }
            if (decoded == null) {
                out.append(c);
                at++;
            } else {
                out.append(decoded);
                at = semi + 1;
            }
        }
        return out.toString();
    }

    private static Set<String> set(String... values) {
        Set<String> result = new HashSet<String>();
        for (String value : values) result.add(value);
        return result;
    }
}
