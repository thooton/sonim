package com.mail.xp3.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An HTML mail as plain text for a 240px screen: paragraphs and line breaks
 * kept, lists bulleted, table cells run together, and everything a
 * newsletter hides (styles, scripts, spacer characters) dropped. Links are
 * listed separately, since a d-pad can't follow one in the text.
 */
public final class Html {

    private Html() {}

    private static final Pattern HIDDEN =
        Pattern.compile("(?is)<!--.*?-->|<(head|style|script|title|xml)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern LINK = Pattern.compile(
        "(?is)<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>(.*?)</a\\s*>");

    private static final java.util.Set<String> PARAGRAPH = set("p", "h1", "h2", "h3", "h4", "h5", "h6", "table",
        "blockquote", "ul", "ol", "dl", "pre");
    private static final java.util.Set<String> LINE = set("div", "tr", "section", "article", "header", "footer",
        "center", "form", "dt", "dd", "address", "main", "nav", "aside", "figure", "figcaption", "tbody", "thead");

    public static String text(String html) {
        String s = HIDDEN.matcher(html).replaceAll("");
        StringBuilder out = new StringBuilder();
        boolean pre = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '<') {
                int end = s.indexOf('>', i);
                if (end < 0) break;
                String tag = s.substring(i + 1, end).trim();
                i = end + 1;
                boolean closing = tag.startsWith("/");
                String name = tag.replaceFirst("^/", "").split("[\\s/>]", 2)[0].toLowerCase(Locale.US);
                if (name.equals("br")) {
                    out.append('\n');
                } else if (name.equals("li")) {
                    if (!closing) {
                        line(out);
                        out.append("• ");
                    }
                } else if (name.equals("td") || name.equals("th")) {
                    if (!closing && out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) {
                        out.append(' ');
                    }
                } else if (name.equals("hr")) {
                    line(out);
                    out.append("———\n");
                } else if (PARAGRAPH.contains(name)) {
                    paragraph(out);
                    if (name.equals("pre")) pre = !closing;
                } else if (LINE.contains(name)) {
                    line(out);
                }
                continue;
            }
            int next = s.indexOf('<', i);
            if (next < 0) next = s.length();
            String text = entities(s.substring(i, next)).replaceAll("[\u200b\u200c\u200d\u2060\ufeff\u034f\u00ad]", "");
            i = next;
            if (pre) {
                out.append(text);
                continue;
            }
            text = text.replaceAll("[\\s\u00a0]+", " ");
            boolean atStart = out.length() == 0 || out.charAt(out.length() - 1) == '\n' || out.charAt(out.length() - 1) == ' ';
            if (atStart && text.startsWith(" ")) text = text.substring(1);
            out.append(text);
        }
        // Tidy: no spaces at either end of a line, no more than one blank line.
        String[] lines = out.toString().split("\n", -1);
        StringBuilder tidy = new StringBuilder();
        int blanks = 0;
        for (String line : lines) {
            String trimmed = line.replaceAll("^[ \u00a0]+|[ \u00a0]+$", "");
            if (trimmed.length() == 0) {
                blanks++;
                continue;
            }
            if (tidy.length() > 0) tidy.append(blanks > 0 ? "\n\n" : "\n");
            blanks = 0;
            tidy.append(trimmed);
        }
        return tidy.toString();
    }

    /** Each link's text and address, http, https and mailto only, each address once. */
    public static List<String[]> links(String html) {
        List<String[]> out = new ArrayList<String[]>();
        List<String> seen = new ArrayList<String>();
        Matcher m = LINK.matcher(HIDDEN.matcher(html).replaceAll(""));
        while (m.find()) {
            String url = entities(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3)).trim();
            String lower = url.toLowerCase(Locale.US);
            if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("mailto:")) continue;
            if (seen.contains(url)) continue;
            seen.add(url);
            String label = text(m.group(4)).replaceAll("\\s+", " ").trim();
            out.add(new String[] {label.length() == 0 ? url : label, url});
        }
        return out;
    }

    private static void line(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
    }

    private static void paragraph(StringBuilder out) {
        if (out.length() == 0) return;
        line(out);
        out.append('\n');
    }

    private static final Map<String, String> NAMED = new HashMap<String, String>();

    static {
        String[] pairs = {"amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", "\u00a0",
            "rsquo", "\u2019", "lsquo", "\u2018", "ldquo", "\u201c", "rdquo", "\u201d", "mdash", "\u2014",
            "ndash", "\u2013", "hellip", "\u2026", "copy", "\u00a9", "reg", "\u00ae", "trade", "\u2122",
            "bull", "\u2022", "middot", "\u00b7", "zwnj", "\u200c", "zwj", "\u200d", "shy", "\u00ad",
            "euro", "\u20ac", "pound", "\u00a3", "cent", "\u00a2", "yen", "\u00a5", "deg", "\u00b0",
            "times", "\u00d7", "divide", "\u00f7", "laquo", "\u00ab", "raquo", "\u00bb", "rarr", "\u2192",
            "larr", "\u2190", "eacute", "\u00e9", "egrave", "\u00e8", "aacute", "\u00e1", "agrave", "\u00e0",
            "iacute", "\u00ed", "oacute", "\u00f3", "uacute", "\u00fa", "ntilde", "\u00f1", "uuml", "\u00fc",
            "ouml", "\u00f6", "auml", "\u00e4", "ccedil", "\u00e7", "szlig", "\u00df", "thinsp", " ",
            "ensp", " ", "emsp", " ", "iexcl", "\u00a1", "iquest", "\u00bf", "sect", "\u00a7", "para", "\u00b6"};
        for (int i = 0; i < pairs.length; i += 2) NAMED.put(pairs[i], pairs[i + 1]);
    }

    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,31});");

    /** "&amp;", "&#8217;" and the common named entities, decoded. */
    public static String entities(String text) {
        if (text.indexOf('&') < 0) return text;
        Matcher m = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String name = m.group(1);
            String decoded = null;
            if (name.startsWith("#")) {
                try {
                    int point = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                        ? Integer.parseInt(name.substring(2), 16)
                        : Integer.parseInt(name.substring(1));
                    if (point > 0 && point <= 0x10FFFF) decoded = new String(Character.toChars(point));
                } catch (RuntimeException e) {
                    decoded = null;
                }
            } else {
                decoded = NAMED.get(name);
            }
            out.append(decoded != null ? decoded : m.group());
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    /** Text as HTML: escaped, with its line breaks kept. */
    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .replace("\n", "<br>");
    }

    private static final Pattern BODY = Pattern.compile("(?is)<body[^>]*>(.*)</body>");

    /** The inside of an HTML document's body, for putting inside another. */
    public static String body(String document) {
        Matcher m = BODY.matcher(document);
        return m.find() ? m.group(1) : document;
    }

    private static java.util.Set<String> set(String... names) {
        return new java.util.HashSet<String>(java.util.Arrays.asList(names));
    }
}
