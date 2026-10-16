package com.books.xp3.book;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A forgiving tokenizer for the XML and XHTML inside an EPUB.
 *
 * Books in the wild are not all well formed: unclosed tags, HTML entities a
 * strict XML parser would refuse, stray ampersands. This reads them as a
 * browser would, token by token, and never throws on bad markup. Element and
 * attribute names come back lower case with any namespace prefix removed, so
 * `dc:title` is `title` and `epub:type` is `type`.
 */
public final class Markup {

    public static final int END_OF_INPUT = 0;
    public static final int TEXT = 1;
    public static final int START = 2;
    public static final int END = 3;

    private final String source;
    private int at;

    /** What the last call to next() found. */
    public int type = END_OF_INPUT;
    /** The element name for START and END, else null. */
    public String name;
    /** Decoded text for TEXT, else null. */
    public String text;
    /** True for a START written as `<name/>`. */
    public boolean selfClosing;
    private final Map<String, String> attributes = new HashMap<String, String>();

    public Markup(String source) {
        this.source = source == null ? "" : source;
    }

    /** The named attribute of the current START, or null. */
    public String attr(String key) {
        return attributes.get(key);
    }

    /** Moves to the next token; false at the end of input. */
    public boolean next() {
        name = null;
        text = null;
        selfClosing = false;
        attributes.clear();
        int length = source.length();
        while (at < length) {
            char c = source.charAt(at);
            if (c != '<') {
                int end = source.indexOf('<', at);
                if (end < 0) end = length;
                text = decode(source, at, end);
                at = end;
                type = TEXT;
                return true;
            }
            if (source.startsWith("<!--", at)) {
                at = skipPast("-->", at + 4);
                continue;
            }
            if (source.startsWith("<![CDATA[", at)) {
                int end = source.indexOf("]]>", at + 9);
                if (end < 0) end = length;
                text = source.substring(at + 9, end);
                at = Math.min(length, end + 3);
                type = TEXT;
                return true;
            }
            if (at + 1 < length && (source.charAt(at + 1) == '!' || source.charAt(at + 1) == '?')) {
                at = skipPast(">", at + 2);
                continue;
            }
            if (at + 1 < length && source.charAt(at + 1) == '/') {
                int end = source.indexOf('>', at + 2);
                if (end < 0) end = length;
                name = localName(source.substring(at + 2, end).trim());
                at = Math.min(length, end + 1);
                type = END;
                return true;
            }
            if (at + 1 < length && isNameStart(source.charAt(at + 1))) {
                readStartTag();
                type = START;
                if (!selfClosing && ("script".equals(name) || "style".equals(name))) {
                    // Raw text: its content is never markup, and is never shown.
                    int end = source.toLowerCase(Locale.ROOT).indexOf("</" + name, at);
                    at = end < 0 ? length : end;
                }
                return true;
            }
            // A lone '<' in text, as in "a < b".
            int end = source.indexOf('<', at + 1);
            if (end < 0) end = length;
            text = decode(source, at, end);
            at = end;
            type = TEXT;
            return true;
        }
        type = END_OF_INPUT;
        return false;
    }

    private void readStartTag() {
        int length = source.length();
        int i = at + 1;
        int nameStart = i;
        while (i < length && isNameChar(source.charAt(i))) i++;
        name = localName(source.substring(nameStart, i));
        while (i < length) {
            char c = source.charAt(i);
            if (c == '>') {
                i++;
                break;
            }
            if (c == '/') {
                selfClosing = true;
                i++;
                continue;
            }
            if (!isNameStart(c)) {
                i++;
                continue;
            }
            selfClosing = false;
            int keyStart = i;
            while (i < length && isNameChar(source.charAt(i))) i++;
            String key = localName(source.substring(keyStart, i));
            while (i < length && Character.isWhitespace(source.charAt(i))) i++;
            String value = "";
            if (i < length && source.charAt(i) == '=') {
                i++;
                while (i < length && Character.isWhitespace(source.charAt(i))) i++;
                if (i < length && (source.charAt(i) == '"' || source.charAt(i) == '\'')) {
                    char quote = source.charAt(i);
                    int end = source.indexOf(quote, i + 1);
                    if (end < 0) end = length;
                    value = decode(source, i + 1, end);
                    i = Math.min(length, end + 1);
                } else {
                    int valueStart = i;
                    while (i < length && !Character.isWhitespace(source.charAt(i)) && source.charAt(i) != '>') i++;
                    value = decode(source, valueStart, i);
                }
            }
            if (!attributes.containsKey(key)) attributes.put(key, value);
        }
        at = i;
    }

    private int skipPast(String marker, int from) {
        int end = source.indexOf(marker, from);
        return end < 0 ? source.length() : end + marker.length();
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == ':';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '-' || c == '.';
    }

