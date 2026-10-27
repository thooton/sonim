# AGENTS.md

Spaced-repetition flashcards on FSRS-6, queued like Anki (learning steps,
reviews due before the 4am rollover, every card answered 1 to 4), drawn as
Anki's desktop window, and synced through the Delta command center with
Flashcards on the tablet (`flashcards/` in thooton/tablet), as AnkiWeb
syncs Anki. Read `../AGENTS.md` first.

New cards per day is a deck option: **Auto**, where new cards come in
while the mean predicted recall of the deck's studied cards is above 85%,
with no daily number (how this app always worked); or a number, as in
Anki, counting every card first answered since the rollover on either
device (`intro`). The Spanish deck starts on Auto.

**Review Ahead.** When a deck is finished for now, its congratulations
offer Review Ahead (Anki's custom study option of that name): studied
cards not yet due, the likeliest forgotten first (lowest predicted recall,
then earliest due), never the card just answered twice running, until the
user leaves. FSRS schedules an early answer from the days actually elapsed.
The tablet's `session.earliest` is the same rule.

- `android/core` (plain Java, tested by `:flashcards:core:selfTest`):
  - `io/github/openspacedrepetition/`: an Android/Java 8 adaptation of
    java-fsrs 1.0.0 (FSRS-6 defaults). Keep it close to upstream; `AlgoTests`
    pins its numbers. The tablet's backend is a Go port of it and of
    `ReviewSession`, checked against answers this code made; change the
    scheduling here only together with the tablet's (see its AGENTS.md).
  - `algo/Fsrs`: the app's seam to that library.
  - `algo/ReviewSession`: one deck's queue: which card is next, the
    new/learning/review counts, the daily limit or Auto, and the interval
    each rating would give.
  - `data/`: `Deck` (name, new cards/day), `DeckCard`, `CardDef`,
    `Collection` (every deck and card, its JSON file, and the sync merge)
    and `Store` (the bundled deck's text format).
  - `sync/Center`: `POST /flashcards/sync`, and pairing.
- `android/app`: `MainActivity` (toolbar, deck list, overview, sync, the
  login), `ReviewPane` (the reviewer: OK shows the answer, then answers
  Good; 1 to 4 answer), `OptionsPane` (New cards/day: Left and Right step
  it, digits type it, below 0 is Auto, Back keeps it), `Anki` (the look).
- `android/app/src/main/assets/decks/`: the Spanish deck and its starting
  state, built from an Anki profile by `tools/import_anki_spanish.py`.
  Only the first run of 1.2 reads them, to bring the deck (with the state
  the phone had saved, `decks_spanish-words.tsv.state`) into the
  collection; its first sync takes it to the command center.
- `setup-emulator.sh`: an x86_64 emulator for trying the app off the phone.

**Sync.** The app keeps its copy of the command center's collection in
`collection.json` (private), studies from it offline, and syncs on opening,
30 seconds after the last answer, after an options change, on leaving, and
from Menu > Sync. Each record carries `mod`, the phone's clock in
milliseconds when it last changed, and a dirty flag until the command
center has it; where the phone and the tablet both changed a card, the
later change wins, except for a card's text, which only the command center changes (`PATCH /flashcards/cards/{id}`): a sync takes corrected text into the card's `def`. The phone sends its time zone (`zone`) too, so the
tablet, which keeps UTC, rolls the day over at 4am here.

**Signing in.** As the tablet does, the app pairs: with no login, the deck
list ends in a code (from `POST /flashcards/pair` at
`Center.DEFAULT_SERVER`), which the user types on the website's Tablet
page; the app polls for the token that makes, which reaches only the
flashcards. A sync refused with 401 (unpaired on the website) forgets the
login and pairs again. A build with a bundled login (`gradle/bundled.gradle`)
starts signed in, and Menu > Command center takes a typed one.

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :flashcards:core:selfTest :flashcards:app:assembleRelease
```
