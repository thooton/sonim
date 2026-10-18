# AGENTS.md

A small, dependency-free Android app for the Sonim XP3 that shows and
manages the files on a reMarkable 2, and sends a file to Transform: a job on
the command center in which an agent reads the instruction handwritten at
the top of page 1, makes the document it asks for, and puts the result back
on the tablet. Same constraints as its siblings: 240x320 mdpi, d-pad driven,
no AndroidX or third-party libraries, `:core` plain Java `--release 8`
running on API 27.

It shows as Tablet and never names the tablet's maker on screen. It is
built to a design proposal that called it Margin (`../DESIGN.md`,
"Tablet"), with changes made on the phone: everything is drawn 1.25 times the
proposal's pixels (`Look.SCALE`), since its 9px text was unreadable at arm's
length, and a file's actions moved out of a sheet always under the shelf
into a menu OK opens (`Sheet.actions`), which gave the covers the room.
Transforms went the same way: the proposal opened the selected job into a
card with its buttons, which moved the list under the eye, so a job is one
line like any other and OK opens its menu (`Sheet.job`).

## The command center

Everything comes from the Delta command center (thooton/delta,
`cmd/delta-cc/remarkable.go` and `transform.go`), signed in with the delta
token as Source and Places are (`gradle/bundled.gradle`, or Settings). The
tablet syncs with rmfakecloud on the same machine, and the command center
reads and writes its storage, so the app sees what the tablet last synced.

- `GET /remarkable`: the Library (`Library`), every document not in the
  trash, newest edit first, and the folders for Move.
- `GET /remarkable/{id}/pages/{n}`: one page as the tablet shows it, as a
  one-page PDF, with `X-Pages` for the count. `Pages` keeps them on disk by
  document id and edit time and draws them with `PdfRenderer`; covers are
  page 1, small. An ePub with nothing written on it has no pages to show
  (the tablet lays it out itself), so its cover is its name on a plain page.
- `POST /remarkable/{id}` with `{"name"}` or `{"parent"}`: Rename, Move,
  and Delete (`"parent": "trash"`, the tablet's trash, which the user
  empties on the tablet). Delete asks first, opening on Cancel.
  The route is PATCH, which `HttpURLConnection` can't send; the command
  center takes POST the same.
- `GET /files/{name}`: a finished job's result from Delta's Files (its
  `files`: the PDF, and the Word document when the agent made one), which a
  job's menu downloads into `Documents` on the SD card, never the phone's
  own storage (`Card.root` in common; no card, no download).
  `Downloads.free` numbers a name already taken. Canvas's file picker
  reaches it (SD card, Documents), so the `.docx` can be handed in straight
  from there: many of the user's teachers take only Word documents. The PDF
  is the one on the tablet, for marking up and transforming again.
  Downloading asks for storage the first time.
- `POST /remarkable/documents?name=&parent=`: Upload, a PDF or ePub from
  the phone, streamed (`Http.upload`), named as the file is without its
  extension, into the folder picked on the Upload to sheet. Upload is a
  screen of its own (`UploadScreen`, from the drawer): the phone's storage
  and the SD card, then their folders and the files the tablet takes
  (`Browse`), folders first. Back goes up a folder. Once it is up, the
  Library opens on it. Reading storage asks for it the first time.
- `GET /transforms`, `POST /transforms`, `DELETE /transforms/{id}`: the jobs.
  They are read every 5 seconds while one runs (every minute otherwise),
  and a job that finishes is announced and the Library read again, since
  its result is a new file.

A job is running, done or failed: there is no pausing and no queue (the
proposal had both; they were dropped). Cancel forgets a running job, after
which its agent can't submit anything. The agent's own words are what the
job's row shows: its title for the job (until then the document's
name) and its status line. Its percentage is never words: a running job's
glyph is a ring (`Ring`) that fills clockwise from the top, with the number
in the middle. The command center can't read
the instruction before a job starts, so the Transform sheet shows the top
of page 1 itself, where the instruction is written.

The proposal's other open questions: results go beside the original (the
command center's choice); there are no alerts while the app is closed; any
number of jobs run at once; offline, the Library shows the last listing
(`library.json`) and the pages already seen. The command center's error
messages are shown as it words them, with "the reMarkable" made "the
tablet".

## Wording

The screens state facts and never explain the app. What a sheet knows about
a file or a job is label and value rows (`Sheet.rows`), not sentences; a
toast is a few words ("Renamed", "Saved  SD card/Documents/Essay.docx");
nothing says where to find something or what will happen next. Keep it
that way.

The Library opens on the newest file, at the far left, every time the app
comes to the front.

## Layout

- `android/core`: `data/` - `Doc` (a file), `Library` (the listing and
  its folders), `Job` and `Jobs` (the transform jobs and their wording), `Downloads`
  (where a result is saved), `Browse` (the phone's folders, for Upload),
  `When` (times in words), `Viewport` (the preview's zoom levels, panning
  and clamping, for a page of any height); `net/Center` (the command
  center). Tests in `src/test` run via `:core:selfTest`, the client against
  `TestServer`.
- `android/app`: one activity, `MainActivity`, owning the state, the
  network calls and the key routing (left soft key is `KEYCODE_MENU`, right
  is `KEYCODE_BACK`), over `LibraryScreen` (with `Shelf`, the sliding
  covers), `TransformsScreen` (with `Ring`), `UploadScreen`, `PageView` (the preview, its chrome drawn over
  the page), `Drawer` and `Sheet` (the file menu, Transform, Details,
  Rename, Move, Settings, a job's menu). `Pages` fetches and draws pages; `Look` is the
  palette, fonts and scale; `Mark` the highlighter stroke; `Parts` shared
  pieces. Everything is built in code; there are no layouts.

The highlighter yellow means "this is selected" and nothing else; keep it
that way. Fonts are static instances cut from Google Fonts' Latin subsets
(`res/font`, OFL, `OFL.txt`).

## Testing on the phone

Test on the real XP3 over adb (`adb -d`); never start the emulator. Before
sending key events, check Tablet is the app in front: Back on the shelf
leaves the app, and later keys land in whatever is underneath.
