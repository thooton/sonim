package com.list.xp3.data;

/** One line of the checklist. Immutable, so a snapshot can cross threads. */
public final class Item {

    /** Long enough for any real task, short enough to stay a line of a list. */
    public static final int MAX_TEXT = 500;

    public final String text;
    public final boolean done;

    public Item(String text, boolean done) {
        this.text = clean(text);
        this.done = done;
    }

    public Item withText(String next) {
        return new Item(next, done);
    }

    public Item toggled() {
        return new Item(text, !done);
    }

    /**
     * The text as a row can show it: one line, trimmed, bounded.
     *
     * The T9 keyboard can produce a newline, and a row is a single entry in
     * an ordered list, so line breaks fold into spaces rather
     * than turning one item into what looks like two.
     */
    public static String clean(String text) {
        if (text == null) throw new IllegalArgumentException("An item needs some text");
        String folded = text.replaceAll("\\s+", " ").trim();
        if (folded.length() == 0) throw new IllegalArgumentException("An item needs some text");
        if (folded.length() > MAX_TEXT) {
            throw new IllegalArgumentException("An item can be at most " + MAX_TEXT + " characters");
        }
        return folded;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Item)) return false;
        Item item = (Item) other;
        return done == item.done && text.equals(item.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode() * 31 + (done ? 1 : 0);
    }

    @Override
    public String toString() {
        return (done ? "[x] " : "[ ] ") + text;
    }
}
