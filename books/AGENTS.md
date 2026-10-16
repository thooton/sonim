# AGENTS.md

A dependency-free e-book reader for the Sonim XP3, styled after Apple Books in
dark mode. A sibling of `../list`, `../music` and the rest, under the same
constraints: 240x320 mdpi, d-pad driven, no AndroidX or third-party libraries,
`:core` plain Java `--release 8` running on API 27. No network: the manifest
asks only for READ_EXTERNAL_STORAGE.

## Layout

- `android/core`:
  - `book/`: `Markup` (a forgiving XML/XHTML tokenizer), `Html` (XHTML to
    paragraphs and italic/bold runs), `Chapter` (text as it is read in:
    white space collapsed, empty paragraphs turned into gaps), `Epub`
    (container, OPF, spine, nav or NCX contents, cover), `PlainText`
    (Gutenberg header/footer, paragraph and heading detection, UTF-8 else
    Windows-1252), and `Book` (chapters cut into `Section`s and the contents
    resolved to offsets).
  - `shelf/Shelf`: scans storage for books, caches title and author, and keeps
    reading positions in `shelf.tsv` in the app's private files.
  - Tests in `src/test` run via `:core:selfTest` with the shared `Check`
    harness (`../common/core`).
- `android/app`: `LibraryActivity` + `LibraryView` (the library, drawn),
  `Covers` (thumbnails cached in the cache dir), `ReaderActivity` (loading,
  layouts, keys, the settings sheet and contents), `Pages` (one section as a
  StaticLayout cut into pages), `PageView` (draws a page and the OK overlay),
  `ListPanel` (the drawn sheet and contents list), `Fonts`, `Settings`.

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
- Night Shift only recolours the paint; nothing that only changes colour may
  trigger a relayout (`Settings.layoutKey` leaves it out).

## UI rules

- No view takes focus. Both activities route every key themselves, OK and
  Menu acting on their *release* (tracked with `startTracking`), so a
  release never lands on something that has just opened.
- The library's Menu is the platform's context menu, opened with
  `showContextMenu(x, y)` beside the chosen row, as in `../list`.

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
