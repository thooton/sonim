# Tablet

## Purpose

Tablet is the phone's window onto the user's e-ink tablet: browse the files on
it, read a page up close, upload a PDF or ePub to it, and send a file to
**Transform**, a job on the command center in which an agent reads the
instruction handwritten at the top of page 1, makes what it asks for (a PDF
back on the tablet, and optionally Word, Markdown, Text, Email, Flashcards or
page images) and the result can be downloaded onto the SD card, for handing
in on Canvas, mailing from Mail or importing into Flashcards.

It runs on the Sonim XP3 (240x320, d-pad, two soft keys, T9 keypad), driven
one-handed with keys, never touch. It talks only to the Delta command center,
which reads and writes what the tablet last synced.

It must stay small and quick: one activity, everything drawn in code, no
libraries, keys with fixed jobs everywhere. It is a companion, not a second
tablet: no drawing or writing on pages, no reader for long books, no editor.
It must never name the tablet's maker on screen, never explain itself in
sentences, and never save anything onto the phone's own storage.

## Not wanted

- Pausing a job, or a queue of jobs (the design proposal had both; dropped).
  Any number of jobs run at once.
- Alerts or notifications while the app is closed.
- The file's actions listed under the shelf all the time (moved into the OK
  menu to give the covers room), and a job opening into a card with buttons
  (a job is one line; OK opens its menu).
- A soft-key bar or key labels for the soft keys.
- Downloads into the phone's storage or the Download folder: results go onto
  the SD card only (Documents, page images into DCIM/Camera).
- Sentences on sheets or in toasts: facts as label and value rows, toasts of
  a few words, nothing saying where to find something or what happens next.
- A filled box around a selected row; the yellow used for anything but
  "selected".
- The proposal's 9px sizes: everything is 1.25 times the proposal.
- The PDF being forced: it is ticked by default but optional.

## Screens

Keys everywhere: **Menu** is the left soft key, **Back** the right soft key.
Holding Back does not repeat. A toast (ink pill at the foot) shows for at
least 2.6 seconds.

### Library (the shelf) — opens here every time the app comes to the front

- Header: "Library" with a tablet glyph, and on the right how fresh the
  listing is: "Not signed in", "Loading…" (first read only), "Offline" (the
  last read failed), or "Updated just now" / "Updated 5m ago".
- The shelf: page 1 of each file as its cover, newest edit first. The
  selected cover is in the middle, outlined in ink; up to two either side,
  smaller, greyed, fading to the edges. Under them, ‹ dots › with the
  selected dot a dash; past twelve files, "3 of 40" instead of dots.
- Under the shelf: the file's name (Literata, one line, cut with …) under the
  highlighter, and "Notebook · 12 pages · edited 2h ago" (type, pages when
  known, edit time when known).
- Each time the app comes to the front the newest file (far left) is
  selected and the listing is read again. A new listing keeps the selected
  file if it is still there.
- Left / Right: the previous / next file; covers slide, not jump. Left on the
  first and Right on the last stay put.
- OK: the file's menu (below). Back: leaves the app. Up/Down: nothing.
- No files: the caption mid-screen says "Not signed in", "Reading the
  tablet…", "Couldn't read the tablet" with the command center's reason
  under it, or "No files". OK does nothing then.
- Offline, the last listing (and covers already seen) still shows.

### A file's menu (sheet)

Rises from the bottom over the dimmed page: "NOTEBOOK"/"PDF"/"EPUB" label,
the name, then Open, Transform, Download PDF, Rename, Move to folder…,
Details, Delete. Opens on Open (so OK, OK opens the file). Up/Down move,
stopping at the first and last; Back closes it; Menu closes it and opens the
drawer. On every sheet, the picked choice is under the highlighter.

- **Open**: the preview of page 1. Back from it returns to the shelf.
- **Transform**: the Formats sheet. If a job is already running on this
  file, instead a sheet with Status and Progress rows and OK.
- **Download PDF**: saves the whole file as the tablet shows it (with
  writing) as `<name>.pdf` into SD card/Documents (see Downloads). An ePub
  with nothing written on it has no PDF; the command center's words say so.
- **Rename**: a field holding the name, all selected, keypad typing into
  it; Save, Cancel under it (Save picked). OK on Save with a new non-empty
  name renames and toasts "Renamed"; empty or unchanged closes quietly. OK on
  Cancel or Back closes.
