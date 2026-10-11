package com.dictionary.xp3;

import android.content.res.AssetManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * WordNet 3.1, as written by tools/build_dict.py (which documents the format).
 *
 * The headword list is held in memory as one string; meanings are read from
 * deflated blocks on demand, keeping the last few blocks.
 */
final class Dictionary {

    /** A meaning: part-of-speech letter (n, v, a, r), gloss, and the words for it. */
    static final class Sense {
        final char pos;
        final String gloss;
        final String[] words;

        Sense(String record) {
            pos = record.charAt(0);
            int tab = record.indexOf('\t');
            gloss = record.substring(1, tab);
            words = record.substring(tab + 1).split("\\|");
        }
    }

    private static final String POS_LETTERS = "nvar";

    // WordNet's detachment rules (morphy), per part of speech: an ending, and
    // what replaces it. Irregular forms come from the exception lists instead.
    private static final String[][] RULES = {
        {"s", "", "ses", "s", "xes", "x", "zes", "z", "ches", "ch", "shes", "sh", "men", "man", "ies", "y"},
        {"s", "", "ies", "y", "es", "e", "es", "", "ed", "e", "ed", "", "ing", "e", "ing", ""},
        {"er", "", "est", "", "er", "e", "est", "e"},
        {},
    };

    private static final int BLOCK = 128;
    private static final int CACHED_BLOCKS = 16;

    private final AssetManager assets;
    private final String words;
    private final int[] starts;
    private final int[] offsets;
    private Map<String, String[]> exceptions;
    private final Map<Integer, String[]> blocks = new LinkedHashMap<Integer, String[]>(CACHED_BLOCKS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, String[]> eldest) {
            return size() > CACHED_BLOCKS;
        }
    };

    Dictionary(AssetManager assets) throws IOException {
        this.assets = assets;
        words = inflate(assets.open("words.dat"));
        int count = 1;
        for (int i = 0; i < words.length(); i++) {
            if (words.charAt(i) == '\n') count++;
        }
        starts = new int[count + 1];
        for (int i = 0, n = 1; i < words.length(); i++) {
            if (words.charAt(i) == '\n') starts[n++] = i + 1;
        }
        starts[count] = words.length() + 1;

        DataInputStream in = new DataInputStream(assets.open("synsets.dat"));
        try {
            offsets = new int[in.readInt() + 1];
            for (int i = 0; i < offsets.length; i++) offsets[i] = in.readInt();
        } finally {
            in.close();
        }
    }

    int size() {
        return starts.length - 1;
    }

    String word(int index) {
        return words.substring(starts[index] + 1, words.indexOf('\t', starts[index]));
    }

    /** Bit 1 noun, 2 verb, 4 adjective, 8 adverb. */
    private int mask(int index) {
        return Character.digit(words.charAt(starts[index]), 16);
    }

    /** The headword's meanings, nouns first, then verbs, adjectives, adverbs. */
    List<Sense> senses(int index) {
        String ids = words.substring(words.indexOf('\t', starts[index]) + 1, starts[index + 1] - 1);
        List<Sense> senses = new ArrayList<>();
        for (String id : ids.split(" ")) {
            int n = Integer.parseInt(id);
            senses.add(new Sense(block(n / BLOCK)[n % BLOCK]));
        }
        return senses;
    }

    /**
     * Where to open the book for a query: the word itself; else its base form
     * (mice, went, better, dogs, hoping); else the first word it begins; else -1.
     */
    int find(String query) {
        String q = normalize(query);
        if (q.isEmpty()) return -1;
        int at = lowerBound(q);
        if (at < size() && word(at).equals(q)) return at;
        for (int pos = 0; pos < POS_LETTERS.length(); pos++) {
            for (String base : bases(q, pos)) {
                int i = lowerBound(base);
                if (i < size() && word(i).equals(base) && (mask(i) & (1 << pos)) != 0) return i;
            }
        }
        if (at < size() && word(at).startsWith(q)) return at;
        return -1;
    }

    /** The first index whose word is not before {@code word}. */
    int lowerBound(String word) {
        int low = 0;
        int high = size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (word(mid).compareTo(word) < 0) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    static String normalize(String query) {
        return query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private List<String> bases(String form, int pos) {
        String[] irregular = exceptions().get(POS_LETTERS.charAt(pos) + form);
        List<String> out = new ArrayList<>();
        if (irregular != null) {
            for (String base : irregular) out.add(base);
            return out;
        }
        String[] rules = RULES[pos];
        for (int i = 0; i < rules.length; i += 2) {
            if (form.endsWith(rules[i]) && form.length() > rules[i].length()) {
                out.add(form.substring(0, form.length() - rules[i].length()) + rules[i + 1]);
            }
        }
        return out;
    }

    /** "pos letter + form" to its base forms, loaded the first time a search needs it. */
    private Map<String, String[]> exceptions() {
        if (exceptions == null) {
            Map<String, String[]> map = new HashMap<>();
            for (String line : read("morph.dat").split("\n")) {
                String[] f = line.split("\t");
                String[] bases = new String[f.length - 2];
                System.arraycopy(f, 2, bases, 0, bases.length);
                map.put(f[0] + f[1], bases);
            }
            exceptions = map;
        }
        return exceptions;
    }

    private String[] block(int k) {
        String[] block = blocks.get(k);
        if (block != null) return block;
        byte[] packed = new byte[offsets[k + 1] - offsets[k]];
        try {
            DataInputStream in = new DataInputStream(assets.open("synsets.dat", AssetManager.ACCESS_RANDOM));
            try {
                long skip = offsets[k];
                while (skip > 0) skip -= in.skip(skip);
                in.readFully(packed);
            } finally {
                in.close();
            }
            block = inflate(new ByteArrayInputStream(packed)).split("\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        blocks.put(k, block);
        return block;
    }

    /** The data is in the APK, so failing to read it later is a bug, not a condition. */
    private String read(String asset) {
        try {
            return inflate(assets.open(asset));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String inflate(InputStream raw) throws IOException {
        InputStream in = new InflaterInputStream(raw);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return out.toString("ISO-8859-1");
        } finally {
            in.close();
        }
    }
}
