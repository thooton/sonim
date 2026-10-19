# AGENTS.md

A small, dependency-free Android app for the Sonim XP3 that keeps the other
apps in this repository up to date from GitHub. A sibling of `../list`,
`../source` and the rest, under the same constraints: 240x320 mdpi, d-pad
driven, no AndroidX or third-party libraries, `:core` plain Java
`--release 8` running on API 27.

## How it works

CI publishes every app to the rolling `latest` release of thooton/sonim as
`<app>.apk`, beside `manifest.json`, which says everything about each app:
version, package, label, the APK's size and SHA-256, and its newest commits
(see `../.github/manifest.py` and `../AGENTS.md`; `core/Catalog` reads it).
On opening, the Updater fetches only that file from
`https://github.com/thooton/sonim/releases/download/latest/` and lists each
app as (each request on a connection of its own, at a URL with a query
never used before, so Check Again sees a release made a moment ago just as
reopening the app does):

- **Update**: on the phone with an older `versionName` (compared part by
  part, numbers as numbers: `core/Version`).
- **Not installed**: no app with that package on the phone.
- **Up to date**, or **newer than** the release (a hand-installed build).

The screen is drawn as the App Store's Updates page (see "Updater: as the App
Store shows it" in `../DESIGN.md`): groups Available, Installed and Not
Installed, each row with its icon, the date of its newest change, an UPDATE,
OPEN or GET capsule and what's new, all from the manifest. An app whose
manifest entry has no notes shows its version in place of a date.

OK presses the row's capsule (UPDATE and GET queue the app, OPEN launches
it, the ring of an app still waiting takes it out of the line). Rows show
the start of what's new; the whole of it is What's New in the menu. Update All is on the bar, Up from the
top row. When a check answers (on opening or Check Again) the list goes back
to its top row, so updates it found are in view rather than below. Menu or holding OK opens the stock context menu beside the app (as
in List): Update, Get, Open, Reinstall, What's New, Update All and Check
Again. Chosen apps go one at a time: download to
`files/apks/<app>.apk`, check it against the manifest's size and SHA-256, check that the APK is the package the release names,
signed with the phone's copy's key and not older than it, then hand it to
Installer (`../installer`) through `ApkProvider` with `package` set, so
Installer refuses any other package. Without Installer it falls back to the
system installer, which the XP3 refuses for anything not Sonim-signed.

Installer goes after the other apps and the Updater itself last, because
Android stops an app to replace it. Installer replacing itself never answers,
so a result with no message is settled by looking at the phone's copy
(`lastUpdateTime` and `versionName`) a moment later. Back while working only
sends the task to the back, so a download keeps going.

- `android/core`: `Version`, `Catalog` (manifest.json), `Status`, `Notes`
  (what's new, and the App Store's dates),
  `Releases` (fetches and downloads over HttpURLConnection, following
  GitHub's redirect to its file host). Tests in `src/test` run via
  `:core:selfTest`, including a download through a local server.
- `android/app`: `MainActivity` (the list, its keys, the menu, What's New
  and the install queue), `AppAdapter` (rows), `ProgressRing` (the download
  ring), `Icons` (rounded app icons), `ApkProvider` (serves a downloaded
  APK read-only to the installer).

## Build and validation

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :updater:core:selfTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :updater:app:assembleRelease
```

There are no UI automation tests; key, focus and install changes need manual
checks on the handset. Bump the version with every change (`../AGENTS.md`).