- **Move to folder…**: every folder on the tablet as a path ("My files" at
  the top, then the rest A–Z; long paths cut from the start), the file's
  own marked "Current" and picked. OK on another moves it, "Moved to
  School / Math"; OK on Current just closes.
- **Details**: Type, Pages (— when unknown), Folder, Size ("1.9 MB",
  "640 KB"), Edited ("10:42" today, "yesterday", "Oct 12"); Done closes.
- **Delete**: Cancel, Delete, opening on **Cancel**. OK on Delete moves the
  file to the tablet's trash and toasts "Deleted <name>"; it leaves the shelf.

After a menu choice opens another sheet, Back from that goes straight to the
shelf, not back to the menu. If a new listing no longer has the file, its
sheet closes.

### Formats (Transform)

"TRANSFORM", the name, then a box per format: PDF (note "To tablet",
ticked), Word (.docx), Markdown (.md), Text (.txt), Email (.eml),
Flashcards (.fkv), Images (.jpg), all but PDF unticked every time; then
Start, picked. OK on a box ticks or unticks it. With nothing ticked Start is
greyed and OK on it does nothing. OK on Start toasts "Starting…", then
"Transform started", and the job appears in Transforms. Only a job with PDF
ticked puts anything on the tablet.

### Drawer (Menu, from Library, Transforms or Upload)

Slides over the left 168dp with a dimmed scrim. Top: "Tablet" and the same
freshness line as the Library header. Then Library ("12 files", "1 file"),
Transforms (newest running job "Essay · 40%" or "Nothing running", and an
ink badge with the running count when any), Upload, Settings. Foot:
"COMMAND CENTER" and the host, or "Not signed in". Opens on the screen you
came from (Settings never). Up/Down move, stopping at the ends; OK goes
there; Back or Menu closes it.

### Transforms

