# AGENTS.md

## Purpose and scope

A dependency-free reader for The New Yorker's articles on the Sonim XP3,
styled after the New Yorker Android app. A sibling of `../books` and the
rest, under the same constraints: 240x320 mdpi, d-pad driven, no AndroidX or
third-party libraries, `:core` plain Java `--release 8` running on API 27.

Articles only. Audio, puzzles and games, video and cartoon galleries are
left out on purpose (`Site.NOT_ARTICLES`); do not add them back incidentally.

## How it reads the site

Everything comes from the public HTML of www.newyorker.com, which renders
every article whole for clients that run no script:

- **Today** is `https://www.newyorker.com/` and **Magazine** is
  `https://www.newyorker.com/magazine` (the current issue). `Feed.parse`
  walks the page in order: an `h2` classed `smart-container__header__hed`
  (front page) or `SectionTitleHed-*` (issue) starts a section, and every
  element classed exactly `smart-item` or `summary-item` after it is a story,
  read from its `*__hed-link`, `*__hed`, `*__dek`, `rubric__name` and
  `byline__name` parts. Duplicates, non-articles and empty sections are
  dropped.
- **An article** (`Article.parse`) takes its header from data-testid
  attributes (`ContentHeaderHed`, `ContentHeaderRubric`, `BylinesWrapper`,
  `ContentHeaderPublishDate`, `ContentHeaderLeadAsset`) and its body from
  every `body__inner-container`, in order. `Blocks` turns that into
  paragraphs (with `has-dropcap` marking a section's opening), headings,
  quotes, list items, pictures and breaks, dropping promotions by class
  prefix. Note that the site classes every body paragraph `paywall`: that
  is not a sign to skip it.
- **Pictures** come from media.newyorker.com, which resizes on request:
  `Site.picture` rewrites the `w_1600` in an address to the width wanted.

The class names with hashed suffixes (`SummaryItemHed-chBZ...`) change with
the site's builds; match the stable BEM names and data-testids, by prefix
where a hashed name is all there is. If the site changes shape, run the
parsers against a freshly fetched page (structure only: counts of sections,
stories and blocks) before guessing.

`Net` sends a phone browser's User-Agent and follows redirects by hand, only
within www.newyorker.com (the shared `Http` never follows them).

## Layout

- `android/core`: `html/` (`Html`, a forgiving parser that drops scripts,
  styles, SVG and the head; `Node`), `news/` (`Site`, `Story`, `Feed`,
  `Article`, `Block`, `Blocks`, `Saved`). Tests in `src/test` use pages
  written for the tests in the site's markup, never copied from it; run them
  with `./gradlew :newyorker:core:selfTest`.
- `android/app`: `MainActivity` (the three tabs, the cache of each feed in
  `files/today.json` and `files/magazine.json`, refreshing a tab older than
  15 minutes when it is opened), `StoryAdapter` (section titles, the lead
  story and story rows), `ArticleActivity` (the article, its keys, text size
  and saving), `PdfExport` (Save as PDF: PdfDocument and StaticLayout on
  US Letter, into `Download/New Yorker`, pictures fetched again at print
  size; it shares `ArticleActivity.styled` and `DropCap` with the screen),
  `DropCap`, `Pictures` (background loading, an in-memory LRU),
  `Net`, `Look` (fonts and text helpers).

## Look

See the New Yorker section of `../DESIGN.md`. The fonts are the site's own
web faces, served by www.newyorker.com and converted to TTF: TNY Adobe
Caslon Pro for text, TNY Irvin Pro Heading for the masthead, section titles
and lead headlines, and Graphik for rubrics, bylines, dates and tabs. They
are proprietary (Condé Nast) and there is no licence file: the note in
`assets/fonts` says what they are and that they are not for redistribution.
The launcher icon is the New Yorker app's own mark from the stores: Eustace
Tilly, monocle to his eye and phone in hand, in ink on the site's red, kept
as PNGs (`res/drawable-*/ic_launcher.png`).

## Keys

- Lists: Up/Down move, OK opens, Left/Right change tab, Menu (or the left
  softkey, `SOFT_LEFT`) opens the popup menu on release.
- Lists: Up/Down are taken over (`MainActivity.move`): a fixed 36dp per
  press unless the next story is already wholly visible, the
  selection following once a story is well onto the screen.
- Article: Down/Up move two lines; Right/Left turn a screen, keeping two lines of the
  last; * and # change the text size (kept in the `settings` preferences);
  Menu opens its menu. Both screens take these in `dispatchKeyEvent`, before
  the ListView or ScrollView can.
