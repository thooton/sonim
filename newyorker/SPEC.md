# Newyorker

## Purpose

A reader for The New Yorker's articles on the Sonim XP3 (240x320, d-pad,
soft keys, T9 keypad), driven one-handed with keys, never touch. It is laid
out after the New Yorker app: three tabs (Today, the Magazine, Saved), white
pages, the site's red rubrics, Caslon text with drop caps.

It reads the public HTML of www.newyorker.com, which carries each article
whole, so it needs no account, no API key and no login. It must stay a
small, quick, dependency-free reader of articles: open, read, save for later,
keep as a PDF. It must never become a podcast player, puzzle or game app,
video or cartoon browser, a subscription or account client, or anything that
needs a login.

## Not wanted

- Audio, podcasts, puzzles and games, crosswords, video and cartoon
  galleries: left out of the lists on purpose (`Site.NOT_ARTICLES`). Do not
  add them back, even incidentally.
- Newsletters, events, account, subscribe, search, contributors, tag and
  store pages: not shown as stories.
- A half-drawn article: the headline and picture the list knew are not shown
  first and then replaced. An article shows only a spinner until it is in.
- Animated or large scroll jumps: Down/Up move a small fixed step, at once.
- Free stand-in fonts: the site's own faces are used (see Look).
- Touch as the way to drive it.

## Screens

### Lists (Today, Magazine, Saved)

A masthead bar at the top, the list (or a state) in the middle, the tab bar
(Today, Magazine, Saved) at the foot with the open tab in ink, bold, under a
short ink rule; the others grey.

- Today: masthead "The New Yorker". The front page's stories: each
  section's first story, if it has a picture, is a lead (picture across the
  screen, taller for the page's top story); each later section is a heavy
  ink rule and the section's name, then rows.
- Magazine: masthead "The Magazine". The issue's date (e.g. "October 26,
  2026") centred at the top, then the issue's own sections as titles with
  rows under them; no leads.
- Saved: masthead "Saved". Saved articles as rows, newest saved first.
- A row: rubric (red), headline, up to two lines of italic dek ending in
  "…", byline, and a square picture on the right when the story has one.
  Hairlines between rows, none over the first row under a title. A row whose
  picture fails to load shows no picture (no empty box).
- The selected row is washed cream.

Keys:

- Left / Right: previous / next tab, wrapping (Left on Today goes to Saved,
  Right on Saved goes to Today). They change tab from any state, including
  a spinner or an error with Try again focused.
- Down / Up: when the next story is wholly on screen it is selected at once.
  Otherwise the list moves a fixed 36dp per press and the next story is
  selected once it is well onto the screen. Section titles and the issue
  date cannot be selected but scroll into view the same way.
- Up on the first story scrolls up to show the date or section title above
  it, then stays. Down on the last story scrolls to the list's end, then
  stays.
- OK: opens the selected story's article.
- Menu or left soft key (on release): a popup with Open (when a story is
  selected), Remove from Saved (Saved tab only), Refresh (Today and
  Magazine only). On Saved with nothing saved, Menu does nothing.
- Remove from Saved removes it at once; the list redraws with the first
  story selected. Removing the last shows the empty state.
- Back: leaves the app.

Place: each tab remembers the story last selected, across tab changes,
refreshes and returning from an article. The first time a tab is shown it
starts at the top with the date/title in view and the first story selected.
If the remembered story is gone after a refresh, the first story is
selected.

States, centred in the middle area:

- Loading with nothing to show: a spinner only.
- Today failed with nothing cached: "Couldn’t load today’s stories", the
  error in small grey text, and a focused TRY AGAIN button (OK presses it).
  The Magazine says "Couldn’t load the issue".
- A page that parsed to no stories is an error: "The page had no stories in
  it."
- Saved empty: "Nothing saved yet", no button.
- A refresh that fails while a list is on screen keeps the list and toasts
  "Couldn’t refresh: <error>".

### Article

Opened from any list. While the page is fetched the screen holds only a
centred spinner. Then, whole, top to bottom: rubric (red, centred), headline
(large, centred), dek (italic, centred), byline, date (grey), the lead
picture with its caption/credit, then the body: paragraphs, section heads,
block quotes (indented), list items (hanging indent), pictures with
captions, and breaks. The first paragraph and each section's opening
paragraph start on a three-line drop cap (an opening quotation mark goes
with the letter; paragraphs under 160 characters get none). The last
paragraph ends with " ♦". A picture that fails to load is dropped with its
caption, leaving no gap.

