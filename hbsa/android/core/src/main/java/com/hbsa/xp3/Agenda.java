package com.hbsa.xp3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * The list the app shows: every event the posts announce, earliest first,
 * under a heading for each day; then, under "Other posts", the posts that
 * name no date.
 */
public final class Agenda {

    /** A day's heading, an event, or a post with no date. */
    public static final class Entry {
        /** The heading's words, or null for an event or a post. */
        public final String heading;
        public final Dates.Event event;
        public final Post post;
        /** An event already over (its day has passed). */
        public final boolean past;

        Entry(String heading, Dates.Event event, Post post, boolean past) {
            this.heading = heading;
            this.event = event;
            this.post = post;
            this.past = past;
        }

        public boolean isHeading() {
            return heading != null;
        }
    }

    public final List<Entry> entries;
    /** Where the list opens: the first event today or later, else the first entry that isn't a heading. */
    public final int start;

    private Agenda(List<Entry> entries, int start) {
        this.entries = Collections.unmodifiableList(entries);
        this.start = start;
    }

    public static Agenda build(List<Post> posts, long now, TimeZone zone) {
        List<Dates.Event> events = new ArrayList<Dates.Event>();
        List<Post> undated = new ArrayList<Post>();
        for (Post post : posts) {
            List<Dates.Event> found = Dates.events(post, zone);
            if (found.isEmpty()) undated.add(post);
            events.addAll(found);
        }
        // By day, then by time with the untimed last; a stable sort keeps
        // newer posts first among equals.
        Collections.sort(events, (a, b) -> a.day != b.day ? Long.compare(a.day, b.day)
            : Integer.compare(a.minutes < 0 ? Integer.MAX_VALUE : a.minutes, b.minutes < 0 ? Integer.MAX_VALUE : b.minutes));

        long today = Dates.midnight(now, zone);
        List<Entry> entries = new ArrayList<Entry>();
        int start = -1;
        long day = Long.MIN_VALUE;
        for (Dates.Event event : events) {
            if (event.day != day) {
                day = event.day;
                entries.add(new Entry(Dates.heading(day, now, zone), null, null, day < today));
            }
            if (start < 0 && event.day >= today) start = entries.size();
            entries.add(new Entry(null, event, event.post, event.day < today));
        }
        if (!undated.isEmpty()) {
            entries.add(new Entry("Other posts", null, null, false));
            if (start < 0 && events.isEmpty()) start = entries.size();
            for (Post post : undated) entries.add(new Entry(null, null, post, false));
        }
        // Nothing coming up: open on the latest event that was.
        if (start < 0) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).event != null) {
                    start = i;
                    break;
                }
            }
        }
        return new Agenda(entries, Math.max(start, 0));
    }

    /** The positions that are events or posts, in order: what Left and Right on a post move through. */
    public List<Integer> items() {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < entries.size(); i++) if (!entries.get(i).isHeading()) out.add(i);
        return out;
    }
}
