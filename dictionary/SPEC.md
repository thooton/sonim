# Dictionary

## Purpose

An offline English dictionary and thesaurus (WordNet 3.1, 147,478 headwords
including phrases) with Anniversary Gregg shorthand (Grascii), for the Sonim
XP3: 240x320, d-pad, soft keys, T9, driven one-handed with keys only. The user
looks a word up, reads it, maybe hears it said, and leaves; or types a Gregg
outline to see what English it is.

It must stay:
- **Offline and permission-free.** Everything is in the APK; no network, no
  accounts, no permissions, no dependencies (not even `common/`).
- **Quick.** A page opens at once from any search; nothing loads or spins.
- **One headword per page,** three pages under one search capsule: home,
  a word, the search.
- **Quiet.** Nothing on screen explains how to use it (DESIGN.md rule 7).

It must never become an online dictionary, a word-game or flashcard app, a
notes app, or a branded clone of another dictionary.

## Not wanted

- Dictionary.com / Thesaurus.com branding: their tabs, logos, icons and
  "Definition for" line were removed (1.2.0). Icons are Material Symbols.
- A speaker icon on the page; Pronounce lives in the Menu.
- OK doing anything on a word's page (1.2.1, 1.2.2). OK at home opens the
  Word of the Day; on a word it does nothing.
- Left/Right leafing to the next or previous headword; they switch tabs.
- Reopening on the last word viewed: the app always opens on home.
- Outlines and words mixed in one search (1.3.1): the shorthand search is
  its own search.
- Menus anchored to the Word of the Day card (1.3.2): they drop from the
  search capsule.
- Instructional text, key hints, example input, counters (DESIGN.md 7, 8).
- Shorthand editions other than Anniversary (1929).

## Screens

Every screen has the white bar at the top: a back arrow (on every page but
home, and while searching) and the search capsule (box plus round blue
button with a magnifier, or a pen in the shorthand search). The capsule only
takes keys while searching. Menu acts on release, OK on press.

### Home

- The Word of the Day card: "WORD OF THE DAY", today's date in capitals
  ("OCTOBER 28, 2026"), the word, its first part of speech in italics, its
  first definition (no examples), and "Meaning and examples" underlined in
  blue. No back arrow.
- The Word of the Day is a plain lowercase word of 6 to 12 letters (no
  capitals, digits, spaces or hyphens), a noun, verb or adjective, with an
  example on its page (one that uses the word, as above). It is the same all day and changes at local
  midnight; the same date always gives the same word.
- **OK:** opens the Word of the Day's page on Definitions.
- **Menu:** popup under the capsule: Word of the Day, Search, Shorthand.
  Word of the Day opens it; Search opens the word search; Shorthand opens the
  shorthand search.
- **Up:** if the card is scrolled to the top, opens the word search, but only
  on a fresh press (holding Up never jumps into the search). Otherwise
  scrolls up.
- **Down:** scrolls a screen less two lines; at the bottom stays.
- **Left/Right, digits:** nothing.
- **Back:** leaves the app.

### A word's page

- The headword at 25sp (smaller when one of its words is wider than the
  line, so no word breaks mid-word), cased as its first meaning writes it
  ("March", "Paris", "ice cream"), then up to three tabs: Definitions, Thesaurus, Shorthand.
  The open tab is SemiBold over a 2dp ink rule.
- **Definitions** (shown for every WordNet word): each part of speech
  (noun, verb, adjective, adverb, in that order) as a serif heading; senses
  numbered from 1 under each heading, in a grey hanging margin; a sense with
  examples ends in ":" and its examples follow in italics, without quote
  marks, behind a pale grey rule. A sense shows only the examples that use
  the headword itself, in any form (dogs, went, kicked the bucket) or another
  spelling of it in that sense (civilise, civilized): WordNet shares a
  meaning among its synonyms, so on "dog" the meaning "frump, dog" never
  shows "she got a reputation as a frump". A sense left with none has no ":".
- **Thesaurus** (shown only if some sense has a synonym other than the word
  itself): per sense with synonyms, "noun as in <gist>" (gist: the gloss up
  to its first `;` or `(`, cut at a word to at most 34 letters plus "…"),
  then the synonyms, each over a green underline. No duplicates, and never
  the headword itself.
- **Shorthand** (shown only if Grascii has an outline): each outline at 18sp
  SemiBold, and under it, for outlines of more than one stroke, the strokes
  parted by middle dots (`' · A · B · T`).
- A word only Grascii has (could, theirs, into) has only the Shorthand tab.
- Opening a page shows the top of it, on the tab the search row asked for
  (Definitions, or Shorthand from the shorthand search); if that tab isn't
  shown, on Definitions (Shorthand for a shorthand-only word).
- **Left/Right:** previous/next shown tab, skipping hidden ones; at the
  first or last tab stays. The new tab shows from the top.
- **Up/Down:** scroll a screen less two lines. Up at the top opens the word
  search from Definitions or Thesaurus, the shorthand search from Shorthand
  (fresh press only, as on home). Down at the bottom stays.
- **OK:** nothing.
- **Menu:** popup under the capsule: Pronounce, Search, Shorthand.
  Pronounce says the headword with the phone's text-to-speech in US English;
  it is greyed out and does nothing when the phone has no English voice.
