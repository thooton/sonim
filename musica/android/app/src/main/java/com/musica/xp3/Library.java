package com.musica.xp3;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import com.xp3.common.Disk;
import com.xp3.common.ui.Card;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The songs in Musica, on the SD card and (if there is one) in the phone's
 * own storage. Loading lists the folders, names each song from its cached
 * tags, shows that, and then reads the tags of songs it hasn't seen.
 */
final class Library {

    interface Listener {
        void changed();
    }

    private static Library shared;

    static Library get(Context context) {
        if (shared == null) shared = new Library(context.getApplicationContext());
        return shared;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final File cacheFile;
    private volatile List<Song> songs = Collections.emptyList();
    private Map<String, Song> byPath = new HashMap<>();
    private boolean loaded;
    private boolean loading;
    private boolean again;

    private Library(Context context) {
        this.context = context;
        cacheFile = new File(context.getFilesDir(), "tags.json");
    }

    void listen(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void unlisten(Listener l) {
        listeners.remove(l);
    }

    List<Song> songs() {
        return songs;
    }

    Song find(String path) {
        return path == null ? null : byPath.get(path);
    }

    /** Whether the first listing is done, so "no songs" means none. */
    boolean loaded() {
        return loaded;
    }

    /** The Musica folders that exist. */
    List<File> folders() {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        File card = Card.root(context);
        File phone = Environment.getExternalStorageDirectory();
        for (File root : new File[] {card, phone}) {
            File dir = Scan.musica(root);
            if (dir != null && seen.add(dir.getAbsolutePath())) out.add(dir);
        }
        return out;
    }

    /** Lists Musica again, in the background. */
    void load() {
        if (loading) {
            again = true;
            return;
        }
        loading = true;
        new Thread(() -> {
            List<Scan.Found> found = new ArrayList<>();
            for (File dir : folders()) found.addAll(Scan.songs(dir));
            Map<String, Song> cache = Song.read(Disk.read(cacheFile, 8_000_000));
            // First what is known without opening a file, then the tags.
            List<Song> quick = Song.catalog(found, cache, audio -> null);
            publish(quick, false);
            boolean unread = false;
            for (Scan.Found f : found) if (!cache.containsKey(f.audio.getAbsolutePath())) unread = true;
            if (unread || cache.size() != found.size()) {
                List<Song> full = Song.catalog(found, cache, this::tags);
                try {
                    synchronized (this) {
                        Disk.write(cacheFile, Song.write(full), false);
                    }
                } catch (IOException ignored) {
                    // Tags are read again next time.
                }
                publish(full, true);
            } else {
                publish(quick, true);
            }
        }, "library").start();
    }

    private void publish(List<Song> list, boolean done) {
        List<Song> fixed = Collections.unmodifiableList(new ArrayList<>(list));
        Map<String, Song> map = new HashMap<>();
        for (Song s : fixed) map.put(s.path, s);
        main.post(() -> {
            songs = fixed;
            byPath = map;
            loaded = true;
            if (done) {
                loading = false;
                if (again) {
                    again = false;
                    load();
                }
            }
            for (Listener l : new ArrayList<>(listeners)) l.changed();
        });
    }

    private Song.Tags tags(File audio) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try (ParcelFileDescriptor fd = Files.open(context, audio)) {
            r.setDataSource(fd.getFileDescriptor());
            Song.Tags t = new Song.Tags();
            t.title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            t.artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (t.artist == null) t.artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            t.album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            try {
                t.duration = ms == null ? 0 : Long.parseLong(ms.trim());
            } catch (NumberFormatException e) {
                t.duration = 0;
            }
            t.art = r.getEmbeddedPicture() != null;
            return t;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            close(r);
        }
    }

    /** release() is declared to throw on newer SDKs, though not on the phone's. */
    static void close(MediaMetadataRetriever r) {
        try {
            r.release();
        } catch (Exception ignored) {
            // Nothing more to free.
        }
    }
}
