package com.books.xp3.book;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads one XHTML document of a book into a Chapter.
 *
 * Only the structure that matters on a 240-pixel page is kept: paragraphs,
 * headings, quotations, list items, scene breaks, italic and bold. The
 * book's style sheets are not read; a few inline hints (centred text, italic
 * or bold set by `style` or an obvious class name) are honoured because books
 * lean on them for epigraphs and emphasis.
 */
final class Html {

    private static final Set<String> BLOCKS = set(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dt", "dd",
        "blockquote", "section", "article", "header", "footer", "aside", "nav", "main",
        "figure", "figcaption", "pre", "table", "tr", "caption", "center", "address",
        "body", "html", "hgroup", "details", "summary", "form", "fieldset", "legend");
    private static final Set<String> VOID = set(
        "br", "hr", "img", "meta", "link", "input", "wbr", "col", "area", "base", "source",
        "param", "embed", "track", "keygen", "image");
    /** Elements whose content is never shown. */
    private static final Set<String> HIDDEN = set(
        "head", "title", "svg", "math", "object", "audio", "video", "canvas", "noscript",
        "template", "iframe", "select", "button", "rt", "rp");
    private static final Set<String> ITALIC = set("i", "em", "cite", "var", "dfn");
    private static final Set<String> BOLD = set("b", "strong");

    /** An open element and what it says about the text inside it. */
    private static final class Frame {
        final String name;
        boolean italic;
        boolean bold;
        boolean centered;
        boolean hidden;
        /** For an `<ol>`, the number of the next item; -1 for any other element. */
        int counter = -1;

        Frame(String name) {
            this.name = name;
        }
    }

    private final Chapter chapter;
    private final List<Frame> stack = new ArrayList<Frame>();

    private Html(Chapter chapter) {
        this.chapter = chapter;
    }

    static void read(String document, Chapter chapter) {
        new Html(chapter).run(document);
    }

    private void run(String document) {
        Markup markup = new Markup(document);
        while (markup.next()) {
            switch (markup.type) {
                case Markup.START:
                    start(markup);
                    break;
                case Markup.END:
                    end(markup.name);
                    break;
                case Markup.TEXT:
                    text(markup.text);
                    break;
                default:
                    break;
            }
        }
        chapter.end();
    }

    private void start(Markup markup) {
        String name = markup.name;
        boolean hidden = hidden();
        if (!hidden) {
            String id = markup.attr("id");
            if (id != null) chapter.anchor(id);
            if ("a".equals(name) && markup.attr("name") != null) chapter.anchor(markup.attr("name"));
        }
        if (VOID.contains(name)) {
            if (hidden) return;
            if ("br".equals(name)) chapter.lineBreak();
            else if ("hr".equals(name)) chapter.sceneBreak();
            return;
        }
        Frame frame = new Frame(name);
        String style = lower(markup.attr("style"));
        String classes = lower(markup.attr("class"));
        frame.hidden = HIDDEN.contains(name) || style.replace(" ", "").contains("display:none")
            || markup.attr("hidden") != null;
        frame.italic = ITALIC.contains(name) || style.contains("italic") || style.contains("oblique")
            || classes.contains("italic");
        frame.bold = BOLD.contains(name) || style.replace(" ", "").contains("font-weight:bold")
            || classes.contains("bold");
        frame.centered = "center".equals(name)
            || "center".equals(lower(markup.attr("align")))
            || (style.contains("text-align") && style.contains("center"))
            || classes.contains("center") || classes.contains("centre");
        if ("ol".equals(name)) {
            frame.counter = 1;
            try {
                String from = markup.attr("start");
                if (from != null) frame.counter = Integer.parseInt(from.trim());
            } catch (NumberFormatException ignored) {
                // A malformed start keeps the default.
            }
        }

        if (!hidden && BLOCKS.contains(name)) chapter.end();
        if (!hidden && ("td".equals(name) || "th".equals(name))) text(" ");
        if (markup.selfClosing) {
            // `<p/>` or `<div/>`: an empty element, which ends what came before.
            return;
        }
        stack.add(frame);
        if (!hidden && "li".equals(name)) bullet();
    }

    private void end(String name) {
        int at = stack.size() - 1;
        while (at >= 0 && !stack.get(at).name.equals(name)) at--;
        if (at < 0) return; // A stray end tag.
        boolean hidden = hidden();
        boolean block = false;
        while (stack.size() > at) {
            Frame frame = stack.remove(stack.size() - 1);
            if (BLOCKS.contains(frame.name)) block = true;
        }
        if (block && !hidden) chapter.end();
    }

    private void text(String piece) {
        if (hidden()) return;
        int kind = Paragraph.BODY;
        int level = 0;
        boolean centered = false;
        int style = 0;
        boolean pre = false;
        for (Frame frame : stack) {
            if (frame.italic) style |= Run.ITALIC;
            if (frame.bold) style |= Run.BOLD;
            if (frame.centered) centered = true;
            String name = frame.name;
            int heading = headingLevel(name);
            if (heading > 0) {
                kind = Paragraph.HEADING;
                level = heading;
            } else if (kind != Paragraph.HEADING) {
                if ("pre".equals(name)) {
                    kind = Paragraph.PRE;
                    pre = true;
                } else if ("blockquote".equals(name) || "dd".equals(name)) {
                    if (kind == Paragraph.BODY) kind = Paragraph.QUOTE;
                } else if ("li".equals(name)) {
                    kind = Paragraph.ITEM;
                }
            }
        }
        if (kind == Paragraph.HEADING) {
            // Headings are set bold already; centring is the book convention.
            style &= ~Run.BOLD;
            centered = true;
        }
        if (pre) chapter.appendVerbatim(piece, style, kind);
        else chapter.append(piece, style, kind, level, centered);
    }

    /** Opens a list item with its bullet, or its number in an `<ol>`. */
    private void bullet() {
        String mark = "\u2022";
        for (int i = stack.size() - 1; i >= 0; i--) {
            Frame frame = stack.get(i);
            if ("ol".equals(frame.name)) {
                mark = (frame.counter++) + ".";
                break;
            }
            if ("ul".equals(frame.name)) break;
        }
        // A no-break space keeps the mark with the first word.
        text(mark + "\u00A0");
    }

    private boolean hidden() {
        for (Frame frame : stack) if (frame.hidden) return true;
        return false;
    }

    private static int headingLevel(String name) {
        if (name.length() == 2 && name.charAt(0) == 'h') {
            char digit = name.charAt(1);
            if (digit >= '1' && digit <= '6') return digit - '0';
        }
        return 0;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static Set<String> set(String... names) {
        Set<String> out = new HashSet<String>();
        for (String name : names) out.add(name);
        return out;
    }
}
