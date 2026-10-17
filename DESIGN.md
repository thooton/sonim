# The family look

How the light, list-and-form apps (Canvas, List, Mail, Places, Serve, Source,
Updater) are drawn, so they read as siblings of Charge and Books rather than
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
- Every screen says what to do next, including empty ones.

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
7. **Say what to do.** Empty screens use `Family.empty`: a short title and,
   under it, the key to press. A status screen shows its main action as a
   `FamilyButton`, not only in the Menu.
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
| Places | `#0F3624` | `#A3C6B2` | `#1D6A45` | `#EAF5EF` |
| Serve | `#0D3236` | `#9CC3C6` | `#11707A` | `#E7F4F5` |
| Source | `#291741` | `#BBA9D3` | `#6334A3` | `#F2EDFA` |
| Updater | `#1B2633` | `#A8B6C6` | `#2B5E92` | `#EAF1F8` |

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

## Places: a place as Google Maps shows one

The results list is the family's. A place's own screen (`PlacePage`) takes
its layout from Google Maps' place sheet, since that is how people expect a
place to read, while keeping the family's colours and Medium for emphasis:

- **A head on white over the grouped page.** The name at 17sp Medium; the
  rating with five gold stars filled to it (a drawable, so 4.5 shows half a
  star) and the count; kind, price range and distance in muted text, with a
  wheelchair in the accent when the entrance takes one; "Open" in green or
  "Closed" in red, Medium, then when that changes.
- **Chips for what Menu can do.** Call (filled in the accent, as Maps fills
  Directions) and Website (the wash), under the head.
- **Icons, not labels.** The first card is address, phone and website, a
  muted Material icon leading each row and hairlines inset to the text. The
  phone and website are in the accent, the website shown as its host.
- **Headed sections.** Hours (two columns, today in Medium, other hours
  under a caption), About, Amenities (ticked chips for what it has, crossed
  and muted for what it hasn't; payment, parking and access as icon rows of
  chips; fuel prices) and Reviews (the rating at 30sp Light beside its
  stars, Google's summary marked with the accent's sparkle, then each review
  with its writer's initial in a circle). Headings are 14sp Medium on the
  page, in line with the text on the cards.
