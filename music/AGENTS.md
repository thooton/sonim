# AGENTS.md

A small, dependency-free Android app for the Sonim XP3 that downloads music
through the Delta command center. A sibling of `../delta`, `../list` and the
rest, under the same constraints: 240x320 mdpi, d-pad driven, no AndroidX or
third-party libraries, `:core` plain Java `--release 8` running on API 27.

## How it works

It speaks the command center's music routes exactly as `delta music` does
(`cmd/delta/music.go`, `cmd/delta-cc/music_run.go` in thooton/delta), with
the delta token: `POST /music` streams JSON lines (`stdout`, `stderr`, `{}`
keepalives, then `done` with `error`, `job`, `files`); `GET /music-jobs/{id}`
is the job's files as a tar (Go's archive/tar, so PAX names); `DELETE
/music-jobs/{id}` removes it; `POST /music-login` is re-login. If the routes
change there, change `Music` here and keep its test in step.

- `android/core`: `net/` — `Http`, `Json` (copied from Delta), `Login` (the
  command center address and token, validated as Delta's `Config` does),
  `Music` (the routes), `Tar` (a minimal reader), `Names` (makes job paths
  safe under the Music folder). Tests in `src/test` run via `:core:selfTest`.
- `android/app`: `MainActivity` (the one screen, keys, menu, login dialog),
  `Download` (the single running download, kept outside the activity under a
  partial wake lock; fetches the tar straight into shared `Music/` through
  `.part` files and tells the media scanner).

## Credentials

`app/build.gradle` bakes the login into a generated `Defaults.java` under
`build/`, from the same places as the Delta app plus the `DELTA_SERVER` /
`DELTA_TOKEN` environment. A bundled login is adopted once per revision.
Never print, commit or attach the config files, the generated source, or the
APK.
