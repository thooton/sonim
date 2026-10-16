# AGENTS.md

Spaced-repetition flashcards on FSRS, in groups of ten targeted at 85%
predicted accuracy. Read `../AGENTS.md` first.

- `android/core` (plain Java, tested by `:flashcards:core:selfTest`):
  - `io/github/openspacedrepetition/`: an Android/Java 8 adaptation of
    java-fsrs 1.0.0 (FSRS-6 defaults). Keep it close to upstream; `AlgoTests`
    pins its numbers.
  - `algo/Fsrs`: the app's seam to that library. `ENGINE` is shared;
    `defaults()` builds a fresh scheduler, with its own fuzz seed.
  - `algo/ReviewSession`: one deck's review session, serving the current
    group a card at a time; `algo/GroupBuilder` picks each group.
  - `data/`: `Deck`, `DeckCard`, `CardDef`, and `Store` (bundled decks in,
    per-user FSRS state out, one line per card).
- `android/app`: `MainActivity` (Decks and Review tabs) and `ReviewPane`
  (shows a card; OK reveals, 1 to 4 rate it).
- `android/app/src/main/assets/decks/`: each deck as `<name>.tsv` and its
  starting state as `<name>.state`, built from an Anki profile by
  `tools/import_anki_spanish.py`.
- `setup-emulator.sh`: an x86_64 emulator for trying the app off the phone.

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :flashcards:core:selfTest :flashcards:app:assembleRelease
```
