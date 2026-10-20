package com.newyorker.xp3.news;

import com.newyorker.xp3.html.Node;

import java.util.ArrayList;
import java.util.List;

/** An article body's HTML as blocks of styled runs. */
final class Blocks {

    /**
     * Inside the body but not the article: promotions, sign-ups, the
     * "You're reading" line, players. (Every paragraph of the article is
     * classed "paywall", so that is no sign.) Matched at the start of a class, the
     * site's component names, since "ad-" inside "lead-standard" is no ad.
     */
    private static final String[] NOT_ARTICLE = {
        "consumer-marketing-unit", "ConsumerMarketingUnit", "journey-unit", "external-link-embed",
        "ExternalLinkEmbed", "newsletter", "Newsletter", "ad__", "ad-", "Ad", "related", "Related",
        "social-icons", "SocialIcons", "ActionBar", "action-bar", "cne-",
        "video", "Video", "audio", "Audio", "product", "Product",
    };

    /** A <br>, kept apart from the source's own newlines, which are only white space. */
    private static final String LINE_BREAK = "\u2028";

    private Blocks() {}

    static void read(Node container, List<Block> into, int imageWidth) {
        List<Block.Run> loose = new ArrayList<Block.Run>();
        for (Node child : container.children()) {
            if (child.isText() || isInline(child)) {
                inline(child, 0, loose);
                continue;
            }
            paragraph(loose, false, Block.PARAGRAPH, "", into);
            loose = new ArrayList<Block.Run>();
            block(child, into, imageWidth);
        }
        paragraph(loose, false, Block.PARAGRAPH, "", into);
    }

