package com.hbsa.xp3;

import android.content.Context;
import android.content.SharedPreferences;

import com.xp3.common.Disk;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The posts both screens share: the last ones fetched, kept on disk so the
 * list is up at once on launch, and which of them have been opened.
 */
final class Club {

    private static List<Post> posts;

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

    /**
     * The newly fetched posts. The first time there are any, they all count
     * as seen: only what arrives after that is new.
     */
    static synchronized void update(Context context, List<Post> fresh) throws IOException {
        Disk.write(file(context), Post.write(fresh), false);
        posts = new ArrayList<Post>(fresh);
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor edit = prefs.edit().putLong("fetched", System.currentTimeMillis());
        if (!prefs.contains("seen")) {
            Set<String> seen = new HashSet<String>();
            for (Post post : fresh) seen.add(post.shortcode);
            edit.putStringSet("seen", seen);
        }
        edit.apply();
    }

    /** When the posts were last fetched, or 0. */
    static long fetched(Context context) {
        return prefs(context).getLong("fetched", 0);
    }

    static boolean isNew(Context context, Post post) {
        Set<String> seen = prefs(context).getStringSet("seen", null);
        return seen != null && !seen.contains(post.shortcode);
    }

    static void seen(Context context, Post post) {
        SharedPreferences prefs = prefs(context);
        Set<String> seen = new HashSet<String>(prefs.getStringSet("seen", new HashSet<String>()));
        if (seen.add(post.shortcode)) prefs.edit().putStringSet("seen", seen).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("club", Context.MODE_PRIVATE);
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), "posts.json");
    }
}
