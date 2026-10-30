# Flashcards

## Purpose

Spaced-repetition flashcards on the Sonim XP3, used one-handed with keys
while out and about: open the app, pick a deck, press OK to see the answer,
press 1 to 4 to grade it, next card. It is Anki's desktop app on a flip
phone: Anki's screens and words (deck list, overview, Study Now, reviewer,
deck options, Statistics), Anki's queue, and Anki's scheduling with the
FSRS4Anki scheduler (FSRS-6 defaults), so a card is due on the same day it
would be in Anki. The decks live on the Delta command center and sync with
Flashcards on the reMarkable tablet, as AnkiWeb syncs Anki.

It must stay quick (a key never waits on disk or network), usable offline,
and faithful to Anki. Where it departs from Anki it does so only as written
below (Auto new cards, Review Ahead's order). It must never become a deck
editor, a card browser, a general Anki client, or anything that needs touch
or typing to study.

## Not wanted

- An on-screen toolbar or answer buttons that act on OK. Only the keys 1-4
  answer; the buttons only label them (1.4.1 dropped the toolbar).
- A deck-wide recall gate for new cards (1.1.0's "while recall is above
  85%"), or a cap on cards in learning (1.4.0, 1.5.1). Auto keeps New +
  Learning + Due at 20, with new cards mixed among reviews (1.6.0).
- java-fsrs' own scheduler or Anki's built-in FSRS; the scheduler is the
  FSRS4Anki script, ported line for line (1.8.0).
- Editing a card's text on the phone. Corrected text comes only from the
  command center.
- A confirmation before Remove from deck.
- Writing the whole collection on the main thread (1.9.2).

## Screens

The app opens on the deck list. There is no header naming the app.

### Menus

Menu opens Anki's pop-up menu, drawn over the window (not a platform
dialog, so Night Shift and Dark mode reach it) at the bottom: a bordered
panel on the elevated colour with 5dp corners; a check column at the left
(`✓` for Night Shift and Dark mode when on); the label; the digit that picks
the item at the right in grey (1 to 9); hairlines between groups (Options and
Stats; Sync and Import; Night Shift and Dark mode; Command center). The
highlighted item has a blue bar and white text. Up and Down move (wrapping),
OK picks (on release), a digit picks that item, Menu and Back close it. Import's
list of `.fkv` files is the same menu, scrolling, under an `Import` heading.

- **Night Shift** warms the whole screen toward yellow (as in Books), is
  kept between runs, and is off by default.
- **Dark mode** is an OLED dark theme: pure black canvas, Anki's night
  colours, kept between runs, off by default, independent of the phone's
  setting. Switching it reopens the app on the deck list.

### Deck list

- A header row, `Deck` then `New`, `Learn`, `Due` in small grey, then one
  row per deck: its name (one line, ending in … if too long) and its three
  counts right-aligned. A count of 0 is faint grey; otherwise New is blue,
  Learn red, Due green.
- Signed in: under the list, centred, `Studied N cards today.` (`1 card`
  when one). N counts cards whose last answer was since today's 4am
  rollover, across all decks.
- Not signed in: under the list, the pairing panel instead (see Signing in).
- The row under the d-pad has a rounded grey wash. Up on the first row and
  Down on the last row stay there. Returning to the list keeps the row last
  selected (or the newly imported deck after an import).
- OK opens the deck's overview.
- Menu on a deck row opens an Anki-style pop-up menu (see Menus) with:
  Options, Stats, Sync, Import, Night Shift, Dark mode, Command center.
  With no deck selected the same without Options and Stats.
- Back leaves the app.
- No decks, signed in: `No decks` centred (`Syncing` while a sync runs).
  No decks, not signed in: only the pairing panel, centred. Menu works on
  both.

### Overview

- The deck's name, centred, in medium weight.
- Cards to study: `New:`, `Learning:`, `To Review:` with their counts in the
  queue colours, and a `Study Now` button with the focus.
- Nothing to study: `Congratulations! You have finished this deck for now.`
  If a learning card is due later today: `The next learning card will be
  ready in N minutes.` (`1 minute.`); the screen refreshes itself when that
  card comes within 20 minutes, showing it as Learning: 1 and Study Now.
  If any card in the deck has ever been answered, a `Review Ahead` button
  takes the focus; otherwise the focus is on Options.
- At the bottom, under a hairline: `Options` and `Stats` buttons.
- The focused button has Anki's blue ring. Down from Study Now / Review
  Ahead reaches Options; Left and Right move between Options and Stats.
- OK on a button presses it. Menu: the deck dialog (as above). Back: the
  deck list.
- Counts here and on the deck list are the same numbers.

### Reviewer

- The card on white: the front, centred, in 20sp. A grey bar below shows
  `N + N + N` (new + learning + review, in the queue colours, the current
  card's queue underlined) over a `Show Answer` button.
- OK (d-pad centre or Enter) shows the answer: the front, a rule, the back,
  and the card's extra text (`aux`, FKV metadata) in smaller grey under it
  when it has one. Show Answer is replaced by four buttons, `1 Again`,
  `2 Hard`, `3 Good`, `4 Easy`, each under the interval it would give
  (`<1m`, `10m`, `3h`, `4d`, `1.5mo`, `2.1y`).
- Keys 1-4 answer only once the answer is shown; before it they do nothing.
  OK after the answer is shown does nothing. A held key answers one card,
  not several. Left and Right do nothing.
- Up and Down scroll a long card by about a line and a half; the card
  starts scrolled to the top.
- The interval shown on a button is exactly what that key gives.
- After an answer the next card shows at once. When nothing more is due
  (or, in Review Ahead, no other card), the overview returns.
- Menu: the Anki-style menu with `Remove from deck`, Night Shift and Dark mode. Choosing it removes the
  card at once without asking, shows the next card, and toasts `Removed
  from <deck>`. Cancel or Back closes the dialog and leaves the card.
- Back: the overview, its counts updated. The answer is not lost: an
  answered card stays answered.
- Review Ahead serves studied cards not yet due, lowest predicted recall
  first (then earliest due), never the same card twice running, and goes
  on until Back. A deck with one studied card ends Review Ahead after it.
  Its bar counts the deck's studied cards as reviews (those in learning as
  learning), as Anki's filtered deck counts the cards it holds.

### Options

- The deck's name in small grey, then a white card `Daily Limits` with
  `New cards/day` and a ringed box `‹  20  ›` (or `‹  Auto  ›`).
- Right adds 1 (up to 9999); Left takes 1 off, and Left at 0 is Auto. Left
  on Auto stays Auto. Digits replace the number (typing 1 then 5 gives 15);
  further digits append, up to 9999. `*` sets Auto. Del drops the last digit
  (to Auto from 0).
- Up and Down move the ring between `New cards/day` and, under a `Display`
  heading, `Show progress` (`‹  Off  ›`, off by default). Left, Right and OK
  flip it. It is kept on this device for every deck, never synced. When on,
  the reviewer shows `N.NN%` (two decimals, rounded down) in small grey at the right of the counts line:
  100% minus the share of the deck's cards still New.
- Back saves the value (if changed it syncs) and shows the deck's overview,
  whose counts reflect it. There is no Cancel.
- Menu does nothing.

### Stats

- At the top two radio labels: `◉ <deck name>` and `○ Collection`. Left
  picks the deck, Right the collection; switching scrolls back to the top.
- Then white cards, each under its centred name:
  - **Today**: `No cards have been studied today.` or `Studied N cards
    today.` and `New: N, Review: N`.
  - **Future Due**: a green bar chart (labels 0, 10, 20), `Due today`,
    `Due tomorrow`, `Average: X reviews/day`, `Total: N reviews`.
  - **Card Counts**: a pie (a grey disc when empty) and rows New, Learning,
    Relearning, Young, Mature with counts and percentages, then Total.
  - **Review Intervals**: a blue bar chart, `Average interval`, `Longest
    interval`.
  - **Card Retrievability**: a green bar chart (0%, 50%, 100%), `Average
    retrievability: X%`, `Estimated total knowledge: N cards`.
- Up and Down scroll the page; Up at the top and Down at the bottom stay.
- Back: the deck's overview. Menu does nothing.
- The numbers match the tablet's Stats for the same cards.

### Dialogs

- Import (from Menu): asks for storage permission the first time. Denied:
  toast `Storage not allowed`. No files: toast `No .fkv files in Documents
  or Download`. Otherwise a list titled `Import` of the files' names without
  `.fkv`, newest first. Picking one makes a deck of it (below); Cancel or
  Back closes it.
- Command center (from Menu): an address field and a password-style token
  field (dots shown when one is saved), Save and Cancel. Save with an empty
  token keeps the saved one; with either missing, a toast says `Both the
  address and the token are needed`. Down and Up move between the two
  fields and the buttons.

## Behaviour

**Queue (per deck).** Learning and relearning cards first once due (steps
1m 10m; relearning 10m); then the day's reviews (due before the next 4am),
earliest day first and in a random order within a day (Anki's "Due date, then
random"; fixed for the day, so a card keeps its place), with new cards mixed in evenly, a review first; then
learning cards due within 20 minutes; then the deck is done. New cards come
in deck order. A deck with a number lets that many new cards in per Anki
day, counting cards first answered on either device since 4am. Auto lets in
enough new cards to bring New + Learning + Due to 20 (none when Learning +
Due is 20 or more). The bundled Spanish deck is on Auto; an imported deck
starts at 20 a day.

**Scheduling.** As fsrs4anki_scheduler.js v6.1.1 in Anki 26.09 (see
AGENTS.md): Anki days from 4am, fuzz seeded by card and last review so phone,
tablet and button labels agree, Hard ≤ Good < Easy. The core's self-tests
pin it; QA checks only that labels match what each key gives and that the
same card is due the same day on the tablet.

**Storage.** The collection is `collection.json` in the app's private
storage, saved 1.5 s after an answer or removal, on leaving, and after a
sync, off the main thread. Answers, removals and option changes survive
force-stopping the app 2 s after them. An unreadable file is replaced by the
bundled deck, with the command center's later reviews winning on the next
sync. Studying needs no network.

**Sync.** `POST /flashcards/sync` to the command center: on opening (each
resume), 30 s after the last answer or removal, after an options change,
after an import, on leaving with unsent changes, and from Menu > Sync. One
sync at a time; a request while one runs is sent after. Incoming changes
show at once on the deck list and overview; a card being studied picks them
up with the next card. Per record the later change wins, a removal beats
any answer, and a card's text always comes from the command center. The
phone's time zone is sent so the tablet rolls the day at 4am local. A 401
forgets the login and starts pairing again.

**Signing in.** A build with a bundled login starts signed in. Otherwise the
app asks the command center for a pairing code and shows `PAIRING CODE`,
the code in large letters, and `Delta, Tablet` (where to type it), or `…`
while asking. It polls every 3 s while the app is in front; once the code
is typed on the website it saves the token and syncs. An expired code or a
failed request shows the reason in red with a `Try Again` button
(`No connection` when the network fails). The panel's lines are centred.

**Import.** Reads `.fkv` files from Documents and Download, on the SD card
and the phone. A good file becomes a new deck named after the file (` (2)`,
` (3)`… if the name is taken), on 20 new cards a day, listed and selected
on the deck list with the toast `Imported N cards into <deck>`; it syncs to
the tablet. A file that isn't FKV is refused with a dialog `Couldn't import
<file>` naming the line and the fault (`Line 3: no || between the key and
the value`); an empty file says `It has no cards.`; over 16 MB says `It is
too big to be flashcards`. If the SD card refuses the read, Android's
"allow access to SD card" is asked once; declining toasts `SD card not
allowed`.

**Permissions.** Internet; read storage, asked only at Import.

**Timed.** Pairing poll every 3 s; save 1.5 s and sync 30 s after an
answer; overview refresh when the next learning card comes due.

## Look

Anki's light desktop theme rather than DESIGN.md's family look: a #F5F5F5
canvas under a status bar of the same grey with dark icons, white cards,
Anki's grey-gradient buttons with 5dp corners and a 2dp blue (#3B82F6) ring
on the focused one, queue colours blue (new), red (learning), green
(review). Card text 20sp sans-serif, centred. No app header.

## Open questions

1. Sync failures are recorded but never shown: offline, an old command
   center or a 400 leaves no trace on screen. Should the deck list say why
   the last sync failed (as `Studied N cards today.` sits), or is silence
   intended?
2. Options and Stats opened from the deck list's Menu return, on Back, to
   the deck's overview rather than the deck list. Intended?
3. Menu > Command center is offered even when paired, and Save replaces the
   pairing token with a typed one. Should it stay in the Menu once paired?
4. Remove from deck has no undo. Is an accidental Menu, OK on a card
   acceptable as is?
5. `flashcards/README.md` holds only "I DON'T KNOW". Replace it with a
   one-paragraph description, or delete it?
