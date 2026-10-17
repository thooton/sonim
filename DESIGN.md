# The family look

How the light, list-and-form apps (Canvas, List, Mail, Serve, Source)
are drawn, so they read as siblings of Charge and Books rather than
as stock Android with a coloured bar. The pieces live in `common/ui`
(`Family`, `res/drawable/family_*`, `res/values/family*.xml`); this is why
they look the way they do. Charge and Books keep their own looks.

## What Charge and Books get right

- A dark, quiet frame. The header is a deep colour and the status bar is the
  same colour, so the two read as one piece of chrome around a white page.
- One hue, used sparingly. Colour marks focus, the main action and the odd
  state (open, late, serving); everything else is near-black text on white
  with tinted greys.
- Hierarchy from weight and colour, not size. Two or three text sizes per
  screen, with Roboto Medium for what leads and a muted grey for what
  follows.
- One left edge. Every line of text starts 10dp in; hairlines are inset to it.
- Focus you can see at arm's length: a whole row lifted into a rounded card.
- Nothing on screen explains itself. What to do next is shown (a button,
  the focus, an icon), never spelled out.

## The rules

1. **Four tones of one hue per app.** `primary` (the header and status bar,
   15 to 20% lightness), `focus_rule` (the accent, 35 to 40%), `focus_fill`
   (a wash near 95%) and greys (`divider`, `text_muted`, `field_fill`)
   tinted toward the hue. Semantic colours are fixed across the family:
   green `#1E7B34` for open or done, red `#B3261E` for late or failed, gold
   `#C58A00` for a star.
2. **Colour means something.** The accent appears on focus, on the one main
   action, and on what is new or actionable (an unread time, an update's
   target version, a phone number). Nothing is coloured to decorate.
3. **Three sizes.** Header 15sp Medium; lead text 13.5sp; secondary 11.5 to
   12sp. Titles of a detail page 16sp Medium. Emphasis is Roboto Medium
   (`Family.MEDIUM`), never bold.
4. **One edge.** Text at 10dp. List hairlines (`family_divider`) start there.
5. **Focus is a card.** `family_focus` (or `family_pick` for lists whose
   typing box keeps the focus): 4dp in from each side, 8dp corners, the wash
   with a 1.5dp accent outline. The text does not move when it lands.
