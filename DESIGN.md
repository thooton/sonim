# The family look

How the light, list-and-form apps (List, Mail, Serve, Source)
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
| List | `#23201C` | `#C7B9A6` | `#A85C00` | `#FBF2E5` |
| Mail | `#004F9B` | `#A9C7FF` | `#004F9B` | `#E4EDF8` |
| Serve | `#0D3236` | `#9CC3C6` | `#11707A` | `#E7F4F5` |
| Source | `#FEF7FF` | `#49454F` | `#6750A4` | `#EADDFF` |

Mail's row is Thunderbird's, and Source's is Material 3's: see below. Each pair was checked for contrast:
the dim header text is at least 7:1 on its header, the accent at least 4.5:1
on its wash, and muted text at least 4.8:1 on white.

## Canvas: Week Strip

Canvas follows the Week Strip design proposal rather than the family look:
a calm, planner-style school app built around the week.

- **One typeface.** Bricolage Grotesque (OFL), bundled in five static
  weights: screen titles 18sp/800, page titles 16sp/800, rows 12sp/600, body
  10.5sp, meta 9sp, labels 8.5sp/700 in spaced capitals. Times use tabular
  figures. Large text raises every step by 15%.
- **Ink, paper and one yellow.** Ink `#101828` means selected (the day in
  view, a chosen answer, the main button); the highlighter `#FFE45C` means
  focus and nothing else, as a stroke under a title or a fill on a button,
  day or option. Turned in is green `#05734F` on `#E3F6EF`. Course colours
  (`#FF5A36`, `#3D5AFE`, `#00A389`, and five more) mark which class
  something is, never its status. Nothing is red; grades never change colour.
- **Dates, not countdowns.** "Due Tuesday, Oct 20 at 10:30", drawn beside a
  tear-off calendar page. Late work "was due" and "can still be turned in".
- **No softkey bar.** The left softkey opens Options and the right one is
  the screen's shortcut, but no bar labels them; the rows get the space.
- **Light and dark.** In the dark theme ink and paper swap and the
  highlighter stroke drops to about 42%.
- **Its icon** is the week strip in miniature: on ink, a yellow day with a
  tick, dim days beside it, and three course-coloured dots under it.

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

