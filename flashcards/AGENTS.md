# AGENTS.md

Spaced-repetition flashcards on FSRS-6, queued like Anki (learning steps,
reviews due before the 4am rollover, every card answered 1 to 4) except that
there is no daily new-card limit: new cards come in while the mean predicted
recall of the studied cards is above 85%. Read `../AGENTS.md` first.

- `android/core` (plain Java, tested by `:flashcards:core:selfTest`):
  - `io/github/openspacedrepetition/`: an Android/Java 8 adaptation of
    java-fsrs 1.0.0 (FSRS-6 defaults). Keep it close to upstream; `AlgoTests`
    pins its numbers.
  - `algo/Fsrs`: the app's seam to that library. `ENGINE` is shared;
    `defaults()` builds a fresh scheduler, with its own fuzz seed.
  - `algo/ReviewSession`: one deck's queue: which card is next, the
    new/learning/review counts, and the interval each rating would give.
  - `data/`: `Deck`, `DeckCard`, `CardDef`, and `Store` (bundled decks in,
    per-user FSRS state out, one line per card).
- `android/app`: `MainActivity` (Decks and Review tabs) and `ReviewPane`
  (shows a card; OK reveals, 1 to 4 rate it, each key over its interval).
- `android/app/src/main/assets/decks/`: each deck as `<name>.tsv` and its
  starting state as `<name>.state`, built from an Anki profile by
  `tools/import_anki_spanish.py`.
- `setup-emulator.sh`: an x86_64 emulator for trying the app off the phone.

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :flashcards:core:selfTest :flashcards:app:assembleRelease
```
