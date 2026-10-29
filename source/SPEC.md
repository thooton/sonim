# Source

## Purpose

Source gets songs, albums and e-books onto the Sonim XP3 without opening an
agent session. Music comes from Soulseek through the command center's music
routes (thooton/delta), and books come from the book source, using the
account kept on the command center. Everything is saved onto the SD card:
music into `Music/`, where the music player finds it, and books into
`Books/`, where the Books app finds them. The user drives it one-handed with
keys on a 240x320 screen, never by touch.

It must stay a small, quick fetcher: search, look at a result, download it,
play or read it. It is honest about where things come from. The engine's log,
Soulseek, who shares each result and the folders it sits in are always shown,
never hidden or reworded. It must never become a music player, a library
manager, a book reader or a general file manager, and it must never pick a
music result for the user.

## Not wanted

- Downloading the best match without asking. The user always picks from the
  results (1.1.0).
- Downloading straight from the results list. OK on a result opens its page,
  and it is downloaded only from there (1.4.0).
- A soft-key bar saying what Menu, OK and Back do. It was removed (it only
  repeated the keys).
- Instructional text: no "OK does X" hints, no "Press Menu to...", no example
  input. The search box's format hints ("Artist - Title", "Artist - Album",
  "Title, author or ISBN") stay, because they are format hints.
- White-labelling the routes or the log. Errors may name Soulseek.
- Saving to the phone's own storage. Downloads go to the SD card only, and fail
  cleanly when no card is in.
- A "Music folder" card on the home screen.
- Smooth scrolling that runs from the top on every key press. The list jumps
  just far enough to show the picked row.
- Logging in to the book source when the stored session would do (the source
  allows about ten logins an hour).
- Re-logging the music account casually. Reconnect is confirmed first.

## Screens

### Main screen

From the top: three pill tabs (**Song**, **Album**, **Book**, each with its
icon, the current one filled), a search pill, a header line (status text, a
spinner while searching, and an option chip on results), then the content area.
The tab last used is restored on launch. The box's hint is the current tab's
format hint. A clear mark shows in the pill while it holds text.

While no row is picked, the keys type into the box, and the pill's search icon
rings in accent. The content area is one of:

