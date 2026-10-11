package com.flashcards.xp3.data;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads bundled decks and round-trips per-user FSRS state.
 *
 * Decks ship in the APK as plain-text assets (see {@link #parseDeck}). The
 * FSRS state — the java-fsrs card fields (state, step, stability, difficulty,
 * due, last review) — lives in the app's private directory as a tiny
 * line-per-card file. Definitions are immutable and never rewritten; only the
 * state file changes, after every rating.
 */
public final class Store {

    private Store() {}

    /**
     * Parses a deck from its text. Format, one card per line:
     *
     *   key<TAB>value<TAB>aux<TAB>identity
     *
     * `aux` and `identity` are optional. Backslash is an escape character, and everything
     * after a leading `#` on an otherwise-blank line is a comment. This is
     * deliberately machine-simple: there is no CSV quote handling, just tab
     * splitting coupled with backslash-escaping the only three characters
     * that ever need it ( backslash, tab, newline ).
     */
    public static List<DeckCard> parseDeck(Reader r) throws IOException {
        BufferedReader br = r instanceof BufferedReader ? (BufferedReader) r : new BufferedReader(r);
        List<DeckCard> cards = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] parts = splitEscaped(line);
            String key = parts[0];
            String value = parts.length > 1 ? parts[1] : "";
            String aux = parts.length > 2 ? parts[2] : "";
            String decodedKey = unescape(key);
            String decodedValue = unescape(value);
            String identity =
                parts.length > 3
                    ? unescape(parts[3])
                    : decodedKey + "\n" + decodedValue;
            cards.add(
                new DeckCard(
                    new CardDef(decodedKey, decodedValue, unescape(aux), identity)));
        }
        return cards;
    }

    /** Writes FSRS state, one card per line: key\tvalue\tstate\tstep\tS\tD\tdue\tlastReview\tidentity. */
    public static void writeState(Writer w, List<DeckCard> cards) throws IOException {
        BufferedWriter bw = w instanceof BufferedWriter ? (BufferedWriter) w : new BufferedWriter(w);
        for (DeckCard c : cards) {
            Card fsrs = c.fsrs;
            String key = escape(c.def.key);
            String value = escape(c.def.value);
            bw.write(key);
            bw.write('\t');
            bw.write(value);
            bw.write('\t');
            bw.write(Integer.toString(fsrs.getState().getValue()));
            bw.write('\t');
            bw.write(fsrs.getStep() == null ? "" : fsrs.getStep().toString());
            bw.write('\t');
            bw.write(fsrs.getStability() == null ? "" : Double.toString(fsrs.getStability()));
            bw.write('\t');
            bw.write(fsrs.getDifficulty() == null ? "" : Double.toString(fsrs.getDifficulty()));
            bw.write('\t');
            bw.write(Long.toString(fsrs.getDue().getEpochSecond()));
            bw.write('\t');
            bw.write(
                fsrs.getLastReview() == null
                    ? ""
                    : Long.toString(fsrs.getLastReview().getEpochSecond()));
            bw.write('\t');
            bw.write(escape(c.def.identity));
            bw.newLine();
        }
        bw.flush();
    }

    /** Reads FSRS state back, reconnecting it to card definitions by identity. */
    public static void readState(Reader r, List<DeckCard> cards) throws IOException {
        BufferedReader br = r instanceof BufferedReader ? (BufferedReader) r : new BufferedReader(r);
        List<StateLine> lines = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] parts = splitEscaped(line);
            if (parts.length < 8) continue;
            try {
                StateLine sl = new StateLine();
                sl.key = unescape(parts[0]);
                sl.value = unescape(parts[1]);
                sl.state = State.fromValue(Integer.parseInt(parts[2]));
                sl.step = parts[3].isEmpty() ? null : (Integer) Integer.parseInt(parts[3]);
                sl.stability = parts[4].isEmpty() ? null : Double.valueOf(parts[4]);
                sl.difficulty = parts[5].isEmpty() ? null : Double.valueOf(parts[5]);
                sl.due = Long.parseLong(parts[6]);
                sl.lastReview = parts[7].isEmpty() ? null : Long.valueOf(parts[7]);
                sl.identity = parts.length > 8 ? unescape(parts[8]) : null;
                if (valid(sl)) lines.add(sl);
            } catch (IllegalArgumentException ignore) {
                // a corrupt line: leave that card's state as new
            }
        }
        for (StateLine sl : lines) {
            for (DeckCard c : cards) {
                boolean matches =
                    sl.identity == null
                        ? c.def.key.equals(sl.key) && c.def.value.equals(sl.value)
                        : c.def.identity.equals(sl.identity);
                if (matches) {
                    c.fsrs =
                        Card.builder()
                            .cardId(DeckCard.idFor(c.def.identity))
                            .state(sl.state)
                            .step(sl.step)
                            .stability(sl.stability)
                            .difficulty(sl.difficulty)
                            .due(java.time.Instant.ofEpochSecond(sl.due))
                            .lastReview(
                                sl.lastReview == null
                                    ? null
                                    : java.time.Instant.ofEpochSecond(sl.lastReview))
                            .build();
                    break;
                }
            }
        }
    }

    private static final class StateLine {
        String key;
        String value;
        State state;
        Integer step;
        Double stability;
        Double difficulty;
        long due;
        Long lastReview;
        String identity;
    }

    private static boolean valid(StateLine sl) {
        if (sl.state == State.REVIEW) {
            if (sl.step != null) return false;
        } else if (sl.step == null || sl.step < 0) {
            return false;
        }

        if (sl.lastReview == null) {
            return sl.state == State.LEARNING
                && sl.stability == null
                && sl.difficulty == null;
        }
        return sl.stability != null
            && Double.isFinite(sl.stability)
            && sl.stability > 0.0
            && sl.difficulty != null
            && Double.isFinite(sl.difficulty)
            && sl.difficulty >= 1.0
            && sl.difficulty <= 10.0;
    }

    private static String[] splitEscaped(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                // Preserve the escape for unescape(); this pass only decides
                // whether a tab is a delimiter.
                cur.append('\\');
                cur.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '\t') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (escaped) cur.append('\\'); // trailing lone backslash is literal
        parts.add(cur.toString());
        return parts.toArray(new String[0]);
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c == '\t') b.append("\\t");
            else if (c == '\n') b.append("\\n");
            else b.append(c);
        }
        return b.toString();
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                if (c == 't') b.append('\t');
                else if (c == 'n') b.append('\n');
                else b.append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else {
                b.append(c);
            }
        }
        if (esc) b.append('\\');
        return b.toString();
    }
}
