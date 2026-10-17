package com.updater.xp3.core;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * What's new in an app, as the App Store shows under an update. CI writes
 * each app's newest commits into the release's manifest.json (see
 * .github/manifest.py and Catalog), already cut down to a sentence.
 */
public final class Notes {

    /** One change to an app. */
    public static final class Note {
        /** When it landed, in epoch milliseconds; 0 when unknown. */
        public final long time;
        /** The change, as a sentence. */
        public final String text;
        /** The version its commit named ("Updater 1.2.4: …"), or null. */
        public final String version;

        public Note(long time, String text, String version) {
            this.time = time;
            this.text = text;
            this.version = version;
        }
    }

    private Notes() {}

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
