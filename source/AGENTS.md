# AGENTS.md

A small, dependency-free Android app for the Sonim XP3 that downloads music
and e-books through Delta. A sibling of `../places`, `../list` and the rest,
under the same constraints: 240x320 mdpi, d-pad driven, no AndroidX or
third-party libraries, `:core` plain Java `--release 8` running on API 27.
It used to be called Music (`com.music.xp3`).

## How it works

Music speaks the command center's music routes (`cmd/delta-cc/music_run.go`
in thooton/delta), with the delta token. Unlike a download by name, which
takes the best match, it lets the user choose: `POST /music-search` streams
JSON lines (`stdout`, `stderr`, `{}` keepalives, then `done` with `error`
and `results`, every result that meets the command center's music config,
best first by its preferences); `POST /music` with `{"link"}`, a result's
`slsk://` link, downloads that one, streaming the same way and ending with
`done` (`error`, `job`, `files`); `GET /music-jobs/{id}` is the job's files as
a tar (Go's archive/tar, so PAX names); `DELETE /music-jobs/{id}` removes it;
`POST /music-login` is re-login. The command center doesn't white-label
these routes, and the app doesn't either: the user sees the engine's log,
Soulseek, and who shares each result. Keep it that way. If the routes change
there, change `Music` here and keep its test in step.

Books work as the delta CLI's former `delta books` did (`cmd/delta/books.go`
and `books_source.go` in thooton/delta's history; `cmd/delta-cc/books.go`
keeps the account), and Source is now the only client. The book source's
account lives on the command center (`GET /books-login`; `PUT` sets it), and
the phone fetches it only to log in to the source itself, then keeps the session (SharedPreferences `books`) and reuses
it, logging in again once when a call fails on it. Logins are rate-limited by
the source (about ten an hour), so never log in when the stored session would
do. Search, download link and download are the source's own `/eapi` endpoints,
with the CLI's headers, cookies, redirects and domain selection, as it had
them. `Books` is now the reference: keep its test in step with it.

- `android/core`: `net/` — over the shared `Http`, `Json` and `Login` (the
  command center address and token, validated as `Login` does;
  `../common/core`): `Music` (the music routes, and the results' wording),
  `Books` (the book source client, file names, and a result's other
  formats), `Tar` (a minimal reader), `Names` (makes job paths safe under the
  Music folder). `data/`: `History` (what was saved, newest first, as JSON),
  `Recents` (the last few searches), `Monogram` (a drawn cover's initials and
  tone). Tests in `src/test` run via `:core:selfTest`.
- `android/app`: `Tonal` (the base of every screen: the look, the dark
  theme, the login, watching `Download`), `MainActivity`
  (the list screen: tabs, search, home, log, results, empty states, keys,
  Options), `DetailActivity` (one result or download), `DownloadsActivity`
  (storage, then active, failed and saved downloads), `Download` (one search
  or download at a time, kept outside the activities under a partial wake
  lock: downloads queue, each with its own state and log, and a search waits
  for the running download rather than run beside it, since two runs would
  log the command center's Soulseek account in twice; music goes straight
  into shared `Music/` through `.part` files, books into shared `Books/`, both
  handed to the media scanner, and saved ones kept in `History`),
  `CardStore` (writes onto the SD card through Android's grant for it, the
  core's `Store`: Android 8.1 refuses plain file writes on a removable card
  outside an app's own folder, so `Tonal.whenWritable` asks before the first
  download for the whole card with `StorageVolume.createAccessIntent(null)`,
  keeps the grant (`CardGrant` in `common/ui`, one card's, shared with
  Books), and every folder, part file
  and rename goes through `DocumentsContract`; reading stays plain files,
  under storage access), and the
  drawn pieces: `ResultRow`, `Cover`, `StateButton`, `Wavy`, `StorageCard`,
  `Sheet` (the options sheet), `Files` (play, read, storage).

The look is Tonal (`../DESIGN.md`, "Source: Tonal"), set in DM Sans
(`res/font`, OFL, `OFL.txt`): static weights cut from Google Fonts'
variable font, since API 27 has no use for its axes. Every music result
shows the folders it sits in, as the sharer named them; keep it that way.

## Credentials

`app/build.gradle` bakes the login into a generated `Bundled.java` under
`build/`, from the same places as Places and Mail plus the `DELTA_SERVER` /
`DELTA_TOKEN` environment (`deltaCenter` in `../gradle/bundled.gradle`). A bundled login is adopted once per revision.
Never print, commit or attach the config files, the generated source, the
book account or session, or the APK.