6. **Fields are soft.** `family_field`: a tinted, borderless 30dp pill with
   its label inside, turning white with the accent outline while it takes
   the keys. A form that is a list of lines (Mail's composer) uses
   `family_line_field` rows instead.
7. **Show, don't tell.** No text tells the user how to use the app: no
   "Press Menu to…", no keys to press, no example input in a field ("Coffee,
   pharmacy…"), no "blank keeps it", no sentence explaining what a screen is
   for. Empty screens use `Family.empty` with a short title saying what is
   (or isn't) there, and a detail line only for a fact such as why a fetch
   failed. A status screen shows its main action as a `FamilyButton`, not
   only in the Menu; a saved secret shows as dots in its empty field; a
   button that can't act yet is greyed out rather than explained. What a
   field expects is fine as its hint, when the label alone doesn't say it
   ("Artist - Title", "Title, author or ISBN"): that is the field's format,
   not a lesson in using the app.
8. **No header names the app.** You just opened it. A header says where
   you are or how things stand (a folder, "Up to date"), and a screen with
   nothing like that to say has no header at all. Nor does a screen carry
   a counter (items, results) for its own sake.
9. **Grouped pages for details.** A detail or status screen (a place, an
   assignment, Serve) sits on `grouped_page` with its facts on white
   `family_card`s, 6dp from the edges, a muted label over each value.

Stock dialogs use `FamilyDialog` (rounded, sentence-case buttons in the
accent) and popup and context menus `FamilyPopupMenu` (rounded, over the row
they are about). The theme's `colorControlHighlight` is `highlight`, the
accent at about 14% opacity, so the item under the d-pad is washed in the
app's hue without hiding anything. A view that takes the focus only so keys
land somewhere (a `ScrollView`, an empty state) turns off the platform's
default focus highlight (`defaultFocusHighlightEnabled="false"`), which
would otherwise lay that wash over the whole page.

## The palettes

| App | primary | on_primary_dim | focus_rule | focus_fill |
| --- | --- | --- | --- | --- |
| Canvas | `#45142A` | `#D3AEBE` | `#8E1F48` | `#F9EEF3` |
| List | `#23201C` | `#C7B9A6` | `#A85C00` | `#FBF2E5` |
| Mail | `#004F9B` | `#A9C7FF` | `#004F9B` | `#E4EDF8` |
| Serve | `#0D3236` | `#9CC3C6` | `#11707A` | `#E7F4F5` |
| Source | `#291741` | `#BBA9D3` | `#6334A3` | `#F2EDFA` |

Mail's row is Thunderbird's: see below. Each pair was checked for contrast:
the dim header text is at least 7:1 on its header, the accent at least 4.5:1
on its wash, and muted text at least 4.8:1 on white.

## Mail: Thunderbird on a flip phone

Mail takes its look from Thunderbird for Android rather than from Charge
and Books, because that is the mail client people know. It keeps the rules
above (one accent, Medium for emphasis, the focus card, `Family.empty`), and
departs from them where Thunderbird does:

- **Light chrome.** The app bar and status bar are Thunderbird's warm
  surface grey (`bar`, `#F1EDEC`) with dark text and status icons, not a dark
  band. The folder pane slides over the screen from the left on Left, on
  Thunderbird's `#FCF8F8`, with the account's blue bar at its head.
- **Thunderbird's palette.** The accent is Thunderbird's primary blue
  `#004F9B`; the folder under the d-pad is a filled pill in its secondary
  container `#2E61A0`, the open folder a pale wash of it. Read conversations
  sit on `#F6F3F2` in a softer ink (`#45474A`), unread ones on white with a
  blue dot, Medium type and a blue time, as Thunderbird's message items do.
- **Its icons.** The Material Symbols icons are copied from
  thunderbird-android (Apache License 2.0), noted in each file. People are
  monograms: an initial in a circle of the accent at 15% with a 1dp ring.
- **Keys shown in menus.** The digits, * and # are shortcuts on the list and
  in a conversation, and each menu item shows its key on a keycap, as
  Thunderbird's menus show their shortcuts.

## Places: Google Maps, dark, for a d-pad

Places is not one of the light family. It takes Google Maps' dark theme,
since Maps is the app people know for finding a place, and lays it out for
a 240x320 screen and a d-pad rather than a finger. Its colours were sampled
from Maps screenshots (`res/values/colors.xml`):

- **The sheet.** `#131314` everywhere, status and navigation bars included;
  hairlines `#3E3E40`; the clear button and recent searches' clocks on
  `#303030`.
- **Google Sans.** Google Sans Flex, regular and medium (OFL), bundled as
  fonts: names 14.5sp in the list and 18sp atop a place, facts 12 to
  12.5sp, the text `#E3E3E3`, what follows it `#ABABAD`.
- **Maps' teal.** Icons, links, the cursor and the loading bar are the
  bright teal `#7DD3E3`/`#5CB8C9`. Filter chips are 8dp-rounded and
  outlined, and filled `#00363F` with `#A6EEFB` text and a tick when on.
- **Maps' signals.** "Closed" `#F2B8B5`, "Open" `#81C995`, stars `#FFC42A`,
  the wheelchair `#A8C7FA`.
- **Focus, which Maps doesn't have.** The row under the d-pad sits on a
  teal-tinted `#1C2B2E`; the chip under it takes a pale teal ring.

What changes from Maps, for the keys and the screen:

- **A compact list.** A result is three lines, as Maps words them: the
  name; "4.8 ★ (112) · $10-20 · ♿ · Cafe"; "Closed · Opens 7:00 AM · 3.5
  mi". No photos and no chips, so three or four fit the screen and Up and
  Down move one place at a time. The picked row doesn't grow.
- **One page for a place, no tabs.** Up and Down scroll it: the rating,
  kind and status; one photo; address, hours, website and phone, each
  after a teal icon; then Reviews and About under headings. The name stays
  at the top, large, and shrinks to one Medium line, with a hairline under
  it, as the page scrolls under it.
- **OK lists what can be done, no button bar.** On a place, OK opens a
  list of choices, each after a teal icon: Call, Copy address, Copy website
  URL. Menu's choices take icons too.

## Updater: as the App Store shows it

The Updater takes its look from the App Store's Updates page, since that is
the screen people know for keeping apps up to date. It sets aside the family
rules where Apple's page differs:

- **Apple's chrome and colours.** A near-white bar (`#F9F9F9`) with a grey
  hairline under it and "Updates" centred in it, the status bar the same
  colour with dark icons. Black names, iOS's secondary grey (`#8E8E93`) for
  dates, its separator grey for hairlines (inset on both sides) and its
  system blue (`#007AFF`) for everything that can be pressed.
- **Large bold section headings.** Available, Installed and Not Installed,
  at 18sp bold, as "Updated Recently" is on the App Store.
- **Apple's rows.** A rounded-square icon (an installed app's own, cropped
  to what it paints; a coloured tile with its initial for one that isn't on
  the phone), the name, the date of its newest change ("Today", "Yesterday",
  "Jun 15, 2019"), and on the right the capsule: UPDATE, OPEN or GET in bold
  blue capitals on `#EFEFF4`. Under it, full width, what's new and a blue
  "more". While an app downloads, the capsule becomes the App Store's ring
  with its stop square; while it waits or installs, the ring spins.
- **Focus as a press.** The row under the d-pad greys as an iOS cell does
  while it is touched, and its capsule fills blue with white text, as the
  button does while pressed. OK presses it. Right moves to "more" (filled the
  same way), which opens What's New. Update All sits on the bar's right, as
  on iOS 12, reached with Up from the top row.
- **What's new comes from commits.** CI builds every app on each push, so
  the subject of an app's newest commit ("Mail: drop the conversation's
  position from the bar") is what's new in its APK, and its date is the
  App Store's date. Without them the rows show the version instead.

## Dictionary: home, a word, the search

Dictionary has three pages under one search capsule (home with the Word of
the Day, a word, the search), and sets the family rules aside for a look of
its own:

- **Light chrome.** A white bar under a white status bar, holding a back
  arrow (on every page but home) and the search capsule:
  white, a 1dp ink (`#252528`) outline, a round light-blue (`#BDD9FF`) search
  button at its end.
- **Its type.** Headwords and parts of speech in IBM Plex Serif SemiBold,
  everything else in IBM Plex Sans, bundled since the phone has neither.
- **Home.** The Word of the Day on a white card with a 2dp blue
  (`#0057FF`) rule and rounded corners: "WORD OF THE DAY", the date in
  capitals, the word in Plex Sans SemiBold, its part of speech in italics,
  its first definition, and "Meaning and examples" as an underlined blue
  link.
- **A word's page.** The headword at 25sp, then tabs (the open one SemiBold
  over a 2dp ink rule, a hairline under the row). Definitions: each part of
  speech as a serif heading, senses numbered in grey in a hanging margin, and
  examples in italics behind a pale grey rule. Thesaurus, shown only when
  WordNet has synonyms: "noun *as in* gist" and the synonyms over a thick
  green (`#00B414`) rule.
- **Menu holds the actions.** Menu opens a stock popup menu over the page
  (Word of the Day and Search at home, Pronounce and Search on a word); OK
  opens nothing on a page. Up from the top of a page moves into the search
  box, as it would on any screen with a search bar above it.
- **The search.** Recent Searches, with Clear in dark red, each row a faint
  clock, the word, and a faint arrow over an inset hairline; while typing,
  the same rows with a magnifier and the rest of each suggestion in
  SemiBold. The row under the d-pad is washed in a pale blue (`#EBF2FF`).
- **Its icon.** An open book with a gold ribbon on blue.
