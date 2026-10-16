# AGENTS.md

A small, dependency-free Android app for the Sonim XP3 that downloads music
and e-books through Delta. A sibling of `../delta`, `../list` and the rest,
under the same constraints: 240x320 mdpi, d-pad driven, no AndroidX or
third-party libraries, `:core` plain Java `--release 8` running on API 27.
It used to be called Music (`com.music.xp3`).

## How it works

Music speaks the command center's music routes exactly as `delta music` does
(`cmd/delta/music.go`, `cmd/delta-cc/music_run.go` in thooton/delta), with
the delta token: `POST /music` streams JSON lines (`stdout`, `stderr`, `{}`
keepalives, then `done` with `error`, `job`, `files`); `GET /music-jobs/{id}`
is the job's files as a tar (Go's archive/tar, so PAX names); `DELETE
/music-jobs/{id}` removes it; `POST /music-login` is re-login. If the routes
change there, change `Music` here and keep its test in step.

Books work as `delta books` does (`cmd/delta/books.go`, `books_source.go`,
`cmd/delta-cc/books.go`): the book source's account lives on the command
center (`GET /books-login`), and the phone fetches it only to log in to the
source itself, then keeps the session (SharedPreferences `books`) and reuses
it, logging in again once when a call fails on it. Logins are rate-limited by
the source (about ten an hour), so never log in when the stored session would
do. Search, download link and download are the source's own `/eapi` endpoints,
with the CLI's headers, cookies, redirects and domain selection; if the CLI's
client changes, change `Books` here and keep its test in step.

- `android/core`: `net/` — over the shared `Http`, `Json` and `Login` (the
  command center address and token, validated as Delta's `Config` does;
  `../common/core`): `Music` (the music routes), `Books` (the book source client and file
  names), `Tar` (a minimal reader), `Names` (makes job paths safe under the
  Music folder). Tests in `src/test` run via `:core:selfTest`.
- `android/app`: `MainActivity` (the one screen: modes, keys, book results,
  menu, login dialog), `Download` (the single running music download, book
  search or book download, kept outside the activity under a partial wake
  lock; music goes straight into shared `Music/` through `.part` files,
  books into shared `Books/`, and both are handed to the media scanner).

## Credentials

`app/build.gradle` bakes the login into a generated `Bundled.java` under
`build/`, from the same places as the Delta app plus the `DELTA_SERVER` /
`DELTA_TOKEN` environment (`deltaCenter` in `../gradle/bundled.gradle`). A bundled login is adopted once per revision.
Never print, commit or attach the config files, the generated source, the
book account or session, or the APK.
