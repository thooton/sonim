package com.updater.xp3.core;

import com.xp3.common.Json;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * What's new in each app, as the App Store shows under an update: read from
 * the repository's recent commits, whose subjects name the apps they change
 * ("Mail: drop the conversation's position from the bar", "List, Canvas:
 * remove the chat", "Delta 1.1.0: Claude accounts on the command center").
 *
 * CI builds every app on each push to master, so an app's newest commit is
 * what its APK in the release carries, and that commit's date is the date
 * the App Store would show beside it.
 */
public final class Notes {

    /** One change to an app. */
    public static final class Note {
        /** When it landed, in epoch milliseconds. */
        public final long time;
        /** The subject after the app names, as a sentence. */
        public final String text;

        public Note(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    private Notes() {}

    /**
     * GitHub's list-commits JSON, sorted into each of `apps` (release names,
     * matched without regard to case), newest first. Merges and commits that
     * name no app are left out.
     */
    public static Map<String, List<Note>> parse(String json, Collection<String> apps) {
        Map<String, String> byLower = new HashMap<>();
        for (String app : apps) byLower.put(app.toLowerCase(Locale.ROOT), app);
        Map<String, List<Note>> out = new HashMap<>();
        for (Object item : Json.array(Json.parse(json))) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> commit = Json.child(Json.object(item), "commit");
            String message = Json.text(commit, "message");
            if (message == null) continue;
            String subject = message.split("\r?\n", 2)[0].trim();
            if (subject.startsWith("Merge ")) continue;
            String text = text(subject);
            if (text == null) continue;
            long time = Json.instant(Json.child(commit, "committer"), "date");
            if (time == 0) time = Json.instant(Json.child(commit, "author"), "date");
            for (String name : names(subject)) {
                String app = byLower.get(name.toLowerCase(Locale.ROOT));
                if (app == null) continue;
                List<Note> notes = out.get(app);
                if (notes == null) out.put(app, notes = new ArrayList<>());
                notes.add(new Note(time, text));
            }
        }
        // GitHub lists commits newest first already; keep that, but make sure.
        for (List<Note> notes : out.values()) {
            java.util.Collections.sort(notes, (a, b) -> Long.compare(b.time, a.time));
        }
        return out;
    }

    /**
     * The app names a subject leads with: the words before its colon, split
     * at commas, "&", "/" and "and", each cut to its first word (so "Delta
     * 1.1.0" is Delta). Empty when there is no such lead.
     */
    public static List<String> names(String subject) {
        List<String> out = new ArrayList<>();
        int colon = subject.indexOf(':');
        if (colon <= 0 || colon > 48) return out;
        for (String part : subject.substring(0, colon).split(",|&|/|\\band\\b")) {
            String word = part.trim();
            int space = word.indexOf(' ');
            if (space > 0) word = word.substring(0, space);
            if (!word.isEmpty()) out.add(word);
        }
        return out;
    }

    /** What follows the colon, starting with a capital and ending in a stop; null if nothing does. */
    public static String text(String subject) {
        int colon = subject.indexOf(':');
        if (colon < 0) return null;
        String text = subject.substring(colon + 1).trim();
        if (text.isEmpty()) return null;
        text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        char last = text.charAt(text.length() - 1);
        if (last != '.' && last != '!' && last != '?' && last != '…') text += ".";
        return text;
    }

    /** The App Store's date: "Today", "Yesterday", else "Jun 15, 2019". */
    public static String when(long time, long now, TimeZone zone) {
        Calendar day = Calendar.getInstance(zone, Locale.US);
        day.setTimeInMillis(now);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long today = day.getTimeInMillis();
        day.add(Calendar.DAY_OF_MONTH, -1);
        long yesterday = day.getTimeInMillis();
        if (time >= today) return "Today";
        if (time >= yesterday) return "Yesterday";
        SimpleDateFormat format = new SimpleDateFormat("MMM d, yyyy", Locale.US);
        format.setTimeZone(zone);
        return format.format(new Date(time));
    }
}
