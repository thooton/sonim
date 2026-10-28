# AGENTS.md

Spaced-repetition flashcards scheduled as Anki desktop schedules them with
the FSRS4Anki scheduler on (fsrs4anki_scheduler.js v6.1.1, FSRS-6 defaults),
queued like Anki (learning steps, reviews due before the 4am rollover, every
card answered 1 to 4), drawn as Anki's desktop window, and synced through
the Delta command center with Flashcards on the tablet (`flashcards/` in
thooton/tablet), as AnkiWeb syncs Anki. Read `../AGENTS.md` first.

New cards per day is a deck option: a number, as in Anki, counting every
card first answered since the rollover on either device (`intro`); or
**Auto**, with no daily number, where New is whatever brings New +
Learning + Due up to `ReviewSession.AUTO_TARGET` (20): none while Learning
+ Due is at it or over, and more as reviews are answered. Either way new
cards are mixed in among the day's reviews, spread evenly, as Anki's
default "Mix with reviews" does (`newTurn`; the tablet's Go port gives the
same order, pinned by its `TestNewCardsMixIntoReviews`). Auto only decides
when a new card is introduced; everything else (learning steps,
relearning, reviews, intervals) is Anki with the FSRS4Anki scheduler, and
must stay so. (Auto has
been a mean-recall gate at 85%, which showed the whole unseen pile once
it crossed, and then a cap on cards in learning; the user wanted new
material to arrive steadily, mixed with the old.) The Spanish deck is on
Auto.

**Review Ahead.** When a deck is finished for now, its congratulations
offer Review Ahead (Anki's custom study option of that name): studied
cards not yet due, the likeliest forgotten first (lowest predicted recall,
then earliest due), never the card just answered twice running, until the
user leaves. An early answer is scheduled from the Anki days actually
elapsed, by the script's review formulas, as if the deck were a normal one:
in Anki the script leaves a filtered deck's intervals to the SM-2 ones, which
these cards don't have. The queue keeps its own rules where they differ
from Anki: no review limit, reviews in order of exact due time, new cards
mixed in card by card, Review Ahead by lowest recall.
The tablet's `session.earliest` is the same rule.

- `android/core` (plain Java, tested by `:flashcards:core:selfTest`):
  - `algo/Fsrs`: the scheduler, a line-for-line port of
    `fsrs4anki_scheduler.js` v6.1.1 (open-spaced-repetition/fsrs4anki) as
    Anki 26.09 runs it, with Anki's own learning side around it (steps 1m
    10m, relearning 10m, up to 25%/5 minutes added to a step, a step past
    the rollover becoming a day's wait). Its comment lists what the script
    does differently from java-fsrs and from Anki's built-in FSRS: Anki
    days, not 24 hours, for recall; every (re)learning answer short-term,
    Hard included; two-decimal rounding; ±(5% + 1 day) fuzz with Hard <=
    Good < Easy. Its one departure from the script is the fuzz draw, seeded
    by the card and its last review so the phone, the tablet and the button
    labels agree. `AlgoTests.scriptVectors` pins numbers the script gave in
    Node; check a change against the script itself the same way (feed it
    `states`/`customData` for a card, with `set_fuzz_factor()` replaced by
    the fuzz this class drew, and compare). The tablet's backend is a Go
    port of it and of `ReviewSession`, checked against answers this code
    made (`TabletVectors` writes its `testdata/java-fsrs.json`: run it with
    the core's test classes and redirect the output there); change the
    scheduling here only together with the tablet's (see its AGENTS.md).
  - `io/github/openspacedrepetition/`: `Card`, `State` and `Rating`, the
    memory state kept from java-fsrs 1.0.0 (its scheduler is gone).
  - `algo/ReviewSession`: one deck's queue: which card is next, the
    new/learning/review counts, the daily limit or Auto, and the interval
    each rating would give (`preview`, which counts Anki days in the
    phone's zone for `Fsrs`).
  - `algo/Stats`: the statistics, from the cards' state alone (no review
    log is kept): Today, Future Due, Card Counts, Review Intervals and
    Card Retrievability (recall after the Anki days since the last
    answer, as `Fsrs` counts them). The tablet's `stats.go` is the same
    arithmetic, and its `TestStatsMatchThePhone` wants `StatsTests`'
    numbers; change them together.
  - `data/`: `Deck` (name, new cards/day), `DeckCard`, `CardDef`,
    `Collection` (every deck and card, its JSON file, the sync merge, and
    `imported`, a new deck from a file), `Store` (the bundled deck's text
    format) and `Fkv` (the FKV format Import reads).
  - `sync/Center`: `POST /flashcards/sync`, and pairing.
- `android/app`: `MainActivity` (deck list, overview, sync, the
  login), `ReviewPane` (the reviewer: OK shows the answer; only 1 to 4
  answer, each button labelled with its key; Menu > Remove from deck takes
  the card out, see Removing), `OptionsPane` (New cards/day: Left and Right step
  it, digits type it, below 0 is Auto, Back keeps it), `StatsPane` (Anki's
  Statistics window: Stats beside Options and in Menu; the deck's or, with
  Left and Right, the collection's; Up and Down scroll; Card Counts' pie and
  the histograms in Anki's colours), `Anki` (the look).
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

**Removing.** On a card, Menu > Remove from deck takes it out of the deck
at once, without asking (`Collection.remove`), and is saved and synced
later, as an answer is, so the next card shows without waiting on the
whole file being written; the collection keeps it
aside, written in the file as itself with `deleted`, until a sync has taken
it to the command center, which keeps it as a tombstone so the tablet drops
it too. The tablet can't remove cards itself. A removal wins over any
answer, made here or on the tablet, before it or after.

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