- **Home** (before a search of this tab): a "Downloading" section (or
  "Downloading, N queued") with at most 2 active download rows, a "Recent"
  section with up to 4 recent searches (icon of their tab, the text, an
  up-left arrow), and the compact storage card ("X free of Y", or "No SD card
  in the phone", and a bar). Sections with nothing in them are absent.
- **Log** (music search running, or results hidden): the engine's log lines,
  monospace, kept scrolled to the bottom as lines arrive.
- **Loading** (book search running): four grey skeleton rows.
- **Results**: rows, with the first one picked when new results arrive. The
  header reads "Songs · N found" / "Albums · N found", and for books
  "Books · page P of N" (or "Books · N found" when there is one page). The chip
  reads "Best match" or "Fastest" (music), or "EPUB first" or "Every format"
  (books).
  - Music row: drawn cover, name, the folders it sits in (ellipsized from the
    start so the deepest folders stay visible), then a format tag with bitrate
    and size, and the sharer (with "(queued)" when the sharer has no free slot).
  - Book row: drawn 2:3 cover, title ("Untitled" when blank), extension tag,
    author · size.
  - A row ends in a state button only once it has been asked for: a ring with
    the percentage while downloading, a clock while queued, a tick when saved,
    a retry arrow when failed. A result saved in an earlier session shows the
    tick if its first file still exists.
- **Empty**: an icon, "No songs found" / "No albums found" / "No books found"
  (or "The search failed" with the reason), then picked buttons:
  "Search albums" / "Search songs" (music only, runs the same words in the
  other tab), and "Search again" (or "Try again" after a failure). Music
  also shows the log's last 6 lines.

Keys:

- **OK**: when no row is picked, searches for the box's text. Empty text does
  nothing, except bring back hidden music results. If a search of this tab is
  running, OK cancels it. On a result row, OK opens the result's page, or plays
  or reads it when it is already saved. On a home row it opens a download's page,
  re-runs a recent search in its tab, or opens Downloads (storage card). On an
  empty state button it does what the button says.
- **Up/Down**: move between the box (no row picked) and the rows. Up from the
  first row returns to the box. Down on the last row stays there. On the Log
  with no row, they scroll the log.
- **Left/Right**: while a row is picked, the box is empty, or a search runs,
  they switch tab Song → Album → Book → Song (wrapping). Switching tabs leaves
  no row picked. On a picked book result they turn the page instead. At the
  ends the status says "This is the first page." / "This is the last page.".
  While typing in a non-empty box they move the cursor.
- **Typing** while a row is picked returns to the box with that key.
- **Back**: cancels a running search; else, with a row picked, returns to the
  box; else, with music results hidden behind the log, shows them again;
  else leaves the app.
- **Menu**: opens the Options sheet.

Searching:

- With no command center login, OK opens the Command center dialog instead.
- A music search starting with "-" is refused with the status
  `A name can't start with "-".`.
- The box is disabled while a search runs. Each search goes first in Recent;
  the same words in the same tab move up rather than repeat.
- Music: the status shows "Searching…". The log shows "» Search for song:
  ..." and the engine's lines, and ends "✓ N results" or "Nothing found".
  Results replace the log when there are any.
- If a download is running, the search waits and the status says "Waiting for
  a download…".
- Books: searches EPUBs only, unless Formats is "Every format". If page 1 has
  no EPUBs, it searches every format, and the status starts "No EPUBs, so every
  format.".
- With no login, the status line reads "No command center".

Options sheet (Menu): rows of icon, name and current value, at the foot over a
scrim. Up/Down move (wrapping), OK does the row and closes the sheet, Back or
Menu closes it. Rows appear only when they apply:

- On a picked result: **Details** (its page), **Cancel download** (if queued
  or running).
- **Downloads** ("N to go" while some are active).
- Book tab: **Next page** / **Previous page** (with the page number),
  **Formats** (toggles EPUB first / Every format), **Clear results**.
- Music tabs: **Sort by** (Best match / Fastest: free slots first, then
  speed), **Show results** or **Show log**, **Clear results**, **Clear log**
  (not while searching), and **Reconnect music account**. Reconnect asks
  "Reconnect music account?" (Reconnect / Cancel), then the status shows
  "Reconnecting…", then "Reconnected" or "Couldn't reconnect: ...".
- **Clear recent searches** (when there are any).
- **Theme** (Light / Dark): switches at once and is remembered.
- **Command center** (its host): a dialog with the address and the token
  (dots when one is saved, and a blank token keeps it). Save sets the status
  "Saved: host", or the error, such as "Address and token needed".

### Result page (Song, Album or Book)

The title bar says Song, Album or Book. The page scrolls; Up/Down scroll it by
a step.

- Song: large cover, name, folders in full, chips (format · bitrate, length,
  size), and the sharer line "user · 1.2 MB/s" (plus "· queued").
- Album: cover beside the folder name and the folders above it, a format tag
  with the file count, bitrates, length and size, the sharer, and a numbered
  track list with lengths. Each track gets a tick as it arrives, or a clock
  while the album is queued or running.
- Book: cover beside title, author, year · language. If the results page holds
  the same title and author in other formats, a "Format" row of chips (format
  and size) with the current one filled. Left/Right move between them (no
  wrap), and the rest of the page follows the chosen format.
- A status card (absent before it is asked for): "Queued" (with "N downloads
  ahead"); running, with its phase, a percentage, the wavy bar, and "5.4 of 8.4
  MB · about 7 sec left"; saved (green), with "Saved to Music" / "Already in
  Books" / "Saved <date>" and the path from the card's top; failed (red),
  with "Failed", the reason, and a flat bar.
- One filled button saying what OK does: **Download**, **Try again**,
  **Play** or **Read**. There is no button while queued or running.
- The download's log, last 60 lines, monospace.

OK does what the button says, and nothing while queued or running. The first
download asks for storage access, then "Allow Source to access SD card?".
A refusal shows "No storage access" or "Source can't save to the SD card unless
it is allowed". Menu: **Cancel download** (queued or running), **Forget it**
(failed), **Get it again** (saved), **Downloads**. Back returns to where it was
opened from.

### Downloads

The title bar says "Downloads". From the top: the full storage card (free of
total, a bar split into Music, Books and the rest, and a "● Music X ● Books Y"
legend once measured), then the sections "Downloading" (running first, then
queued), "Failed" (this session, newest first) and "Saved" (newest first, up to
100, with size and date, and the tick; one whose first file is gone has no
tick and reads "No longer on the phone" before its date). With none of these it shows the heading "Nothing
downloaded yet".

The first download row is picked on opening. Up/Down move between download
rows only. Up from the first one scrolls the storage card back into view, and
Down on the last row stays there. The pick stays on the same download as the
lists change. Left/Right do nothing.

- OK: on a running or queued row, opens its page. On a failed one, tries it
  again. On a saved one, plays or reads it. If its files are gone it shows "It's
  no longer on the phone".
- Menu: **Details**; **Cancel download** or **Forget it** (failed);
  **Cancel all** when more than one is active.
- Back returns to the previous screen.

## Behaviour

- **One network thing at a time.** Downloads queue and run one at a time, in
  the order they were asked for. A search runs ahead of the queue but waits for
  the running download (two runs would log the Soulseek account in twice).
  These keep going when a screen closes or the phone is shut (partial wake lock,
  at most 45 minutes per run).
- **Music download.** `POST /music` with the result's `slsk://` link streams
  the engine's log ("Downloading from <user>"). Then the job's tar is fetched
  straight into `SD card/Music/` ("Saving to the SD card"), and then the job is
  deleted on the command center. An album lands in a folder of its own. A file
  already there with the same size is kept ("N already there"), and a
  different file with the same name gets " (2)". Files are written as `.part`
  files and renamed when done. Saved files are handed to the media scanner.
- **Book download.** It goes into `SD card/Books/` as `Author - Title.ext`
  ("Title.ext" with no author). The same name with the same size is kept ("Already
  in Books"). A web page instead of a book fails with "Got a web page instead of
  the book: the account's daily download limit may be used up.".
- **Book account.** It is fetched from the command center (`GET /books-login`)
  only to log in. The session is kept and reused, with one fresh login when a
  call fails on it. With no account, the error names `PUT /books-login`.
- **Cancel.** A cancelled download (running or queued) disappears as if it
  had never been asked for, and its part file is deleted.
- **Play / Read.** Play opens the first audio file (by name) in whatever plays
  audio. Read opens the Books app if it is installed, or else any app for the
  file's type. If nothing can open it, it shows "No app here opens it".
- **No SD card.** Downloads fail with "No SD card in the phone.". The storage
  cards say "No SD card in the phone".
- **Errors** are shown in the words of the engine or the command center, for
  example "The command center refused the token." (HTTP 401), or "Lost the
  connection to the command center before the ... finished.". Network
  requests to the command center time out after 60 s.
- **Survives a restart:** the tab, recent searches (4), sort, formats, theme,
  login, the book session, and saved-download history (100, in the app's
  files). **Lost on restart:** the search log, results, and queued, running
  and failed downloads.
- **Login.** It is baked in at build time and adopted once per build that
  carries a new one. Otherwise the saved one (from Options) is kept.
- The storage card measures the Music and Books folders at most once a minute,
  off the main thread.
- Logs keep their last 300 lines.

## Look

Tonal (DESIGN.md, "Source: Tonal"): Material 3 tones from `#6750A4`, a light
status bar, DM Sans, pill tabs, drawn covers (six tones, square for a song,
square with a record for an album, 2:3 with a spine for a book, greyed when
failed), the wavy bar drifting while it runs, and straight and red when
failed. The picked row is a filled `accent_container` block. The dark theme
comes from Options only.

## Open questions

- README.md still describes the removed soft-key bar and says OK on a
  result downloads it. Since 1.4.0 OK opens the result's page. Should the
  README be brought up to date?
- Back on the main screen cancels a running search even when it belongs to
  another tab, while OK cancels only this tab's search. Is that intended?
- Saved downloads whose files were deleted stay in Downloads, and OK on them
  says "It's no longer on the phone". Should they drop out of the list
  instead?
