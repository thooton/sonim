package com.flashcards.xp3;

import com.flashcards.xp3.data.CardDef;
import com.flashcards.xp3.data.DeckCard;
import com.flashcards.xp3.data.Fkv;
import com.flashcards.xp3.data.Store;

import com.xp3.common.Check;
import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.State;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The data half: deck parsing from the bundled text format, and the
 * round-trip of per-user FSRS state through the writer/reader.
 */
public final class DataTests {

    public static void run(Check c) {
        parseDeck(c);
        escape(c);
        stateRoundTrip(c);
        duplicateTextStateRoundTrip(c);
        corruptState(c);
        fkv(c);
    }

    private static void fkv(Check c) {
        c.run("FKV", () -> {
            String file = "\uFEFFhola||hello\r\n\n  \nline\\none||a \\\\ b\n"
                + "quote \"it's\"||\\|\\| and \\||\na|||b\n\\x41\\u00e9\\U0001F600||tab\\there\n"
                + "estar||to be||Yo estoy fr\u00edo.\\n\\nI am cold.\nx||y||\n";
            List<Fkv.Card> cards = Fkv.parse(file.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            c.same("seven cards", 7, cards.size());
            c.same("no metadata", "", cards.get(0).meta);
            c.same("metadata", "Yo estoy fr\u00edo.\n\nI am cold.", cards.get(5).meta);
            c.same("empty metadata", "", cards.get(6).meta);
            c.same("plain", "hola=hello", cards.get(0).key + "=" + cards.get(0).value);
            c.same("newline and backslash", "line\none=a \\ b", cards.get(1).key + "=" + cards.get(1).value);
            c.same("quotes and pipes", "quote \"it's\"=|| and ||", cards.get(2).key + "=" + cards.get(2).value);
            c.same("the first || splits", "a=|b", cards.get(3).key + "=" + cards.get(3).value);
            c.same("code points", "A\u00e9\uD83D\uDE00=tab\there", cards.get(4).key + "=" + cards.get(4).value);
            String[][] bad = {
                {"ok||ok\nno separator", "Line 2: no ||"},
                {"a||b||c||d", "a third ||"},
                {"||b", "the key is empty"},
                {"a||", "the value is empty"},
                {"a\\q||b", "\\q isn't an escape"},
                {"a||b\\", "a lone \\"},
                {"\\x4||b", "isn't a character"},
                {"\\uD800||b", "isn't a character"},
            };
            for (String[] one : bad) {
                c.fails(one[0], one[1], () -> Fkv.parse(one[0].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            c.fails("not UTF-8", "UTF-8", () -> Fkv.parse(new byte[] {'a', '|', '|', (byte) 0xff}));
        });
    }

    private static void duplicateTextStateRoundTrip(Check c) {
        c.run("duplicate text state round trip", () -> {
            List<DeckCard> original = new ArrayList<>();
            DeckCard first = new DeckCard(new CardDef("general", "general", "", "anki:1"));
            first.fsrs = reviewedCard(first, 2.5);
            DeckCard second = new DeckCard(new CardDef("general", "general", "", "anki:2"));
            second.fsrs = reviewedCard(second, 8.5);
            original.add(first);
            original.add(second);

            StringWriter w = new StringWriter();
            Store.writeState(w, original);
            List<DeckCard> restored = new ArrayList<>();
            restored.add(new DeckCard(new CardDef("general", "general", "", "anki:1")));
            restored.add(new DeckCard(new CardDef("general", "general", "", "anki:2")));
            Store.readState(new StringReader(w.toString()), restored);

            c.close("first duplicate keeps its state", 2.5, restored.get(0).fsrs.getStability(), 1e-12);
            c.close("second duplicate keeps its state", 8.5, restored.get(1).fsrs.getStability(), 1e-12);
        });
    }

    private static Card reviewedCard(DeckCard card, double stability) {
        return Card.builder()
            .cardId(DeckCard.idFor(card.def.identity))
            .state(State.REVIEW)
            .step(null)
            .stability(stability)
            .difficulty(5.0)
            .due(Instant.ofEpochSecond(2000L))
            .lastReview(Instant.ofEpochSecond(1000L))
            .build();
    }

    private static void parseDeck(Check c) {
        c.run("parse deck", () -> {
            String deck =
                "apple\tred fruit\n" +
                "# a comment to be skipped\n" +
                "dog\ta loyal animal\tsays woof\tanki:2\n" +
                "\tblank key example\n";
            List<DeckCard> cards = Store.parseDeck(new StringReader(deck));
            c.same("three cards", 3, cards.size());
            c.same("first key", "apple", cards.get(0).def.key);
            c.same("first value", "red fruit", cards.get(0).def.value);
            c.same("first aux empty", "", cards.get(0).def.aux);
            c.same("second key", "dog", cards.get(1).def.key);
            c.same("second value", "a loyal animal", cards.get(1).def.value);
            c.same("second aux", "says woof", cards.get(1).def.aux);
            c.same("explicit identity", "anki:2", cards.get(1).def.identity);
            c.same("blank key survives", "", cards.get(2).def.key);
        });
    }

    private static void escape(Check c) {
        c.run("escape", () -> {
            String round = "a\\b\tc\nd";
            List<DeckCard> cards = new ArrayList<>();
            DeckCard original = new DeckCard(new CardDef(round, "v", "x"));
            original.fsrs =
                Card.builder()
                    .cardId(DeckCard.idFor(round, "v"))
                    .state(State.REVIEW)
                    .step(null)
                    .stability(7.5)
                    .difficulty(4.0)
                    .due(Instant.ofEpochSecond(1234L))
                    .lastReview(Instant.ofEpochSecond(1000L))
                    .build();
            cards.add(original);
            StringWriter w = new StringWriter();
            Store.writeState(w, cards);
            String out = w.toString();
            // the raw tab and newline and backslash must be escaped in the line
            c.ok("escapes tab", !out.contains("\t" + "c") || out.contains("\\t"));
            List<DeckCard> back = new ArrayList<>();
            back.add(new DeckCard(new CardDef("a", "b"))); // placeholder identity
            Store.readState(new StringReader(out), back);
            // identity matched by exact key/value strings must survive
            List<DeckCard> exact = new ArrayList<>();
            exact.add(new DeckCard(new CardDef(round, "v", "x")));
            Store.readState(new StringReader(out), exact);
            c.same("key round-trips", round, exact.get(0).def.key);
            c.close("escaped identity restores state", 7.5, exact.get(0).fsrs.getStability(), 1e-12);
        });
    }

    private static void stateRoundTrip(Check c) {
        c.run("state round trip", () -> {
            List<DeckCard> cards = new ArrayList<>();
            DeckCard k1 = new DeckCard(new CardDef("k1", "v1"));
            // a graduated card, fully specified
            k1.fsrs =
                Card.builder()
                    .cardId(DeckCard.idFor("k1", "v1"))
                    .state(State.REVIEW)
                    .step(null)
                    .stability(3.14)
                    .difficulty(4.5)
                    .due(Instant.ofEpochSecond(1234567890L))
                    .lastReview(Instant.ofEpochSecond(1234567890L))
                    .build();
            cards.add(k1);
            // a learning card mid-steps
            DeckCard l1 = new DeckCard(new CardDef("k3", "v3"));
            l1.fsrs =
                Card.builder()
                    .cardId(DeckCard.idFor("k3", "v3"))
                    .state(State.LEARNING)
                    .step(1)
                    .stability(3.2602)
                    .difficulty(5.0)
                    .due(Instant.ofEpochSecond(1500000000L))
                    .lastReview(Instant.ofEpochSecond(1499999400L))
                    .build();
            cards.add(l1);
            cards.add(new DeckCard(new CardDef("k2", "v2"))); // still new
            StringWriter w = new StringWriter();
            Store.writeState(w, cards);
            String out = w.toString();

            // a fresh set of definitions, same identities, must pick the state
            // back up exactly
            List<DeckCard> reloaded = new ArrayList<>();
            reloaded.add(new DeckCard(new CardDef("k1", "v1")));
            reloaded.add(new DeckCard(new CardDef("k3", "v3")));
            reloaded.add(new DeckCard(new CardDef("k2", "v2")));
            Store.readState(new StringReader(out), reloaded);

            Card r1 = reloaded.get(0).fsrs;
            c.same("state restored", State.REVIEW, r1.getState());
            c.ok("step restored null", r1.getStep() == null);
            c.close("stability restored", 3.14, r1.getStability(), 1e-12);
            c.close("difficulty restored", 4.5, r1.getDifficulty(), 1e-12);
            c.same("due restored", 1234567890L, r1.getDue().getEpochSecond());
            c.same("last review restored", 1234567890L, r1.getLastReview().getEpochSecond());

            Card l2 = reloaded.get(1).fsrs;
            c.same("learning state restored", State.LEARNING, l2.getState());
            c.same("step restored", 1, l2.getStep());
            c.close("learning stability restored", 3.2602, l2.getStability(), 1e-12);

            c.ok("untouched card stays new", reloaded.get(2).fsrs.getLastReview() == null);
        });
    }

    private static void corruptState(Check c) {
        c.run("corrupt state", () -> {
            DeckCard card = new DeckCard(new CardDef("key", "value"));
            List<DeckCard> cards = new ArrayList<>();
            cards.add(card);
            Store.readState(
                new StringReader("key\tvalue\t99\t\tNaN\t5\t1000\t900\n"),
                cards);
            c.ok("invalid state line is ignored", card.isNew());

            Store.readState(
                new StringReader("key\tvalue\t2\t\tNaN\t5\t1000\t900\n"),
                cards);
            c.ok("non-finite memory is ignored", card.isNew());
        });
    }
}
