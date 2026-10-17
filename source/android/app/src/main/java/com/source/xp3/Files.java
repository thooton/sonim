package com.source.xp3;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What can be done with saved files: play music in the phone's player, read
 * a book in the Books app, and how full the phone's storage is.
 */
final class Files {

    private Files() {}

    /** The Books app (`../books`), which reads what is in the Books folder. */
    private static final String READER = "com.books.xp3";

    private static final String[] AUDIO = {"mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "alac", "ape", "aiff", "aif"};

    static boolean audio(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = path.substring(dot + 1).toLowerCase(Locale.US);
        for (String one : AUDIO) if (one.equals(ext)) return true;
        return false;
    }

    /** The first music file among `files` that is still there, in name order, or null. */
    static String firstAudio(List<String> files) {
        List<String> sorted = new ArrayList<String>(files);
        Collections.sort(sorted);
        for (String path : sorted) if (audio(path) && new File(path).isFile()) return path;
        return null;
    }

    /** Where a saved file is, from the top of the phone's storage: "Music/Artist/Song.mp3". */
    static String shown(String path) {
        String root = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        return path.startsWith(root) ? path.substring(root.length()) : path;
    }

    /** The folder a download went into, as shown: an album's own folder, or a song's. */
    static String folderOf(List<String> files) {
        if (files.isEmpty()) return "";
        String parent = new File(files.get(0)).getParent();
        return parent == null ? "" : shown(parent);
    }

    /** Plays a music file in whatever plays music, once the media scanner knows it. */
    static void play(final Tonal activity, String path) {
        if (path == null) {
            activity.note("Nothing to play");
            return;
        }
        open(activity, path, "audio/*");
    }

    /**
     * Opens a saved book: in the Books app when it is on the phone, since it
     * reads the Books folder; else in anything that opens the file's type.
     */
    static void read(final Tonal activity, String path) {
        Intent books = activity.getPackageManager().getLaunchIntentForPackage(READER);
        if (books != null) {
            activity.startActivity(books);
            return;
        }
        String type = path.toLowerCase(Locale.US).endsWith(".pdf") ? "application/pdf" : "application/epub+zip";
        open(activity, path, type);
    }

    private static void open(final Tonal activity, String path, final String type) {
        final Handler main = new Handler(Looper.getMainLooper());
        MediaScannerConnection.scanFile(activity, new String[] {path}, null, (scanned, uri) -> main.post(() -> {
            if (uri == null) {
                activity.note("Couldn't open it");
                return;
            }
            view(activity, uri, type);
        }));
    }

    private static void view(Activity activity, Uri uri, String type) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, type);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            ((Tonal) activity).note("No app here opens it");
        }
    }

    /** The phone's shared storage: how much is free, of how much. */
    static long[] space() {
        try {
            StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            return new long[] {stat.getAvailableBytes(), stat.getTotalBytes()};
        } catch (IllegalArgumentException e) {
            return new long[] {0, 0};
        }
    }

    /** Everything in a folder and the folders in it. Slow on a full card, so off the main thread. */
    static long size(File dir) {
        File[] list = dir.listFiles();
        if (list == null) return 0;
        long total = 0;
        for (File file : list) total += file.isDirectory() ? size(file) : file.length();
        return total;
    }
}
