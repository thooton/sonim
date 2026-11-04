package com.musica.xp3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The order songs play in: the library's A to Z, or shuffled. Songs are
 * known by an id (their path), so the library can be reloaded under it.
 */
public final class Queue {

    public enum Repeat { OFF, ALL, ONE }

    private final List<String> library = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private final Random random;
    private boolean shuffled;
    private int at = -1;

    public Queue(Random random) {
        this.random = random;
    }

    /** The songs, in library order; keeps the current one current if it is still there. */
    public void setLibrary(List<String> ids) {
        String current = current();
        library.clear();
        library.addAll(ids);
        rebuild(current);
    }

    public boolean isShuffled() {
        return shuffled;
    }

    /** Turns shuffle on or off; the current song stays current (and first when shuffling). */
    public void setShuffled(boolean on) {
        shuffled = on;
        rebuild(current());
    }

    private void rebuild(String current) {
        order.clear();
        order.addAll(library);
        if (shuffled) {
            Collections.shuffle(order, random);
            if (current != null && order.remove(current)) order.add(0, current);
        }
        at = current == null ? -1 : order.indexOf(current);
    }

    /** Makes `id` current; it must be in the library. */
    public void play(String id) {
        at = order.indexOf(id);
    }

    public String current() {
        return at >= 0 && at < order.size() ? order.get(at) : null;
    }

    /** The song after this one, moving to it; null at the end with nothing repeating. */
    public String next(Repeat repeat, boolean byUser) {
        if (order.isEmpty()) return null;
        if (repeat == Repeat.ONE && !byUser) return current();
        if (at + 1 < order.size()) {
            at++;
        } else if (repeat == Repeat.OFF && !byUser) {
            return null;
        } else {
            // Round again; a reshuffle so the second time isn't the first.
            if (shuffled) {
                String last = current();
                Collections.shuffle(order, random);
                if (order.size() > 1 && order.get(0).equals(last)) Collections.swap(order, 0, order.size() - 1);
            }
            at = 0;
        }
        return current();
    }

    /** The song before this one, moving to it; null at the start. */
    public String previous(Repeat repeat) {
        if (order.isEmpty()) return null;
        if (at > 0) at--;
        else if (repeat == Repeat.ALL) at = order.size() - 1;
        else return null;
        return current();
    }

    public int size() {
        return order.size();
    }
}