Header "Transforms", right "N running". One line per job, running first,
newest first within each: a glyph (running: a ring filling clockwise from
the top with the percentage inside; done: a filled ink circle with a tick;
failed: an outlined circle with an ×), the heading (the agent's title, else
the file's name) and one line under it: the agent's status ("Starting"
until it says), the finish time, or why it failed ("No result" if it gave no
reason). Rows keep their height when selected; the list scrolls only enough
to keep the selected one in view.

- Up/Down: the previous/next job, stopping at the ends. OK: the job's menu.
  Back: the Library. Empty: "No transforms" (or "Not signed in").
- When a running job finishes (seen at the next read) a toast says
  "Done  <result name>" or "Failed  <heading>", on any screen, and the
  Library is read again.

### A job's menu

Label Running / Done / Failed, the heading, then rows (From is shown only
when the heading is the agent's title):

- Running: From, Status, Progress; **Keep running** (picked), Cancel job.
  Cancel job removes it ("Cancelled"); its agent can no longer submit.
- Done: From, Saved as, Finished; View (only if a PDF went on the tablet),
  Download… (only if it has files), Remove ("Removed").
- Failed: From, Failed (time), the reason in small graphite text; Try again
  (same formats; "File no longer on the tablet" if the source is gone),
  Remove.
- **View** opens the result in the preview; Back returns to Transforms.
- **Download…**: "All" (only when more than one format, note "5 files"),
  then each format made (note ".docx", or "3 .jpg" for several images).

### Upload (from the drawer)

Asks for storage the first time; if refused, toast "Storage permission
denied" and nothing opens. Header "Upload", right the place being looked in
("SD card/Documents/School", cut from the start). First level: Phone, and SD
card when one is in. Inside a folder: its folders, then its PDFs and ePubs
(nothing else, nothing hidden), each A–Z ignoring case; a file's line says
"1.9 MB · 2h ago". Empty folder: "No PDFs or ePubs".

- Up/Down move, stopping at the ends. OK on a place or folder goes in, on
  the first line. Back goes up one, landing on the folder just left; at the
  first level, Back returns to the Library.
- OK on a file (signed in): the "Upload to" sheet, the file's name without
  extension, then the tablet's folders ("My files" first, picked). OK
  uploads: toasts "Uploading <name>…" and at 25/50/75%, then
  "Uploaded  <name>", and the Library opens on the new file. Back closes the
  sheet. Not signed in: toast "Not signed in".

### Settings (from the drawer)

"SETTINGS", "Command center", a Server row (host or "Not signed in"), then
Refresh, "Change server…" (or "Sign in…" when signed out), Done. Opens on
Refresh when signed in, on Sign in… when not. Refresh reads the files and
jobs again (signed out, it asks to sign in). The sign-in dialog has an
address field and a token field (shown as dots when one is saved; left
blank keeps it); Save with both signs in ("Signed in to <host>") and reads
again; a missing part says "Both the address and the token are needed".

### Preview (a page up close)

Opens at page 1, 100% (the page's width fills the screen), at the top.

- Top bar (translucent): ‹, the name (cut with …), and on the right
  "4 ‹ Page 2 of 12 › 6", the 4 or 6 greyed at the first or last page; a
  one-page file shows "Page 1 of 1".
- Round ink arrows on each side there is more page to pan to.
- Bottom left, an ink pill: the zoom ("150%") on the highlighter, then
  "* out  # in  0 fit". Bottom right, a minimap of the whole page with the
  part on screen washed yellow and outlined.
- d-pad: pan a quarter of the screen's width, never past the page's edges.
- `#` / `*`: zoom in / out through 100, 150, 200, 300%, keeping the centre;
  no further past either end. `0`: back to 100% at the top.
- `4` / `6`: previous / next page, keeping the zoom, back at the top of the
  page, with "Page n of m" flashed mid-screen for a moment. Nothing on the
  first / last page.
- While a page comes: "Loading page n…" on a blank page. If it can't come,
  the reason on the blank page. Asking past the real count shows the last
  page. A file with no pages at all (an ePub nothing is written on) goes
  straight back with the reason as a toast.
- Back: where it was opened from. Menu: nothing.

## Behaviour

- **Login**: the command center address and delta token, baked in at build
  time (adopted once per build that carries a new one) or entered in
  Settings; kept in the app's preferences. Without one every screen says
  "Not signed in" and nothing is fetched.
- **Files**: `GET /remarkable` every time the app comes to the front and
  after every change (rename, move, delete, upload, a job finishing). The
  last listing is kept in `library.json` and shown at start and offline.
  Trashed files and files in trashed folders are not listed.
- **Pages**: fetched one page at a time as one-page PDFs, kept on disk by
  file and edit time (up to 80 MB, oldest dropped first), so a page seen
  once opens offline and an edit on the tablet fetches it again. Covers are
  page 1; an ePub with nothing written on it has its name on a plain page.
- **Jobs**: `GET /transforms` every 5 s while one runs, every minute
  otherwise, only while the app is in front. Done/Failed toasts only for
  jobs seen running before.
- **Errors**: the command center's message as it words it, with "the
  reMarkable" made "the tablet"; a refused token is "Token refused".
  Changes made while offline fail with a toast and change nothing.
- **Downloads** (Download PDF and a job's Download…): onto the SD card only.
  Documents (.pdf, .docx, .md, .txt, .eml, .fkv) go into SD card/Documents,
  images into SD card/DCIM/Camera, folders made if missing. A taken name
  becomes "Essay (2).pdf"; nothing is overwritten. First time: Android's
  storage permission, then its "allow access to SD card" grant, each asked
  once; refusing says "Storage permission denied" / "SD card access
  denied". No card: "No SD card". Toasts: "Downloading <name>…", then
  "Saved  SD card/Documents/Essay.docx" or "Saved  5 files, SD card"
  (with "/Documents" when they share a folder). Saved files are
  media-scanned so Canvas, Mail, Flashcards and the gallery see them.
- Nothing runs while the app is closed; no notifications.

## Look

DESIGN.md "Tablet" covers the palette (paper, sheet, ink, graphite, rules,
the one highlighter yellow), Literata and Atkinson Hyperlegible, the 1.25
scale, the shelf, Transforms and the preview. Beyond it: sheets rise from
the bottom over a dimmed page; toasts are an ink pill with any lead word
("Done", "Saved", "Uploaded") bold in the yellow; the drawer is sheet
coloured with an ink edge on its right.

## Open questions

1. Signed out, Rename/Move/Delete/Transform toast "Not signed in. Sign in
   from Menu → Settings.", which tells the user where to find something,
   against the app's wording rule; downloads and uploads just say "Not
   signed in". Should it be just "Not signed in" everywhere?
2. Transforms' header always shows "N running", including "0 running";
   DESIGN.md rule 8 discourages counters for their own sake. Keep it, or
   show it only while something runs?
3. Menu does nothing in the preview (no drawer there). Intended?
4. OK on Start when a job has meanwhile started on the same file closes
   the sheet without a word. Should it show the running job instead?
5. Transforms and Upload both go back to the Library on Back, even when
   reached from each other through the drawer. Is that right, or should
   Back return to the previous screen?
