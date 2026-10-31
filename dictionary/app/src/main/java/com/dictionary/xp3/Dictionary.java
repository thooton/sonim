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

    /**
     * What to offer under the search box as it is typed: the word {@link #find}
     * would open when that is a base form (mice: mouse), then the words that
     * begin with the query, in order.
     */
    List<Integer> suggest(String query, int limit) {
        String q = normalize(query);
        List<Integer> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        int found = find(q);
        if (found >= 0 && !word(found).startsWith(q)) out.add(found);
        for (int i = lowerBound(q); i < size() && out.size() < limit && word(i).startsWith(q); i++) {
            out.add(i);
        }
        return out;
    }

    /**
     * The Word of the Day for a day counted from the epoch: a plain word of
     * six to twelve letters, not a name, with an example among its meanings,
     * picked from the day so every day has its own and a day keeps it.
     */
    int wordOfTheDay(long day) {
        long h = (day + 1) * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        int start = (int) Math.floorMod(h, (long) size());
        for (int i = 0; i < size(); i++) {
            int at = (start + i) % size();
            String w = word(at);
            if (w.length() < 6 || w.length() > 12 || (mask(at) & 7) == 0) continue;
            boolean letters = true;
            for (int c = 0; c < w.length() && letters; c++) letters = w.charAt(c) >= 'a' && w.charAt(c) <= 'z';
            if (!letters) continue;
            List<Sense> senses = senses(at);
            boolean example = false;
            // Plain lowercase as its page writes it: as its first meaning does.
            String cased = null;
            for (Sense sense : senses) {
                example |= !examples(sense, w).isEmpty();
                for (String form : sense.words) {
                    if (cased == null && form.equalsIgnoreCase(w)) cased = form;
                }
            }
            if (example && w.equals(cased)) return at;
        }
        return 0;
    }

    /**
     * The examples of a sense to show on {@code word}'s page: those that use
     * the word itself, in any of its forms. A meaning is shared by all its
     * words, and WordNet wrote each example for one of them, so on "dog" the
     * meaning "frump, dog" would otherwise show "she got a reputation as a
     * frump". The meaning's other spellings of the word count as the word
     * (civilise, civilize; esthetic, aesthetic).
     */
    List<String> examples(Sense sense, String word) {
        List<String> out = new ArrayList<>();
        int at = sense.gloss.indexOf("; \"");
        if (at < 0) return out;
        List<List<String>> spellings = new ArrayList<>();
        spellings.add(tokens(word));
        String lower = word.toLowerCase(Locale.ROOT);
        for (String other : sense.words) {
            String o = other.toLowerCase(Locale.ROOT);
            if (!o.equals(lower) && variant(o, lower)) spellings.add(tokens(o));
        }
        for (String example : sense.gloss.substring(at + 2).split("; (?=\")")) {
            example = example.replace("\"", "").trim();
            if (example.isEmpty()) continue;
            List<String> tokens = tokens(example);
            for (List<String> parts : spellings) {
                if (uses(tokens, parts)) {
                    out.add(example);
                    break;
                }
            }
        }
        return out;
    }

    /** Whether two words are spellings of one: at most one letter apart, two for longer words. */
    private static boolean variant(String a, String b) {
        int n = Math.min(a.length(), b.length());
        if (n < 4) return false;
        int limit = n < 6 ? 1 : 2;
        if (Math.abs(a.length() - b.length()) > limit) return false;
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int diagonal = row[0];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int above = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1),
                    diagonal + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                diagonal = above;
            }
        }
        return row[b.length()] <= limit;
    }

    /** Lowercase words, split at anything but letters, digits and apostrophes, without a trailing 's. */
    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}']+")) {
            t = t.replaceAll("^'+|'+$", "");
            if (t.endsWith("'s") && t.length() > 2) t = t.substring(0, t.length() - 2);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * Whether {@code tokens} contain the word split into {@code parts}, in
     * any of its forms; written closed or open alike (looseleaf, loose-leaf).
     */
    private boolean uses(List<String> tokens, List<String> parts) {
        if (parts.isEmpty()) return false;
        String closed = String.join("", parts);
        for (int i = 0; i < tokens.size(); i++) {
            if (i + parts.size() <= tokens.size()) {
                boolean all = true;
                for (int j = 0; j < parts.size() && all; j++) all = form(tokens.get(i + j), parts.get(j));
                if (all) return true;
            }
            // The other way round: a closed word written open, or a phrase written closed.
            String joined = "";
            for (int j = i; j < tokens.size() && joined.length() < closed.length(); j++) {
                joined += tokens.get(j);
                if (j > i || parts.size() > 1) {
                    if (form(joined, closed)) return true;
                }
            }
        }
        return false;
    }

    /** Whether {@code token} and {@code base} are forms of one word (dogs, went, stopped, bigger; cost, costs). */
    private boolean form(String token, String base) {
        if (token.equals(base)) return true;
        for (int pos = 0; pos < 4; pos++) {
            List<String> of = bases(token, pos);
            if (of.contains(base)) return true;
            for (String b : bases(base, pos)) {
                if (b.equals(token) || of.contains(b)) return true;
            }
        }
        // A doubled last letter (stopped, running, bigger), which morphy leaves to the exception lists.
        if (token.length() > base.length() + 2 && token.startsWith(base)
                && token.charAt(base.length()) == base.charAt(base.length() - 1)) {
            String rest = token.substring(base.length() + 1);
            return rest.equals("ed") || rest.equals("ing") || rest.equals("er") || rest.equals("est");
        }
        return false;
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
