package com.hbsa.xp3;

import android.content.Context;
import android.content.SharedPreferences;

import com.xp3.common.Disk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The posts both screens share: the last ones fetched, kept on disk so the
 * list is up at once on launch, and the agenda made of them that the list
 * shows, which a post's Left and Right move through.
 */
final class Club {

    private static List<Post> posts;
    /** The agenda on screen; null until the list has made one. */
    static Agenda agenda;
    /** Where in the agenda a post screen last was, for the list to land on coming back; or -1. */
    static int opened = -1;

    private Club() {}

    static synchronized List<Post> posts(Context context) {
        if (posts == null) {
            posts = new ArrayList<Post>();
            String saved = Disk.read(file(context), 4000000);
            if (saved != null) {
                try {
                    posts = Post.read(saved);
                } catch (RuntimeException e) {
                    posts = new ArrayList<Post>();
                }
            }
        }
        return posts;
    }

    static synchronized void update(Context context, List<Post> fresh) throws IOException {
        Disk.write(file(context), Post.write(fresh), false);
        posts = new ArrayList<Post>(fresh);
        prefs(context).edit().putLong("fetched", System.currentTimeMillis()).apply();
    }

    /** When the posts were last fetched, or 0. */
    static long fetched(Context context) {
        return prefs(context).getLong("fetched", 0);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("club", Context.MODE_PRIVATE);
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), "posts.json");
    }
}
