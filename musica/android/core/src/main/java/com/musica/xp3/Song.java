package com.musica.xp3;

import com.xp3.common.Json;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One song in Musica: its file, its lyrics' file, and what its tags say. */
public final class Song {

    public final String path;
    /** The SRT's path; null when there are no lyrics. */
    public final String srt;
    public final String title;
    /** "" when neither the tags nor the name give one. */
    public final String artist;
    public final String album;
    public final long duration;
    /** Whether the file carries a picture. */
    public final boolean art;
    /** The file's size and time, to tell when cached tags are stale. */
    public final long size;
    public final long modified;

    public Song(String path, String srt, String title, String artist, String album,
                long duration, boolean art, long size, long modified) {
        this.path = path;
        this.srt = srt;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.art = art;
        this.size = size;
        this.modified = modified;
    }

    /** The file name without its folder or extension. */
    public String base() {
        return Names.base(new File(path).getName());
    }

    /** What a song's tags say; any of it may be null or empty. */
    public static final class Tags {
        public String title;
        public String artist;
        public String album;
        public long duration;
        public boolean art;
    }

    /** Reads a file's tags; on the phone, MediaMetadataRetriever. */
    public interface Tagger {
        Tags read(File audio);
    }

    /**
     * The songs found, named from their tags, from `cache` when the file
     * hasn't changed since and from `tagger` otherwise. Sorted A to Z.
     */
    public static List<Song> catalog(List<Scan.Found> found, Map<String, Song> cache, Tagger tagger) {
        List<Song> out = new ArrayList<>();
        for (Scan.Found f : found) {
            String path = f.audio.getAbsolutePath();
            String srt = f.srt == null ? null : f.srt.getAbsolutePath();
            long size = f.audio.length();
            long modified = f.audio.lastModified();
            Song known = cache.get(path);
            if (known != null && known.size == size && known.modified == modified) {
                out.add(new Song(path, srt, known.title, known.artist, known.album,
                    known.duration, known.art, size, modified));
                continue;
            }
            Tags tags = null;
            try {
                tags = tagger.read(f.audio);
            } catch (RuntimeException e) {
                // A file the tagger chokes on still plays, named from its name.
            }
            out.add(named(f, tags, size, modified));
        }
        sort(out);
        return out;
    }

    static Song named(Scan.Found f, Tags tags, long size, long modified) {
        String[] fromName = Names.split(Names.base(f.audio.getName()));
        String title = tags != null && notBlank(tags.title) ? tags.title.trim() : fromName[0];
        String artist = tags != null && notBlank(tags.artist) ? tags.artist.trim() : fromName[1];
        String album = tags != null && notBlank(tags.album) ? tags.album.trim() : lastPart(f.folder);
        return new Song(f.audio.getAbsolutePath(), f.srt == null ? null : f.srt.getAbsolutePath(),
            title, artist, album, tags == null ? 0 : tags.duration, tags != null && tags.art, size, modified);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String lastPart(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash >= 0 ? folder.substring(slash + 1) : folder;
    }

    public static void sort(List<Song> songs) {
        Collections.sort(songs, (a, b) -> {
            int c = Names.compare(a.title, b.title);
            if (c != 0) return c;
            c = Names.compare(a.artist, b.artist);
            return c != 0 ? c : a.path.compareTo(b.path);
        });
    }

    public Map<String, Object> toJson() {
        return Json.map("path", path, "title", title, "artist", artist, "album", album,
            "duration", (double) duration, "art", art, "size", (double) size, "modified", (double) modified);
    }

    /** The cache, as written by write(); empty if it can't be read. */
    public static Map<String, Song> read(String json) {
        Map<String, Song> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            for (Object o : Json.array(Json.parse(json))) {
                Map<String, Object> m = Json.object(o);
                String path = Json.string(m, "path");
                out.put(path, new Song(path, null, str(m, "title"), str(m, "artist"),
                    str(m, "album"), num(m, "duration"), Json.flag(m, "art"),
                    num(m, "size"), num(m, "modified")));
            }
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        String s = Json.text(m, key);
        return s == null ? "" : s;
    }

    private static long num(Map<String, Object> m, String key) {
        Double d = Json.number(m, key);
        return d == null ? 0 : d.longValue();
    }

    public static String write(List<Song> songs) {
        List<Object> list = new ArrayList<>();
        for (Song s : songs) list.add(s.toJson());
        return Json.write(list);
    }
}
