package com.dictionary.xp3;

import android.content.res.AssetManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Anniversary Gregg shorthand, as Grascii writes it, read from the asset
 * tools/build_shorthand.py makes (whose docstring documents the format).
 *
 * Outlines are found both ways: by their English (a word's Shorthand tab), and
 * by an outline typed into the search, read stroke by stroke as Grascii's own
 * search reads it by default: annotations and aspirates are ignored,
 * disjoiners must agree, and a similar outline is one whose strokes are each
 * at most one step from the typed ones in Grascii's similarity graph.
 */
final class Shorthand {

    /** How a typed outline matched: wholly, as the start of a longer one, or with similar strokes. */
    static final int EXACT = 0;
    static final int START = 1;
    static final int SIMILAR = 2;

    static final class Match {
        final int entry;
        final int kind;
        final int distance;

        Match(int entry, int kind, int distance) {
            this.entry = entry;
            this.kind = kind;
            this.distance = distance;
        }
    }

    /** Grascii's strokes; the asset writes STROKES[i] as (char) (0xC0 + i). The same list as the script's. */
    private static final String[] STROKES = (
        "A AU A&' A&E B CH D DD DF DM DN DT DV E EU F G I J JND JNT K L LD "
        + "M MD MM MN MT N ND NG NK NT O OE P PND PNT R S SH SS T TD TH TM TN TV "
        + "U V X XS Z").split(" ");
    private static final char CODE = 0xC0;
    private static final char DISJOINER = '^';
    private static final String ANNOTATIONS = ",.|~_()";
    private static final int LONGEST = 3;

    // Strokes Grascii treats as one (similarities.py's _equiv_nodes), and the
    // edges between them; a stroke's similars at distance d are the groups
    // within d edges of its own.
    private static final String[][] EQUIVALENT = {
        {"S", "Z"}, {"TD", "DT", "DD"}, {"MN", "MM"}, {"TN", "DN"}, {"TM", "DM"},
        {"NT", "ND"}, {"MT", "MD"}, {"DF", "DV", "TV"}, {"PNT", "PND", "JNT", "JND"},
    };
    private static final String[] EDGES = (
        "K G  R L  L LD  T D  D DT  I A  I A&'  I A&E  A E  A A&'  A A&E  A&' A&E  AU EU  NK N  N M  M MN  "
        + "N NG  M NK  NG NK  M NG  NK MN  SH S  S SS  S XS  SS XS  XS X  X S  S F  F V  SH CH  F CH  V J  "
        + "CH J  CH P  S P  P B  J B  TH TN  TN TM  NT MT  TH NT").split("\\s+");

    /** DISTANCE[a][b]: 0 for the same stroke or an equivalent, else edges apart, at most 3. */
    private static final int[][] DISTANCE = distances();

    private final String[] outlines;
    private final String[] translations;
    private final String[] strokes;
    // Entries are sorted by translation, so each translation's are a run:
    // group g is entries groups[g] .. groups[g + 1] - 1, keyed keys[g].
    private final int[] groups;
    private final String[] keys;
    private final int[] groupOf;

    Shorthand(AssetManager assets) throws IOException {
        String[] lines = Dictionary.inflate(assets.open("shorthand.dat")).split("\n");
        int n = lines.length;
        outlines = new String[n];
        translations = new String[n];
        strokes = new String[n];
        groupOf = new int[n];
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String line = lines[i];
            int a = line.indexOf('\t');
            int b = line.indexOf('\t', a + 1);
            outlines[i] = line.substring(0, a);
            translations[i] = line.substring(a + 1, b);
            strokes[i] = line.substring(b + 1);
            String key = key(translations[i]);
            if (names.isEmpty() || !names.get(names.size() - 1).equals(key)) {
                starts.add(i);
                names.add(key);
            }
            groupOf[i] = names.size() - 1;
        }
        starts.add(n);
        groups = new int[starts.size()];
        for (int g = 0; g < groups.length; g++) groups[g] = starts.get(g);
        keys = names.toArray(new String[0]);
    }

    String outline(int entry) {
        return outlines[entry];
    }

    String translation(int entry) {
        return translations[entry];
    }

    int group(int entry) {
        return groupOf[entry];
    }

    /** The translation a group is for, as the dictionary writes it. */
    String name(int group) {
        return translations[groups[group]];
    }

    String key(int group) {
        return keys[group];
    }

    List<Integer> entries(int group) {
        List<Integer> out = new ArrayList<>();
        for (int i = groups[group]; i < groups[group + 1]; i++) out.add(i);
        return out;
    }

    /** The group for exactly this word or phrase, or -1. */
    int find(String word) {
        String k = key(word);
        int at = lowerBound(k);
        return at < keys.length && keys[at].equals(k) ? at : -1;
    }

    /** The groups whose translation begins with the query, in order. */
    List<Integer> prefixed(String query, int limit) {
        String q = key(query);
        List<Integer> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (int g = lowerBound(q); g < keys.length && out.size() < limit && keys[g].startsWith(q); g++) out.add(g);
        return out;
    }

    /**
     * The outlines a typed outline could be: those it is (the one written
     * exactly as typed first), then the longer ones it begins, then those
     * with similar strokes, nearest first. Empty if what was typed is not
     * Grascii.
     */
    List<Match> read(String typed, int limit) {
        List<Match> out = new ArrayList<>();
        String typedStrokes = strokes(typed);
        if (typedStrokes == null || typedStrokes.isEmpty()) return out;
        String exactly = typed.trim().toUpperCase(Locale.ROOT);
        List<Match> exact = new ArrayList<>();
        List<Match> start = new ArrayList<>();
        List<Match> similar = new ArrayList<>();
        int m = typedStrokes.length();
        for (int i = 0; i < strokes.length; i++) {
            // Written exactly as typed, it matches however its strokes read
            // (Grascii's parser reads a few P and J blends unlike split()).
            if (outlines[i].equals(exactly)) {
                exact.add(new Match(i, EXACT, 0));
                continue;
            }
            String s = strokes[i];
            if (s.length() < m) continue;
            int distance = 0;
            for (int j = 0; j < m && distance >= 0; j++) {
                int d = distance(typedStrokes.charAt(j), s.charAt(j));
                distance = d > 1 ? -1 : distance + d;
            }
            if (distance < 0) continue;
            if (s.length() == m) {
                if (distance == 0) exact.add(new Match(i, EXACT, 1));
                else similar.add(new Match(i, SIMILAR, distance));
            } else if (distance == 0) {
                start.add(new Match(i, START, s.length() - m));
            }
        }
        // Sorting is stable, so a tie keeps the dictionary's order.
        Collections.sort(exact, (a, b) -> a.distance - b.distance);
        Collections.sort(start, (a, b) -> a.distance - b.distance);
        Collections.sort(similar, (a, b) -> a.distance - b.distance);
        for (List<Match> part : Arrays.asList(exact, start, similar)) {
            for (Match match : part) {
                if (out.size() >= limit) return out;
                out.add(match);
            }
        }
        return out;
    }

    /**
     * An outline split into what Grascii reads as its strokes, each with its
     * annotations, an aspirate or a disjoiner apiece: A~MNVTR is A~ MN V T R.
     * Null if it is not Grascii.
     */
    static List<String> split(String outline) {
        String s = outline.trim().toUpperCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ') {
                i++;
            } else if (ANNOTATIONS.indexOf(c) >= 0) {
                // An annotation belongs to the stroke before it.
                if (out.isEmpty()) return null;
                out.set(out.size() - 1, out.get(out.size() - 1) + c);
                i++;
            } else if (c == '\'' || c == DISJOINER || c == '-' || c == '\\') {
                out.add(String.valueOf(c));
                i++;
            } else {
                int length = stroke(s, i);
                if (length == 0) return null;
                out.add(s.substring(i, i + length));
                i += length;
            }
        }
        return out;
    }

    /** What the search compares: one character per stroke and disjoiner, as the asset writes them. Null if not Grascii. */
    static String strokes(String outline) {
        List<String> parts = split(outline);
        if (parts == null) return null;
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.equals("^")) {
                out.append(DISJOINER);
                continue;
            }
            int end = part.length();
            while (end > 0 && ANNOTATIONS.indexOf(part.charAt(end - 1)) >= 0) end--;
            int index = index(part.substring(0, end));
            if (index >= 0) out.append((char) (CODE + index));
        }
        return out.toString();
    }

    /**
     * How many letters the stroke at {@code at} takes: the longest stroke
     * there, as Grascii's parser prefers blends, but not one ending in S or T
     * before an H, which is SH or TH (NTH is N TH); not NT, MT, ND or MD
     * before an N or M, where it reads the over blend that follows (MDN is
     * M DN); and not PNT, PND, JNT or JND ending a longer outline, which it
     * reads as P NT, J ND and so on (KOJNT is K O J NT). 0 if no stroke
     * starts there.
     */
    private static int stroke(String s, int at) {
        for (int length = Math.min(LONGEST, s.length() - at); length > 0; length--) {
            String candidate = s.substring(at, at + length);
            if (index(candidate) < 0) continue;
            char next = at + length < s.length() ? s.charAt(at + length) : 0;
            if (length > 1 && next == 'H' && (candidate.endsWith("S") || candidate.endsWith("T"))) continue;
            if (length == 2 && "NT MT ND MD".contains(candidate) && (next == 'N' || next == 'M')) continue;
            if (length == 3 && at > 0 && next == 0 && "PNT PND JNT JND".contains(candidate)) continue;
            return length;
        }
        return 0;
    }

    private static int index(String stroke) {
        for (int i = 0; i < STROKES.length; i++) {
            if (STROKES[i].equals(stroke)) return i;
        }
        return -1;
    }

    private static int distance(char typed, char written) {
        if (typed == DISJOINER || written == DISJOINER) return typed == written ? 0 : 3;
        return DISTANCE[typed - CODE][written - CODE];
    }

    private static int[][] distances() {
        int n = STROKES.length;
        int[] group = new int[n];
        for (int i = 0; i < n; i++) group[i] = i;
        for (String[] same : EQUIVALENT) {
            int first = index(same[0]);
            for (String s : same) group[index(s)] = first;
        }
        boolean[][] edge = new boolean[n][n];
        for (int i = 0; i < EDGES.length; i += 2) {
            int a = group[index(EDGES[i])];
            int b = group[index(EDGES[i + 1])];
            edge[a][b] = true;
            edge[b][a] = true;
        }
        int[][] out = new int[n][n];
        for (int a = 0; a < n; a++) {
            // Breadth first from a's group, two layers out.
            int[] layer = new int[n];
            Arrays.fill(layer, 3);
            layer[group[a]] = 0;
            for (int d = 1; d <= 2; d++) {
                for (int g = 0; g < n; g++) {
                    if (layer[g] != d - 1) continue;
                    for (int h = 0; h < n; h++) {
                        if (edge[g][h] && layer[h] > d) layer[h] = d;
                    }
                }
            }
            for (int b = 0; b < n; b++) out[a][b] = layer[group[b]];
        }
        return out;
    }

    private int lowerBound(String key) {
        int low = 0;
        int high = keys.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (keys[mid].compareTo(key) < 0) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private static String key(String translation) {
        return Dictionary.normalize(translation);
    }
}
