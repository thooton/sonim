package com.musica.xp3;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Finds the songs in a Musica folder, each with its SRT when there is one. */
public final class Scan {

    private Scan() {}

    /** What the phone's MediaPlayer plays; MP3 is what the folder is for. */
    static final List<String> AUDIO = Arrays.asList("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav");
    /** How deep subfolders are looked into. */
    static final int DEPTH = 4;

    public static final class Found {
        public final File audio;
        /** The lyrics; null when the song has none. */
        public final File srt;
        /** The subfolder it is in, relative to Musica; "" at the top. */
        public final String folder;

        Found(File audio, File srt, String folder) {
            this.audio = audio;
            this.srt = srt;
            this.folder = folder;
        }
    }

    /** The folder called Musica (or Música, in any case) in `root`; null if none. */
    public static File musica(File root) {
        if (root == null) return null;
        File[] kids = root.listFiles();
        if (kids == null) return null;
        for (File kid : kids) {
            if (kid.isDirectory() && Names.fold(kid.getName()).equals("musica")) return kid;
        }
        return null;
    }

    /** Every song under `dir`, in no particular order. */
    public static List<Found> songs(File dir) {
        List<Found> out = new ArrayList<>();
        if (dir != null) walk(dir, "", 0, out);
        return out;
    }

    private static void walk(File dir, String folder, int depth, List<Found> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids);
        Map<String, File> srts = new HashMap<>();
        List<File> audio = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        for (File kid : kids) {
            String name = kid.getName();
            if (name.startsWith(".")) continue;
            if (kid.isDirectory()) {
                dirs.add(kid);
            } else {
                String ext = Names.extension(name);
                if (ext.equals("srt")) srts.put(Names.fold(Names.base(name)), kid);
                else if (AUDIO.contains(ext)) audio.add(kid);
            }
        }
        for (File a : audio) {
            out.add(new Found(a, lyricsFor(Names.fold(Names.base(a.getName())), srts), folder));
        }
        if (depth >= DEPTH) return;
        for (File d : dirs) {
            walk(d, folder.isEmpty() ? d.getName() : folder + "/" + d.getName(), depth + 1, out);
        }
    }

    /** "Song.srt", or failing that a tagged one such as "Song.es.srt". */
    static File lyricsFor(String base, Map<String, File> srts) {
        File exact = srts.get(base);
        if (exact != null) return exact;
        File tagged = null;
        for (Map.Entry<String, File> e : srts.entrySet()) {
            String key = e.getKey();
            if (key.startsWith(base + ".") && key.indexOf('.', base.length() + 1) < 0) {
                // Prefer Spanish when there are several.
                if (tagged == null || key.endsWith(".es") || key.endsWith(".spa")) tagged = e.getValue();
            }
        }
        return tagged;
    }
}
