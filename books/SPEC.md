# Books

## Purpose

An e-book reader for the Sonim XP3 (240x320, d-pad, soft keys, T9 keypad),
modelled on Apple Books in dark mode. The user reads one-handed on a flip
phone, often in the dark, turning pages with the d-pad or the keypad. The page
is the whole screen: justified, hyphenated text on black, nothing else but the
percentage through the book in the bottom corner.

It reads EPUB (2 and 3) and plain text (.txt, Project Gutenberg's included)
from the `Books` folder at the top of the phone's storage and of the SD card.
It can also mark books read or unread, delete a book, and convert an EPUB to
MOBI for a Kindle.

It must stay small, quick and offline: a page turn is instant, opening a book
takes a moment, nothing waits on a network. It must never become a store, a
sync service, a file manager, or a reader of other formats it can't show
well. It has no INTERNET permission and must not gain one.

## Not wanted

- No search of the whole filesystem for books: only `Books` at the top of the
  phone's storage and of each SD card (subfolders count). Decided 1.0.1.
- No toasts and no platform popups or context menus: Night Shift's filter
  can't reach system windows, so every notice and sheet is drawn by the app.
- No instructional text: no "Press OK to…", no explanation under an empty
  state (DESIGN.md rule 7). Empty states are a short title only.
- No status bar, header or page numbers on the reading page; no second
  colour scheme for night (Night Shift is a warm filter over everything).
- MOBIs are written for a Kindle, not read: the library never lists them.

## Screens

### Library

Status bar black. Header: "Library" in large bold white at the left; at the
right, in small grey, "1 book" / "N books", replaced by an orange notice
(below) while there is one. Below, one row per book:

- the cover (or, without one, a muted cloth-coloured binding with the title's
  initial and a short rule);
- title in bold, wrapped to at most two lines, the second ellipsized;
- author in grey, ellipsized, if the book has one;
- a status line: an orange **NEW** badge (never opened), **FINISHED** in dim
  grey, or a thin orange progress bar with "N%" beside it.

A book without a title shows its file name without the extension, `_` turned
to spaces. Order: books that have been opened, most recently opened first;
then the rest by title, ignoring case.

Focus is a rounded raised-grey card over the whole row; other rows are
divided by a hairline starting right of the cover.

| Key | |
| --- | --- |
| Up / Down | Previous / next book. Up on the first and Down on the last stay there. The list scrolls to keep the focused row in view. |
| Left / Right | Jump five rows up / down, stopping at the first / last book. |
| OK (on release) | Open the focused book in the reader. With no books, look for books again. Without storage permission, ask for it again. |
| Menu (on release) | Open the action sheet (below). Does nothing without storage permission. |
| Back | Leave the app. |

Empty and waiting states, centred, title only:

- First launch: "No storage access" while Android asks for storage.
- Denied: "No storage access"; denied with "don't ask again": "Storage is
  turned off".
- Scanning with nothing known yet: "Looking for books…".
- No books found: "No books yet", and no count in the header.

On every return to the library it shows the books it knows at once, then
looks again in the background; new books appear, deleted ones disappear,
and focus stays on the same book if it is still there. After reading a book,
focus is on the first row (that book).

Notices replace the count for 5 seconds: "Deleted", "Couldn't delete it",
"Not allowed to change storage", "Not allowed to use the SD card",
"Already converting a book", "Converting…" (stays until the conversion ends),
"Saved <name>.mobi", "Couldn't convert it", "Couldn't save it beside the
book", or "Can't read <file>".

### Library action sheet

A dark grey sheet rising from the bottom with rounded top corners and a
grabber; title: the focused book's title (or "Library" with no books). Rows,
in order, each only when it applies:

- **Mark as Unread**: shown unless the book is NEW. Makes it NEW again (place
  forgotten) and moves it among the unread books, by title.
- **Mark as Finished**: shown for a book opened before and not finished. Shows
  FINISHED; it keeps its place in the order.
- **Convert to MOBI**: EPUBs only.
- **Look for New Books**: always. Rescans.
- **Delete Book**: in red.

Up / Down move (stopping at the ends), Left / Right do nothing, OK acts on the
row and closes the sheet, Menu or Back closes it with nothing done.

**Delete Book** opens a second sheet, titled with the book's title: a grey
header "ERASE IT FROM THE PHONE?", then **Cancel** (focused) and **Delete** in
red. Cancel, Back or Menu keeps the book. Delete erases the file, removes the
book and its place, and the notice says "Deleted".

**Convert to MOBI** writes `<same name>.mobi` beside the EPUB (replacing an
earlier one), off the main thread, one book at a time. The header says
"Converting…" then "Saved <name>.mobi". The list stays usable meanwhile.

### Reader

Fullscreen, no status bar. "Opening…" in dim grey in the middle while the book
loads; it then opens where it was left (the start for a new book; the last
page for a finished one). A book that can't be read shows "This book can't be
opened", with the reason flashed briefly over it.

The page: the text in the chosen font and size, soft white (`#E3E3E5`) on
black, justified unless Justify is off, hyphenated; italic and bold kept;
headings and scene breaks never left at the foot of a page. Bottom right,
small and dim: the percentage through the book (rounded down, 0% to 100%).

| Key | |
| --- | --- |
| Down, Right, 6, 8 (also Page Down, Space) | Next page. On the last page of a section, the next section's first page. Past the very last page: the book is marked finished and the overlay shows "End of book" and 100%. |
| Up, Left, 2, 4 (also Page Up) | Previous page. From a section's first page, the previous section's last page. On the book's first page, nothing happens. |
| OK (on release) | Show or hide the overlay: the chapter's name at the top; at the bottom a progress bar through the book with "N pages left in chapter" ("1 page left in chapter", "Last page in chapter") and the percentage. |
| Menu (on release) | Themes & Settings. |
| 1 / 3 | Text size one step smaller / larger (11 to 26; at a limit nothing happens). Keeps the place. |
| # | Night Shift on / off, with "Night Shift on" / "Night Shift off" flashed for about 1.6 s. |
| * | Contents. |
| Back | Hides the overlay if shown; otherwise saves the place and returns to the library. |

A page turn hides the overlay. Holding a key repeats page turns; it does not
repeat 1, 3, # or *. Keys pressed while the next section is still being laid
out are ignored rather than queued.

### Themes & Settings

A sheet over the bottom two-thirds of the page, titled "Themes & Settings",
focus on the first row. Rows:

- **Contents** (chevron): OK opens Contents.
- **Text Size** (stepper, 11 to 26, default 15): Left / Right, also 4 / 6.
- **Night Shift** (switch, default off): Left, Right or OK flips it.
- **Justify** (switch, default on): Left, Right or OK flips it.
- **Line Spacing**: Compact, Normal (default), Relaxed.
- **Margins**: Narrow, Normal (default), Wide.
- a FONT header, then **Charter** (default), **Literata**, **Baskerville**,
  **Inter**, **Atkinson Hyperlegible**, each in its own face, the chosen one
  ticked in orange. OK chooses.

Steppers stop at their ends. Up / Down (also 2 / 8) move, skipping the FONT
header, and stop at the first and last rows; the sheet scrolls, with a thin
scroll bar at the right. Every change shows on the page behind at once and
keeps the place. Menu or Back closes the sheet. 1, 3, # and * do nothing here.

### Contents

Fills the screen, titled with the book's title (or "Contents"). One row per
contents entry, indented by depth (up to three levels); the entry being read
is orange and focused when it opens. A book without contents has one row,
"Beginning". Up / Down (2 / 8) move one row, Left / Right (4 / 6) a screenful,
stopping at the ends. OK goes to the entry's start and returns to the page.
Back or Menu returns to the page where it was (not to the settings sheet).

## Behaviour

- **Where books are found**: `.epub` and `.txt` files (any case) in `Books` at
  the top of the phone's storage and each SD card, up to 5 folders deep and
  5000 files. Hidden files and folders (starting with `.`) and empty files are
  skipped. Books elsewhere never appear.
- **Permissions**: READ_EXTERNAL_STORAGE asked on first launch. Deleting or
  converting on the phone's storage needs WRITE_EXTERNAL_STORAGE, normally
  granted silently. On the SD card, Android's "Allow Books to access SD
  card?" is asked once, the first time a card book is deleted or converted,
  or one is opened that Books can't read where it lies; after a yes the action
  carries on and the library rescans. A no gives "Not allowed to use the SD
  card".
- **The shelf** (`shelf.tsv` in the app's private files): each book's title,
  author, size, date, place and when it was last opened. The place is an
  offset in the text, independent of font and size. It is written at most
  every 4 seconds while reading and always on leaving the reader or the app,
  so it survives a restart, and survives a battery pull up to those seconds.
  A book replaced by a file of a different size starts afresh as NEW.
- **Settings** (font, size, spacing, margins, justify, Night Shift) are one
  set for every book and survive restarts.
- **Night Shift** warms everything the app draws (page, sheets, library,
  covers) toward yellow, in the reader and the library alike; black stays
  black. Only the status bar and Android's own dialogs escape it.
- **Covers** are cut once into small thumbnails in the cache; a card book that
  couldn't be read yet gets its title and cover once it can be.
- **Screen**: stays on in the reader until 5 minutes after the last key;
  then the phone's normal timeout applies. The library has no such hold.
- **Plain text**: Gutenberg's header and licence footer are left out.
  Paragraphs are split at blank lines (hard-wrapped lines joined), else one
  per line; a short line such as "Chapter 7", "PART TWO" or "XIV." starts a
  chapter, which Contents lists. UTF-8, else Windows-1252. Files over 24 MB
  are refused ("This book can't be opened").
- **Converting**: through a hidden `.part` file renamed into place, so a
  failed conversion leaves no half-written MOBI. The MOBI keeps headings,
  italics, bold, lists, tables, links, the cover and pictures, and a contents
  page the Kindle's Go To menu finds. Leaving the app while converting
  finishes the conversion.
- **Network**: none.

## Look

Books keeps its own look (Apple Books, dark), not the family one in
DESIGN.md: true black page, iOS dark greys (`#1C1C1E` sheets, `#2C2C2E`
focus), one orange accent `#FF9F0A` (focus details, NEW, progress, the current
contents entry, switches on), red `#FF453A` only for Delete. Interface text is
Inter; the cloth covers' initial is Charter Bold. No view ever shows Android's
own focus highlight.

## Open questions

1. The library header shows a book count ("12 books"). DESIGN.md rule 8 says
   no screen carries a counter for its own sake; Books keeps its own look, so
   is the count wanted?
2. Mark as Finished is offered only for a book that has been opened. Should a
   NEW book be markable as finished too?
3. Contents opened from Themes & Settings: Back returns to the page, not to
   the sheet. Is that intended?
4. The delete sheet says "Erase it from the phone?" even for a book on the
   SD card. Keep the wording?