- **A compact list.** A result is a few lines, as Maps words them: the
  name; "4.8 ★ (112) · $10-20 · ♿ · Cafe"; the address without its
  country, wrapped rather than cut (Maps leaves it out, but on a list of branches it is what
  tells them apart); "Closed · Opens 7:00 AM · 3.5 mi". No photos and no
  chips, so two or three fit the screen and Up and Down move one place at
  a time. The picked row doesn't grow.
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
  blue capitals on `#EFEFF4`. Under it, full width, the start of what's new
  (no "more": What's New is in the menu). While an app downloads, the capsule becomes the App Store's ring
  with its stop square; while it waits or installs, the ring spins.
- **Focus as a press.** The row under the d-pad greys as an iOS cell does
  while it is touched, and its capsule fills blue with white text, as the
  button does while pressed. OK presses it. Update All sits on the bar's right, as
  on iOS 12, reached with Up from the top row.
- **What's new comes from commits.** CI builds every app on each push and
  writes each app's newest commits into the release's manifest.json, so the
  subject of an app's newest commit ("Mail: drop the conversation's
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
  green (`#00B414`) rule. Shorthand, shown only when Grascii has an outline
  (and the only tab for a word only it has): each outline at 18sp SemiBold,
  its strokes under it in grey, parted by middle dots.
- **Menu holds the actions.** Menu opens a stock popup menu under the search capsule
  (Word of the Day, Search and Shorthand at home, Pronounce, Search and
  Shorthand on a word; while searching, the other search). OK
  opens the Word of the Day at home and nothing on a word. Up from the top of a page moves into the search
  box, as it would on any screen with a search bar above it.
- **The search.** Recent Searches, with Clear in dark red, each row a faint
  clock, the word, and a faint arrow over an inset hairline; while typing,
  the same rows with a magnifier and the rest of each suggestion in
  SemiBold. The shorthand search is the same page with a pen in the search
  button and "Grascii outline" as the hint; its rows are the outlines the
  query reads as, each after a faint pen, the outline in SemiBold and its
  English in grey. The row under the d-pad is washed in a pale blue (`#EBF2FF`).
- **Its icon.** An open book with a gold ribbon on blue.

## Source: Tonal

Source takes its look from the Tonal proposal: Material 3's colour roles
generated from `#6750A4`, built around the d-pad. It keeps
the family's rules on words (nothing explains itself, no em dashes) and
departs from it where the proposal does:

- **Light chrome, tonal surfaces.** No band: the status bar is the page
  (`surface`, `#FEF7FF`) with dark icons. Colour comes from soft tinted
  containers (`container`, `container_high`); saturated `accent` is kept for
  the one action that matters, a page's button, focus rings and
  progress. The family's names (`primary`, `focus_fill`, `row`...) are kept,
  pointed at the matching roles, for the shared drawables and dialogs.
- **A dark theme.** `values-night` holds Material 3's dark roles for the same
  seed. The phone has no dark setting, so Options switches it, by giving
  the app's activities a night configuration (`Tonal.attachBaseContext`).
- **DM Sans, and Bold rather than Medium.** The proposal's face, bundled as
  static instances of Google Fonts' variable DM Sans at its 14pt optical
  size (OFL): row titles, the picked tab, page titles and a page's button
  in Bold, labels in SemiBold, tabs at rest in
  Medium, everything else regular (`Tonal.FONT` and the rest, and
  `@font/dm_sans_family` in the theme).
- **Pill tabs** in a tonal track, each with the icon of what it finds, and a
  pill search field with a search icon that rings in `accent` while it takes
  the keys.
- **No soft-key bar.** The rows get the full height. A result's page has
  one filled pill of `accent` under its card saying what OK does there
  (Download, Try again, Play, Read), gone while it downloads.
- **Look before you get.** OK on a result opens its page (or plays a saved
  one); it is downloaded only from there, never straight from the list.
- **State once asked for.** A result's row ends in `StateButton` only once
  it is downloading: a ring filling with the percentage, an outline with a
  clock while queued, the success container with a tick once saved, the
  error container with a retry arrow when it failed. The picked row is a
  filled 14dp block of `accent_container`, not an outlined card.
- **Drawn covers.** Soulseek and the book source send no artwork, so `Cover`
  draws the name's initials on one of six tones: a square for a song, a
  square with a record behind it for an album, 2:3 with a spine for a book. A
  failed download's cover loses its colour.
- **Folders always show.** A music result's folders, as the sharer named
  them, are a line of their own under its name, cut from the start so the
  deepest folders stay in view; a detail page shows them whole. Who shares
  it stays on every row, after a short "320 · 10.8 MB" in the mockup's
  manner; its length and the sharer's speed are on its page.
- **Wavy means moving.** `Wavy` draws progress as a sine path whose phase
  drifts while the download runs; failed, it goes straight and still in the
  error colour.
- **Never a dead end.** An empty or failed search shows what is so and a
  next step that takes the d-pad (search albums instead, search again), with
  the engine's last log lines below it.

## Tablet: e-ink paper and one highlighter

Tablet follows its own design proposal (where it was called Margin) rather
than the family look: the e-ink tablet itself, on a 240x320 screen.

- **Paper, graphite and one yellow.** Paper `#E3E4DF` for the page, sheet
  `#F7F7F3` for what rises over it (sheets, cards, the drawer), ink
  `#222428`, graphite `#62666D` for what follows, rules `#C5C7C1`. The
  highlighter `#F2D343` means "this is selected" and nothing else: a marker
  stroke with slanted ends (`Mark`) behind the selected file's or job's
  title and the selected choice, the zoom level, the part of the page the
  minimap shows. Never a filled box around
  a row.
- **Two faces.** Literata (semibold) for titles, Atkinson Hyperlegible for
  everything else. Spaced-capital labels
  at 8.5px bold over sections and sheets.
- **Larger than the mockup.** Every size is the proposal's pixels times
  1.25 (`Look.SCALE`); at its own size the 9px text could not be read on
  the phone.
- **The shelf has the Library.** Covers slide between slots, the middle one
  outlined in ink, the rest greyed and fading toward the edges, with dots
  for where you are (a count instead, past twelve files). A file's actions
  are a menu OK opens, Open first.
- **Transforms is a list.** One line per job, the same height selected or
  not, the selected title under the highlighter; OK opens the job's menu.
- **No soft-key bar.** The keys' jobs never change, so the full height goes
  to content: the header's menu glyph is the only hint, for the drawer.
- **The preview is the page.** A translucent top bar with the file, page
  and the 4 and 6 keys; round arrows where there is more to pan to; a zoom
  pill with its * # 0 keys; a minimap.

## New Yorker: the New Yorker app, on a flip phone

New Yorker takes its look from the New Yorker app and newyorker.com rather
than the family, since that is the magazine people know. Its colours were
sampled from the site's styles (`res/values/colors.xml`), and its icon is
the New Yorker app's own from the app stores: Eustace Tilly, monocle to
his eye and a phone in hand, in ink and white on the site's red, as PNGs
in `res/drawable-*`.

- **White, ink and one red.** White pages and white bars with dark status
  icons; ink `#231F20`, hairlines `#DADADA`, and the site's rubric red
  `#DB3334` for the rubric over a headline and nothing else. The row under
  the d-pad is washed in a deeper step (`#ECE6D9`) of the site's cream
  `#F6F4EF`.
- **Caslon for words, Irvin for headlines.** Text and deks in the site's
  own TNY Adobe Caslon Pro, the deks in its italic as the site's
  `description-core` sets them; the masthead, section titles, rubrics and
  headlines in its TNY Irvin Pro Heading; the date and captions in its
  Graphik (the date in the Medium, as `context-tertiary` does, a shade
  grey); bylines in its Neutraface New Yorker SemiBold, normal case, no
  tracking. These are the faces served by newyorker.com itself, bundled as
  TTF; see the note in the app's `assets/fonts`. Leading follows the site's
  tokens cut to the phone: a headline at 1.13 (article) or 1.27 (rows), a
  dek at 1.41, the body at 1.43; a section's opening drop cap in the
  Caslon, at the body's three-line depth, as the site's first-letter rule
  does; the head of a text section (subhed-break) in the Caslon at its own
  weight, not bolded, a cut above the body's size; the headline of an
  article set in Irvin (hed-standard), not the Caslon the list rows use.
- **Today.** "The New Yorker" set centred in the bar. The front page's lead
  story has its picture across the screen; each section after it is a heavy
  ink rule and the section's name (Irvin, at its own leading), then rows:
  rubric (Irvin, red), headline, two lines of italic dek, byline
  (Neutraface), and a square picture on the right, hairlines between them.
- **The Magazine.** The same rows under the issue's date and the issue's own
  sections (The Talk of the Town, Reporting & Essays, The Critics...).
- **Tabs at the foot.** Today, Magazine, Saved, as the app's bottom bar,
  the open one in ink under a short ink rule; Left and Right move between
  them.
- **The article as the magazine sets it.** Centred rubric, a large headline
  in Irvin, the dek in the italic Caslon, the byline (Neutraface) and the
  date (Graphik Medium, grey), the lead picture and its credit; then the
  text, each section opening on a three-line drop cap, and the last
  paragraph ending on the diamond. Down and Up move two lines, Right and
  Left a screen; on the lists each press moves the same distance.

## Weather: CARROT on a flip phone

Weather takes its look from CARROT Weather, whose colours it uses exactly
(read from CARROT's Android app): the card colour behind the sky for each
condition (`#0077F8` clear, `#0045A3` cloudy, `#001986` night and rain,
`#61A3DE` snow, `#44C5EE` fog, `#0045A2` wind), its sky effects and scenery
palettes, its icon colours, its temperature scale (`#8A01C1` at -60°F to
`#FFCBB0` at 130°F) and its blue `#0B84FE` for rain. The drawings are the
app's own, in CARROT's manner.

What changes from CARROT, for the screen:

- **The sky runs edge to edge.** No card margin: the place, icon,
  temperature and "Feels" sit on the sky, the scenery along its foot, and the
  status bar takes the sky's colour.
- **No cards below it.** The hourly and daily forecasts sit straight on the
  page (CARROT's dark `#000000` or light `#FFFFFF`) with an 8dp margin, under
  small grey capitals.
- **No snark.** It would not fit.
- **iOS's hourly chips.** The hourly forecast shows Temperature, Feels like,
  Precip chance and the rest one at a time, named in a blue chip at the right
  of its heading; Menu or * and # change it.

`weather/mockups/index.html` is the design and is drawn by the same layout
code as the app; see `weather/AGENTS.md`.
