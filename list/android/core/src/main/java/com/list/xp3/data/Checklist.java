package com.list.xp3.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ordered checklist.
 *
 * Every change bumps a version number. With the chat assistant (since
 * removed) that number let its write of the whole list be refused when the
 * list had moved on since it last read it, and the compare-and-replace below
 * made that check and the write one step. Both are kept, tested, for any
 * future second writer.
 */
public final class Checklist {

    /** A list long enough for anything kept on a phone, and no longer. */
    public static final int MAX_ITEMS = 500;

    /** The items and the version they were read at, taken together. */
    public static final class Snapshot {
        public final List<Item> items;
        public final long version;

        Snapshot(List<Item> items, long version) {
            this.items = items;
            this.version = version;
        }
    }

    private List<Item> items = new ArrayList<Item>();
    private long version;

    public Checklist() {}

    public Checklist(List<Item> initial) {
        if (initial != null) items = bounded(initial);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(Collections.unmodifiableList(new ArrayList<Item>(items)), version);
    }

    public synchronized List<Item> items() {
        return Collections.unmodifiableList(new ArrayList<Item>(items));
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized Item get(int index) {
        return items.get(index);
    }

    public synchronized long version() {
        return version;
    }

    // ------------------------------------------------------------------
    // The person's edits, one item at a time
    // ------------------------------------------------------------------

    /** Inserts at `index`, which may equal the size to append. */
    public synchronized void insert(int index, String text) {
        if (items.size() >= MAX_ITEMS) {
            throw new IllegalArgumentException("The list is full");
        }
        if (index < 0 || index > items.size()) throw new IndexOutOfBoundsException();
        items.add(index, new Item(text, false));
        version++;
    }

    public synchronized void edit(int index, String text) {
        items.set(index, items.get(index).withText(text));
        version++;
    }

    public synchronized void remove(int index) {
        items.remove(index);
        version++;
    }

    public synchronized void toggle(int index) {
        items.set(index, items.get(index).toggled());
        version++;
    }

    /** Takes the item at `from` out and puts it back in at `to`. */
    public synchronized void move(int from, int to) {
        if (to < 0 || to >= items.size()) throw new IndexOutOfBoundsException();
        items.add(to, items.remove(from));
        version++;
    }

    // ------------------------------------------------------------------
    // A second writer's edit: the whole list, if nothing changed underneath
    // ------------------------------------------------------------------

    /**
     * Replaces every item, but only if the list is still at `expected`.
     *
     * @return the new version, or -1 when the list had changed
     */
    public synchronized long replaceIf(long expected, List<Item> next) {
        if (version != expected) return -1;
        items = bounded(next);
        version++;
        return version;
    }

    private static List<Item> bounded(List<Item> next) {
        if (next.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("A list can hold at most " + MAX_ITEMS + " items");
        }
        List<Item> copy = new ArrayList<Item>(next.size());
        for (Item item : next) {
            if (item == null) throw new IllegalArgumentException("An item is missing");
            copy.add(item);
        }
        return copy;
    }
}
