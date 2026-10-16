# AGENTS.md

A small, dependency-free Android app for the Sonim XP3 that serves one
folder over HTTP, in the style of `npx serve`. A sibling of `../music`,
`../list` and the rest, under the same constraints: 240x320 mdpi, d-pad
driven, no AndroidX or third-party libraries, `:core` plain Java
`--release 8` running on API 27.

## Layout

- `android/core`: `net/` — `Server` (GET/HEAD, keep-alive, a thread per
  connection, byte ranges, index.html, 301 to the trailing slash; paths are
  resolved under the root and canonicalised so neither `..` nor a symlink
  leaves it), `Listing` (the directory and 404 pages, modelled on
  serve-handler's templates; keep them looking like `npx serve`), `Paths`,
  `Ranges`, `Mime`, `Addresses` (local IPv4s, Wi-Fi first). Tests in
  `src/test` run a real server on loopback via `:core:selfTest`.
- `android/app`: `MainActivity` (the one screen, keys, the folder picker as
  stacked AlertDialogs like Delta's file picker) and `ServeService` (the
  foreground service that owns the running `Server`, with a partial wake
  lock and a Wi-Fi lock, and a Stop action on its notification).

## Port

`Server.PORT` is fixed at 28413 on purpose, so the address is the same every
time. Don't make it random or configurable without being asked; if it must
change, keep it above 1024, below Android's ephemeral range (32768), and off
the ports dev servers commonly use.

## Build and validation

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest :app:assembleRelease
```

There are no UI automation tests; key, dialog and notification changes need
manual checks on the handset.