A saved article opens from its saved copy at once, without the network.

Keys (taken before the scroll view, so nothing else moves the page):

- Down / Up: two lines on / back, at once, not animated. Stays put at the
  top and at the end.
- Right / Left: a screen on / back, keeping two lines of the last screen in
  view.
- \*: smaller text; #: larger. Five sizes; the third (15.5sp) is the
  default. At the smallest or largest the key does nothing. The size is kept
  across articles and restarts, and the same part of the article stays in
  view.
- Menu or left soft key (on release): popup, in this order, showing only
  what applies:
  - Save, or Remove from Saved if saved (once the article is in): toasts
    "Saved" / "Removed from Saved". The article stays open.
  - Larger text (not at the largest), Smaller text (not at the smallest).
  - Update saved copy (saved articles only): spinner, fetches afresh,
    replaces the saved copy and toasts "Saved copy updated".
  - Save as PDF (once the article is in).
  - Back to top: scrolls to the top.
- Back: returns to the list, at the same story. If Saved is open there, it
  shows what was saved or removed while reading.

Failure: the spinner becomes the error in grey ("This story is no longer on
the site." for a 404, "The New Yorker answered <code>." for other statuses,
"This page has no article to read here." for a page with no body) and a
focused TRY AGAIN button. OK on it retries (spinner again). Up leaves the
button for the page; Back returns to the list.

### Save as PDF

From the article menu. The first time it asks for storage permission; if
refused, toasts "Saving a PDF needs access to storage" and nothing is saved;
if granted, the export continues. Toasts "Making the PDF…", then "Saved to
Download/New Yorker/<headline>.pdf", or "Couldn’t make the PDF: <error>".
A second request while one is being made is ignored.

The PDF is US Letter: the header over the lead picture, justified,
hyphenated Caslon with drop caps, indented paragraphs, pictures fetched
again at print size with captions, page numbers. The name is the headline
without characters file systems refuse, cut to 80 characters ("Article" if
empty); a clash becomes "<name> 2.pdf", "<name> 3.pdf"… It goes into the
phone's own storage, in Download/New Yorker, and is media-scanned so other
apps see it.

## Behaviour

- Data, in the app's private files:
  - `today.json`, `magazine.json`: the last good copy of each feed, written
    only when a fetch has stories.
  - `saved/saved.json`: the saved list, newest first; each saved article
    whole in `saved/article-<hash>.json`. Saving one already saved moves it
    to the top. A list or article file that no longer reads is treated as
    empty or missing, never a crash.
  - `settings` preferences: the text size.
- On launch the cached Today and Magazine show at once; Today is fetched
  afresh at once, the Magazine when its tab is first opened. A fresh copy
  replaces the list in place, keeping the selected story.
- A tab not fetched in the last 15 minutes (in this run of the app) is
  fetched again when Left/Right opens it. Refresh fetches at once.
- Offline: cached lists and saved articles work; an unsaved article shows
  the error and Try again. Saved articles' pictures still need the network.
- Network: only www.newyorker.com (pages, sent with a phone browser's
  User-Agent; redirects followed by hand, at most four hops, never off the
  site) and media.newyorker.com (pictures, asked for at the size shown).
  HTTPS only. Pages time out after 60s, pictures after 30s; a picture over
  4MB is refused.
- Pictures are kept in memory only (about 6MB) while the app runs.
- Permissions: Internet; storage, asked only for Save as PDF.
- Portrait only.

## Look

As the New Yorker section of DESIGN.md and newyorker/AGENTS.md say: white,
ink `#231F20`, hairlines `#DADADA`, red `#DB3334` for rubrics only, cream
selection `#ECE6D9`; Caslon for text and deks (deks italic), Irvin for the
masthead, section titles, rubrics and the article headline, Graphik for the
date, captions and tabs, Neutraface SemiBold for bylines. TRY AGAIN is a
spaced capital button whose text turns white on ink when focused.

## Open questions

- List headlines, leads included, are set in Caslon, but the 1.0.1 history
  and a code comment say the lead's headline is Irvin. Which is meant?
- If "Update saved copy" fails, the screen shows the error, not the saved
  copy (which is still kept). Should it fall back to the saved copy with a
  toast instead?
- Coming back to the app after a long time does not refresh the open tab;
  only switching tabs (or Refresh) does. Is that intended?