    static String localName(String raw) {
        int colon = raw.lastIndexOf(':');
        String local = colon >= 0 ? raw.substring(colon + 1) : raw;
        int space = 0;
        while (space < local.length() && !Character.isWhitespace(local.charAt(space))) space++;
        return local.substring(0, space).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------

    private static final Map<String, Character> ENTITIES = new HashMap<String, Character>();

    static {
        String[] named = {
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'",
            "nbsp", "\u00A0", "shy", "\u00AD", "ensp", "\u2002", "emsp", "\u2003", "thinsp", "\u2009",
            "hairsp", "\u200A", "zwnj", "\u200C", "zwj", "\u200D",
            "ndash", "\u2013", "mdash", "\u2014", "horbar", "\u2015", "hellip", "\u2026", "mldr", "\u2026",
            "lsquo", "\u2018", "rsquo", "\u2019", "sbquo", "\u201A", "ldquo", "\u201C", "rdquo", "\u201D",
            "bdquo", "\u201E", "laquo", "\u00AB", "raquo", "\u00BB", "lsaquo", "\u2039", "rsaquo", "\u203A",
            "prime", "\u2032", "Prime", "\u2033", "bull", "\u2022", "middot", "\u00B7", "dagger", "\u2020",
            "Dagger", "\u2021", "sect", "\u00A7", "para", "\u00B6", "copy", "\u00A9", "reg", "\u00AE",
            "trade", "\u2122", "deg", "\u00B0", "plusmn", "\u00B1", "times", "\u00D7", "divide", "\u00F7",
            "frac12", "\u00BD", "frac14", "\u00BC", "frac34", "\u00BE", "sup1", "\u00B9", "sup2", "\u00B2",
            "sup3", "\u00B3", "minus", "\u2212", "euro", "\u20AC", "pound", "\u00A3", "cent", "\u00A2",
            "yen", "\u00A5", "curren", "\u00A4", "iexcl", "\u00A1", "iquest", "\u00BF", "ordf", "\u00AA",
            "ordm", "\u00BA", "not", "\u00AC", "macr", "\u00AF", "acute", "\u00B4", "micro", "\u00B5",
            "cedil", "\u00B8", "uml", "\u00A8", "brvbar", "\u00A6", "oelig", "\u0153", "OElig", "\u0152",
            "scaron", "\u0161", "Scaron", "\u0160", "yuml", "\u00FF", "Yuml", "\u0178", "fnof", "\u0192",
            "circ", "\u02C6", "tilde", "\u02DC", "larr", "\u2190", "rarr", "\u2192", "uarr", "\u2191",
            "darr", "\u2193", "harr", "\u2194", "infin", "\u221E", "ne", "\u2260", "le", "\u2264",
            "ge", "\u2265", "asymp", "\u2248", "alpha", "\u03B1", "beta", "\u03B2", "gamma", "\u03B3",
            "delta", "\u03B4", "pi", "\u03C0", "sigma", "\u03C3", "omega", "\u03C9", "lambda", "\u03BB",
            "mu", "\u03BC", "theta", "\u03B8", "loz", "\u25CA", "spades", "\u2660", "clubs", "\u2663",
            "hearts", "\u2665", "diams", "\u2666", "lowast", "\u2217", "oline", "\u203E",
        };
        for (int i = 0; i < named.length; i += 2) ENTITIES.put(named[i], Character.valueOf(named[i + 1].charAt(0)));
        // Latin-1 letters, from &Agrave; (U+00C0) to &yuml; (U+00FF), by name.
        String[] latin = {
            "Agrave", "Aacute", "Acirc", "Atilde", "Auml", "Aring", "AElig", "Ccedil",
            "Egrave", "Eacute", "Ecirc", "Euml", "Igrave", "Iacute", "Icirc", "Iuml",
            "ETH", "Ntilde", "Ograve", "Oacute", "Ocirc", "Otilde", "Ouml", null,
            "Oslash", "Ugrave", "Uacute", "Ucirc", "Uuml", "Yacute", "THORN", "szlig",
            "agrave", "aacute", "acirc", "atilde", "auml", "aring", "aelig", "ccedil",
            "egrave", "eacute", "ecirc", "euml", "igrave", "iacute", "icirc", "iuml",
            "eth", "ntilde", "ograve", "oacute", "ocirc", "otilde", "ouml", null,
            "oslash", "ugrave", "uacute", "ucirc", "uuml", "yacute", "thorn", null,
        };
        for (int i = 0; i < latin.length; i++) {
            if (latin[i] != null) ENTITIES.put(latin[i], Character.valueOf((char) (0xC0 + i)));
        }
    }

    /** Text with its character and entity references replaced. */
    static String decode(String source, int start, int end) {
        int amp = source.indexOf('&', start);
        if (amp < 0 || amp >= end) return source.substring(start, end);
        StringBuilder out = new StringBuilder(end - start);
        int i = start;
        while (i < end) {
            char c = source.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = source.indexOf(';', i + 1);
            if (semi < 0 || semi >= end || semi - i > 12) {
                out.append(c);
                i++;
                continue;
            }
            String ref = source.substring(i + 1, semi);
            int code = -1;
            if (ref.startsWith("#x") || ref.startsWith("#X")) code = parse(ref.substring(2), 16);
            else if (ref.startsWith("#")) code = parse(ref.substring(1), 10);
            if (code >= 0) {
                if (code > 0 && Character.isValidCodePoint(code)) out.appendCodePoint(code);
                i = semi + 1;
                continue;
            }
            Character named = ENTITIES.get(ref);
            if (named != null) {
                out.append(named.charValue());
                i = semi + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int parse(String digits, int radix) {
        try {
            return digits.isEmpty() ? -1 : Integer.parseInt(digits, radix);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