    private static void block(Node node, List<Block> into, int imageWidth) {
        if (skipped(node)) return;
        String tag = node.tag;
        if (tag.equals("p")) {
            paragraph(runs(node), node.classContains("dropcap"), Block.PARAGRAPH, "", into);
        } else if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
            paragraph(runs(node), false, Block.HEADING, "", into);
        } else if (tag.equals("blockquote")) {
            List<Block> inner = new ArrayList<Block>();
            read(node, inner, imageWidth);
            for (Block block : inner) {
                into.add(block.kind == Block.PARAGRAPH
                    ? new Block(Block.QUOTE, block.runs, false, "", "")
                    : block);
            }
        } else if (tag.equals("ul") || tag.equals("ol")) {
            int number = 1;
            for (Node item : node.children()) {
                if (!item.is("li")) continue;
                String marker = tag.equals("ol") ? number++ + "." : "•";
                paragraph(runs(item), false, Block.ITEM, marker, into);
            }
        } else if (tag.equals("hr")) {
            if (!into.isEmpty() && into.get(into.size() - 1).kind != Block.BREAK) {
                into.add(new Block(Block.BREAK, new ArrayList<Block.Run>(), false, "", ""));
            }
        } else if (tag.equals("figure") || isPicture(node)) {
            Node img = Article.picture(node, imageWidth);
            if (img != null) {
                into.add(new Block(Block.PICTURE, new ArrayList<Block.Run>(), false,
                    Site.picture(img.attr("src"), imageWidth), Article.caption(node)));
            }
        } else if (tag.equals("img")) {
            String src = Site.picture(node.attr("src"), imageWidth);
            if (src != null) into.add(new Block(Block.PICTURE, new ArrayList<Block.Run>(), false, src, ""));
        } else if (tag.equals("table") || tag.equals("aside") || tag.equals("nav") || tag.equals("footer")) {
            // Tables of figures and asides read poorly at 240 pixels, and are rare.
        } else {
            read(node, into, imageWidth);
        }
    }

    private static boolean isPicture(Node node) {
        return node.classContains("asset-embed") && node.first(each -> each.is("img")) != null
            && !node.classContains("asset-embed__caption")
            || node.classContains("responsive-cartoon") && node.first(each -> each.is("img")) != null;
    }

    private static boolean skipped(Node node) {
        for (String prefix : NOT_ARTICLE) if (node.classStartsWith(prefix)) return true;
        return node.attr("aria-hidden").equals("true") || node.attr("role").equals("presentation");
    }

    private static boolean isInline(Node node) {
        switch (node.tag) {
            case "a": case "abbr": case "b": case "cite": case "code": case "em": case "i":
            case "q": case "s": case "small": case "span": case "strong": case "sub": case "sup":
            case "u": case "time": case "mark": case "br":
                return true;
            default:
                return false;
        }
    }

    static List<Block.Run> runs(Node node) {
        List<Block.Run> runs = new ArrayList<Block.Run>();
        for (Node child : node.children()) inline(child, 0, runs);
        return runs;
    }

    private static void inline(Node node, int style, List<Block.Run> runs) {
        if (node.isText()) {
            add(runs, node.text, style);
            return;
        }
        if (skipped(node)) return;
        String tag = node.tag;
        if (tag.equals("br")) {
            add(runs, LINE_BREAK, style);
            return;
        }
        int inner = style;
        if (tag.equals("em") || tag.equals("i") || tag.equals("cite")) inner |= Block.Run.ITALIC;
        if (tag.equals("strong") || tag.equals("b")) inner |= Block.Run.BOLD;
        for (Node child : node.children()) inline(child, inner, runs);
    }

    private static void add(List<Block.Run> runs, String text, int style) {
        if (text.isEmpty()) return;
        if (!runs.isEmpty() && runs.get(runs.size() - 1).style == style) {
            Block.Run last = runs.remove(runs.size() - 1);
            runs.add(new Block.Run(last.text + text, style));
        } else {
            runs.add(new Block.Run(text, style));
        }
    }

    /**
     * Adds the runs as one block, white space collapsed across them as a
     * browser would ("one <em>two</em> three" keeps its spaces), unless
     * nothing is left once it is.
     */
    private static void paragraph(List<Block.Run> raw, boolean dropCap, int kind, String marker, List<Block> into) {
        List<Block.Run> runs = new ArrayList<Block.Run>();
        boolean space = false;
        char last = '\n';
        for (Block.Run run : raw) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < run.text.length(); i++) {
                char c = run.text.charAt(i);
                if (c == LINE_BREAK.charAt(0)) {
                    c = '\n';
                    space = false;
                } else if (Character.isWhitespace(c) || c == '\u00A0') {
                    space = true;
                    continue;
                } else if (space && last != '\n') {
                    // The space goes with the text before it: "one " then "two".
                    if (out.length() > 0) out.append(' ');
                    else runs.set(runs.size() - 1, new Block.Run(runs.get(runs.size() - 1).text + " ", runs.get(runs.size() - 1).style));
                }
                space = false;
                out.append(c);
                last = c;
            }
            if (out.length() > 0) runs.add(new Block.Run(out.toString(), run.style));
        }
        boolean any = false;
        for (Block.Run run : runs) any |= run.text.trim().length() > 0;
        if (!any) return;
        // Trailing and leading line breaks are the markup's, not the text's.
        trimBreaks(runs);
        into.add(new Block(kind, runs, dropCap, marker, ""));
    }

    private static void trimBreaks(List<Block.Run> runs) {
        while (!runs.isEmpty()) {
            Block.Run first = runs.get(0);
            String text = first.text;
            int start = 0;
            while (start < text.length() && (text.charAt(start) == '\n' || text.charAt(start) == ' ')) start++;
            if (start == 0) break;
            runs.remove(0);
            if (start < text.length()) {
                runs.add(0, new Block.Run(text.substring(start), first.style));
                break;
            }
        }
        while (!runs.isEmpty()) {
            Block.Run last = runs.get(runs.size() - 1);
            String text = last.text;
            int end = text.length();
            while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == ' ')) end--;
            if (end == text.length()) break;
            runs.remove(runs.size() - 1);
            if (end > 0) {
                runs.add(new Block.Run(text.substring(0, end), last.style));
                break;
            }
        }
    }
}