- **Back:** the page you came from (a word, or home), on Definitions, at the
  top. Opening the page you are already on adds no step to Back.

### The search (words)

Opened from Menu > Search or Up at the top of a page. The box is empty, has
the hint "Search a word or phrase", the cursor, and the keys.

- **Empty box:** "Recent Searches" with "Clear" (dark red) at its right,
  then up to 20 words opened from a search, newest first, no repeats, each a
  faint clock, the word, and a faint arrow. With no recent searches the page
  below the bar is blank.
- **Typing:** up to 8 rows, each a magnifier, the word with the part past
  what was typed in SemiBold, and an arrow. First the dictionary form when
  that differs from what was typed (mice: mouse, went: go, biggest: big),
  then every headword that begins with the query, in alphabetical order,
  with shorthand-only words among them. Case and extra spaces don't matter.
  Rows update on every key.
- **Nothing matches:** the query turns red and the only row is "No results".
- Long words end in "…" on one line.
- **Down:** from the box to the first word (skipping Clear), then row by row;
  on the last row stays. **Up:** row by row; from the first word to Clear
  (when shown), then back into the box. The picked row is washed pale blue;
  Clear gets its own focus mark. The list scrolls to keep the picked row in
  view. With no rows Up/Down do nothing.
- **Left/Right:** move the cursor in the box; nothing while a row is picked.
- **OK:** on a row opens that word; on Clear empties Recent Searches (the
  list goes blank, focus back to the box). With no row picked: opens the
  word typed, else its dictionary form, else the word typed as only the
  shorthand dictionary has it (into), else the first word it begins, else
  the first suggestion; with an empty box or no match, nothing.
- Every word opened from the search goes to the top of Recent Searches.
- **Menu:** popup with one item, Shorthand: switches to the shorthand
  search, keeping what was typed.
- **Back:** closes the search (the text is dropped) and returns to the page
  it was opened from, on the same tab, scrolled to the top.

### The shorthand search

The same page, with a pen in the search button, the hint "Grascii outline",
and capitals on.

- **Empty box:** the same Recent Searches, each row a clock and the word;
  these open on the word's Shorthand tab.
- **One outline typed:** up to 12 rows, each a faint pen, the outline in
  SemiBold and its English in grey: outlines it is (one written exactly as
  typed first), then longer outlines it begins (shortest first), then those
  with similar strokes (at most one step away in Grascii's similarity graph,
  nearest first). Case, annotations (`, . | ~ _ ( )`) and aspirates are
  ignored, as in Grascii's search. Each row opens its word's page on the
  Shorthand tab.
- **Outlines separated by spaces (a phrase):** first any outline they make
  joined (`IS 'US`: ice house); then one SemiBold line with the English each
  reads as (its first row), in order, `?` for one that reads as nothing
  (`S 'US`: his house), which is a row that opens when the dictionary has
  that phrase and plain text otherwise; then each outline's own rows, less
  any already shown as the joined outline.
- Input that is not Grascii, or matches nothing: red query, "No results".
- **OK:** the picked row, or else the first row.
- **Menu:** one item, Words: switches to the word search, keeping the text.
- Up, Down, Left, Right, Back: as in the word search.

## Behaviour

- **Data:** WordNet in `assets/words.dat` and `synsets.dat` (meanings
  deflated in blocks of 128, read on demand), WordNet's morphology exceptions
  in `morph.dat`, Grascii's Anniversary dictionary in `shorthand.dat`; all
  read-only in the APK. The licences (WordNet, Grascii, IBM Plex) ship as
  assets.
- **Survives a restart:** only Recent Searches (the app's own preferences,
  key `recent`). The app always opens on home with an empty Back history,
  also when launched from its icon while it is still running.
  Not backed up (`allowBackup="false"`).
- **Network:** none. **Permissions:** none.
- **Timed:** the Word of the Day follows the local date.
- **Text-to-speech:** started at launch; Pronounce greys out until it is
  ready and stays grey if there is no English voice.
- **Orientation:** portrait only; opening or closing the keyboard doesn't
  reset the screen.
- An unreadable asset is a broken build, not a condition: the app may crash.

## Look

DESIGN.md's "Dictionary" section covers it. In addition:
- Search rows are 29dp tall over a 1dp hairline inset 12dp on both sides.
- "No results" is 12.5sp muted grey; the red query is `#B3261E`.
- The pale blue wash on the picked row is `#EBF2FF`; Clear is `#8F1A06`.

## Open questions

1. The Word of the Day is picked when the app starts, so if the app stays
   open past midnight home keeps yesterday's word and date until it is
   relaunched. The README says "new at midnight": should it refresh then (or
   whenever home is shown)?
2. Back returns to the previous word on its Definitions tab, at the top,
   not on the tab and scroll position you left. Intended?
3. With no recent searches (and right after Clear) the search shows a blank
   page. Should it say something short, such as "No recent searches"?
4. Typing a letter on home or a word's page does nothing; the search opens
   only from Menu or Up. Should typing start a search?
5. "Meaning and examples" on the card looks like a link but is not
   separately focusable; OK anywhere on home opens the word. Is that enough?
