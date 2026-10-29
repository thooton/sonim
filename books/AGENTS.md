# AGENTS.md

A dependency-free e-book reader for the Sonim XP3, styled after Apple Books in
dark mode. A sibling of `../list`, `../music` and the rest, under the same
constraints: 240x320 mdpi, d-pad driven, no AndroidX or third-party libraries,
`:core` plain Java `--release 8` running on API 27. No network: the manifest
asks only for READ_EXTERNAL_STORAGE, and WRITE_EXTERNAL_STORAGE for deleting
a book and saving one converted to MOBI on the phone's own storage. On the SD
card those take Android's grant for the card instead (`CardFiles`).

## Layout

- `android/core`:
  - `book/`: `Markup` (a forgiving XML/XHTML tokenizer), `Html` (XHTML to
    paragraphs and italic/bold runs), `Chapter` (text as it is read in:
    white space collapsed, empty paragraphs turned into gaps), `Epub`
    (container, OPF, spine, nav or NCX contents, cover), `PlainText`
    (Gutenberg header/footer, paragraph and heading detection, UTF-8 else
    Windows-1252), and `Book` (chapters cut into `Section`s and the contents
    resolved to offsets). `Mobi` writes an EPUB as a Mobipocket 6 file for a
    Kindle (spine joined into one HTML, markup cut down, links as `filepos`,
    a contents page and guide, images as records, EXTH metadata and cover),
    with its text records packed by `PalmDoc`. `MobiTests` reads the file
    back as a reader would; check header changes against KindleUnpack too
    (`pip install mobi`, then `mobi.extract`).
  - `shelf/Shelf`: scans the folders it is given for books, caches title and
    author, and keeps reading positions in `shelf.tsv` in the app's private
    files. The library gives it only `Books` at the top of internal storage
    and of each SD card; never widen that to a search of the filesystem.
  - Tests in `src/test` run via `:core:selfTest` with the shared `Check`
    harness (`../common/core`).
- `android/app`: `LibraryActivity` + `LibraryView` (the library, drawn),
  `Covers` (thumbnails cached in the cache dir), `ReaderActivity` (loading,
  layouts, keys, the settings sheet and contents), `Pages` (one section as a
  StaticLayout cut into pages), `PageView` (draws a page and the OK overlay),
  `ListPanel` (the drawn sheet and contents list), `Fonts`, `Settings`,
  `KindleImages` (images readied for MOBI: small JPEGs and GIFs kept, the
  rest redrawn as JPEG under 127 KB), `CardFiles` (books on the SD card
  through `CardGrant`, below).

## The SD card

Android 8.1 refuses plain file writes on a removable card outside an app's
own folder, and can refuse plain reads ("Permission denied") of files another
app put there through Android's documents provider (a file manager's move,
Source's downloads), while still listing them. So:

- `CardFiles` is the shelf's `Shelf.Reader`: a book that opens where it lies
  is read there; one that doesn't is copied through the card's grant into
  `cache/card` (the newest few kept) and read from the copy. A book that
  can't be read at all is a `FileNotFoundException` ("Can't read X"), never
  "Not an EPUB"; the shelf leaves its title unset and Covers leave no marker,
  so both try again once it can be read.
- The grant (`CardGrant` in `common/ui`, shared with Source) is asked for
  once, with `StorageVolume.createAccessIntent(null)`: when a card book is
  opened that can't be read, or deleted or converted. Deleting and the
  MOBI's part file and rename go through `DocumentsContract` there.

## How reading works

- A place in a book is one number: an offset through the sections' text. It
  is independent of font and size, so it is what the shelf saves.
- Chapters are cut into sections of roughly 8-22k characters, at a pause in
  the text where possible, because StaticLayout is the slow part on the phone.
  A section boundary forces a page break.
- Each section is one justified, hyphenated StaticLayout; a page is a run of
  whole lines. Headings and scene breaks are never left at a page's foot.
  Layouts are built on a worker thread, and the neighbours of the current
  section are prefetched.
- Paragraph spacing is a `LineHeightSpan` that sets each line's metrics
  outright. Do not make it add to the metrics it is handed: StaticLayout can
  pass a later line what an earlier call left behind, and the space then
  lands on every line.
- Night Shift is a colour-matrix filter on each activity's root view
  (`NightShift`), so everything the app draws is warmed, covers included. It
  never relayouts (`ReaderActivity.setNightShift` recolours only). Because system
  windows escape the filter, the app shows no toasts (`PageView.flash`) and
  no platform popups.

## UI rules

- No view takes focus. Both activities route every key themselves, OK and
  Menu acting on their *release* (tracked with `startTracking`), so a
  release never lands on something that has just opened.
- The library's Menu is an action sheet drawn with `ListPanel`, not the
  platform's context menu as in `../list`: the popup is its own window, which
  Night Shift's filter would miss. Delete asks first, on a second sheet with
  Cancel focused. What an action did (Deleted, Converting…, Saved X.mobi) is
  a notice in the library's header (`LibraryView.setNotice`), not a toast.
- Converting writes `<name>.mobi` beside the EPUB, through a hidden `.part`
  file renamed into place, on its own worker, one book at a time. The
  library does not list MOBIs.

## Build and validation

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :books:core:selfTest :books:app:assembleRelease
```

There are no UI automation tests. Rendering, keys and paging were checked on
the 240x320 `xp3` AVD (API 35, which adds a nav bar and an "older Android"
warning the phone does not have); check key and paging changes on a handset.

## Fonts

`assets/fonts` holds four static faces per font plus each licence. They are
stored uncompressed in the APK (`noCompress "ttf"`). To change a variable
font's cut, instance it with fontTools' `varLib.instancer` (Literata at opsz
12, Inter at opsz 14; bold is wght 700, Inter's 650).
