package com.newyorker.xp3.news;

import com.xp3.common.Disk;
import com.xp3.common.Json;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Articles kept to read later, whole, so they open without the network.
 *
 * The list is saved.json in the folder, newest first; each article is its own
 * file beside it, named for a hash of its address.
 */
public final class Saved {

    private static final long LIMIT = 8000000;

    private final File folder;
    private final List<Story> stories = new ArrayList<Story>();

    public Saved(File folder) {
        this.folder = folder;
        String text = Disk.read(new File(folder, "saved.json"), LIMIT);
        if (text == null) return;
        try {
            for (Object each : Json.array(Json.parse(text))) stories.add(Story.fromJson(Json.object(each)));
        } catch (RuntimeException e) {
            // A list that no longer reads is started again rather than crashing the app.
            stories.clear();
        }
    }

    public synchronized List<Story> stories() {
        return new ArrayList<Story>(stories);
    }

    public synchronized boolean contains(String url) {
        return indexOf(url) >= 0;
    }

    private int indexOf(String url) {
        for (int i = 0; i < stories.size(); i++) if (stories.get(i).url.equals(url)) return i;
        return -1;
    }

    /** Keeps the article, moving it to the top if it was there already. */
    public synchronized void add(Article article) throws IOException {
        Disk.write(file(article.url), article.toJson(), false);
        int at = indexOf(article.url);
        if (at >= 0) stories.remove(at);
        stories.add(0, article.story());
        write();
    }

    public synchronized void remove(String url) throws IOException {
        int at = indexOf(url);
        if (at < 0) return;
        stories.remove(at);
        write();
        file(url).delete();
    }

    /** The saved copy, or null when there is none or it no longer reads. */
    public synchronized Article article(String url) {
        String text = Disk.read(file(url), LIMIT);
        if (text == null) return null;
        try {
            return Article.fromJson(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void write() throws IOException {
        List<Object> list = new ArrayList<Object>();
        for (Story story : stories) list.add(story.toJson());
        Disk.write(new File(folder, "saved.json"), Json.write(list), false);
    }

    private File file(String url) {
        return new File(folder, "article-" + hash(url) + ".json");
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format("%02x", digest[i] & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
