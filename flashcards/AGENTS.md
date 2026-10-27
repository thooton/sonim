# AGENTS.md

Spaced-repetition flashcards on FSRS-6, queued like Anki (learning steps,
reviews due before the 4am rollover, every card answered 1 to 4), drawn as
Anki's desktop window, and synced through the Delta command center with
Flashcards on the tablet (`flashcards/` in thooton/tablet), as AnkiWeb
syncs Anki. Read `../AGENTS.md` first.

New cards per day is a deck option: a number, as in Anki, counting every
card first answered since the rollover on either device (`intro`); or
**Auto**, with no daily number, where a new card comes in whenever fewer
than `ReviewSession.AUTO_WINDOW` (20) cards are in learning or relearning,
as Quizlet brings in new material once the old is in hand. New cards come
after the day's reviews either way, so old material always goes first.
Auto only decides when a new card is introduced; everything else
(learning steps, relearning, reviews, order, intervals) is stock Anki,
and must stay so. The New count on Auto is the room left in the window,
never the whole unseen pile. (Auto used to let new cards in while the
studied cards' mean predicted recall was above 85%, which showed the
whole pile at once when it crossed, and kept itself open, since a card
learned today counts as recalled.) The Spanish deck is on Auto.

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
    `Collection` (every deck and card, its JSON file, the sync merge, and
    `imported`, a new deck from a file), `Store` (the bundled deck's text
    format) and `Fkv` (the FKV format Import reads).
  - `sync/Center`: `POST /flashcards/sync`, and pairing.
- `android/app`: `MainActivity` (deck list, overview, sync, the
  login), `ReviewPane` (the reviewer: OK shows the answer; only 1 to 4
  answer, each button labelled with its key), `OptionsPane` (New cards/day: Left and Right step
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

**Import.** Menu > Import (on the deck list or a deck's overview) lists
the `.fkv` files in Documents and Download, on the SD card and the phone,
newest first, and makes a new deck of the one picked, named after the file
("Cell biology.fkv" is Cell biology; a name taken gets " (2)"), on 20 new
cards a day. Tablet saves Transform's flashcards into Documents on the
card. FKV (Flashcards Key-Value) is UTF-8 text, one card per line,
`key||value` or `key||value||metadata` (the metadata, such as an example
sentence, becomes the card's `aux`, shown under the answer), each part
written as the inside of a string literal (`\n`, `\t`, `\\`, `\|` and the
rest; quotes are themselves; each bare `||` ends a part, so a literal one is
`\|\|`), blank lines skipped; a file
that doesn't parse is refused, naming the line. The deck's id is `fkv-` and
a random UUID, and each card's is the deck's plus `:` and its place, since
the command center keeps cards by id across decks. The deck and its cards
are dirty as of the import, so the next sync takes them to the command
center and from there to the tablet. Reading asks for storage first, and
for the SD card's grant (`CardGrant`) only if a plain read there is
refused. The command center checks what Transform submits with the same
rules (`internal/fkv` in thooton/delta); change them together.

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
