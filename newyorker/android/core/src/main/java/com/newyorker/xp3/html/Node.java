package com.newyorker.xp3.html;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One element or one run of text in a parsed page.
 *
 * Only what reading a page needs: a tag, its attributes, its children and its
 * text. Scripts, styles and SVG are dropped by the parser before they get
 * here, which is most of what makes the front page's two megabytes small.
 */
public final class Node {

    /** Lower case, or null for a text node. */
    public final String tag;
    /** Decoded, for a text node; null for an element. */
    public final String text;
    final Map<String, String> attributes;
    final List<Node> children = new ArrayList<Node>();
    Node parent;
    private String[] classes;

    Node(String tag, String text, Map<String, String> attributes) {
        this.tag = tag;
        this.text = text;
        this.attributes = attributes;
    }

    public boolean isText() {
        return tag == null;
    }

    public boolean is(String name) {
        return name.equals(tag);
    }

    public Node parent() {
        return parent;
    }

    public List<Node> children() {
        return Collections.unmodifiableList(children);
    }

    /** The attribute's value, or "" when it is absent. */
    public String attr(String name) {
        if (attributes == null) return "";
        String value = attributes.get(name);
        return value == null ? "" : value;
    }

    private String[] classes() {
        if (classes == null) {
            String value = attr("class").trim();
            classes = value.isEmpty() ? new String[0] : value.split("\\s+");
        }
        return classes;
    }

    /** Whether one of its classes is exactly `name`. */
    public boolean hasClass(String name) {
        for (String each : classes()) if (each.equals(name)) return true;
        return false;
    }

    /** Whether one of its classes ends with `suffix`, as "smart-item__hed" ends with "__hed". */
    public boolean classEndsWith(String suffix) {
        for (String each : classes()) if (each.endsWith(suffix)) return true;
        return false;
    }

    /** Whether one of its classes starts with `prefix`, as "SplitScreenContentHeaderDek-juwmcJ" does with a dek's. */
    public boolean classStartsWith(String prefix) {
        for (String each : classes()) if (each.startsWith(prefix)) return true;
        return false;
    }

    /** Whether one of its classes contains `fragment` anywhere. */
    public boolean classContains(String fragment) {
        for (String each : classes()) if (each.contains(fragment)) return true;
        return false;
    }

    public interface Test {
        boolean matches(Node node);
    }

    /** The first element under this one (not itself), in document order, that passes. */
    public Node first(Test test) {
        for (Node child : children) {
            if (child.isText()) continue;
            if (test.matches(child)) return child;
            Node found = child.first(test);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Every element under this one that passes, in document order, without
     * looking inside the ones found: a story inside a story is not two.
     */
    public List<Node> all(Test test) {
        List<Node> found = new ArrayList<Node>();
        collect(test, found);
        return found;
    }

    private void collect(Test test, List<Node> found) {
        for (Node child : children) {
            if (child.isText()) continue;
            if (test.matches(child)) found.add(child);
            else child.collect(test, found);
        }
    }

    /** Whether `ancestor` is this node or holds it. */
    public boolean within(Node ancestor) {
        for (Node at = this; at != null; at = at.parent) if (at == ancestor) return true;
        return false;
    }

    /** Its text with white space collapsed and trimmed, as a reader sees it. */
    public String text() {
        StringBuilder out = new StringBuilder();
        appendText(out);
        return collapse(out);
    }

    private void appendText(StringBuilder out) {
        if (isText()) {
            out.append(text);
            return;
        }
        // A block's text is apart from its neighbours': "<p>a</p><p>b</p>" is "a b".
        boolean block = !INLINE.contains(tag);
        if (block) out.append(' ');
        for (Node child : children) child.appendText(out);
        if (block) out.append(' ');
    }

    private static final java.util.Set<String> INLINE = new java.util.HashSet<String>(java.util.Arrays.asList(
        "a", "abbr", "b", "cite", "code", "em", "i", "q", "s", "small", "span", "strong", "sub", "sup",
        "u", "time", "mark", "del", "ins", "#root"));

    static String collapse(CharSequence raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || c == ' ') {
                space = out.length() > 0;
            } else {
                if (space) out.append(' ');
                space = false;
                out.append(c);
            }
        }
        return out.toString();
    }
}
