package com.delta.xp3.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The phone's shared folders a downloaded file can be moved into, and the
 * name it gets there.
 *
 * The names are Android's own (Environment.DIRECTORY_MUSIC and the rest),
 * which is what the music player, the gallery and the ringtone picker look in.
 */
public final class Folders {

    private Folders() {}

    public static final String MUSIC = "Music";
    public static final String RINGTONES = "Ringtones";
    public static final String NOTIFICATIONS = "Notifications";
    public static final String ALARMS = "Alarms";
    public static final String PODCASTS = "Podcasts";
    public static final String PICTURES = "Pictures";
    public static final String MOVIES = "Movies";
    public static final String DOCUMENTS = "Documents";
    public static final String DOWNLOAD = "Download";

    /** Every folder offered, in the order shown after the ones the file suggests. */
    private static final List<String> ALL = Arrays.asList(
        MUSIC, PICTURES, MOVIES, DOCUMENTS, DOWNLOAD, RINGTONES, NOTIFICATIONS, ALARMS, PODCASTS);

    private static final Set<String> AUDIO = set(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "mid", "midi");
    private static final Set<String> IMAGE = set("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic");
    private static final Set<String> VIDEO = set("mp4", "m4v", "mkv", "webm", "3gp", "avi", "mov");
    private static final Set<String> DOCUMENT = set(
        "pdf", "epub", "mobi", "azw3", "txt", "md", "rtf", "doc", "docx", "odt",
        "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp");

    private static Set<String> set(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    /** Every folder, the likeliest for a file of this name first. */
    public static List<String> forName(String name) {
        String extension = extension(name);
        Set<String> order = new LinkedHashSet<String>();
        if (AUDIO.contains(extension)) order.addAll(Arrays.asList(MUSIC, RINGTONES, NOTIFICATIONS, ALARMS, PODCASTS));
        else if (IMAGE.contains(extension)) order.add(PICTURES);
        else if (VIDEO.contains(extension)) order.add(MOVIES);
        else if (DOCUMENT.contains(extension)) order.add(DOCUMENTS);
        else order.add(DOWNLOAD);
        order.addAll(ALL);
        return new ArrayList<String>(order);
    }

    /**
     * `name` in `folder`, or, when something there already has it, the first
     * free "name (2).ext", "name (3).ext" and so on. Nothing already there is
     * ever replaced.
     */
    public static File unused(File folder, String name) {
        File plain = new File(folder, name);
        if (!plain.exists()) return plain;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String tail = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            File numbered = new File(folder, stem + " (" + n + ")" + tail);
            if (!numbered.exists()) return numbered;
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }
}
