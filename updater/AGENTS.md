# AGENTS.md

A small, dependency-free Android app for the Sonim XP3 that keeps the other
apps in this repository up to date from GitHub. A sibling of `../list`,
`../delta` and the rest, under the same constraints: 240x320 mdpi, d-pad
driven, no AndroidX or third-party libraries, `:core` plain Java
`--release 8` running on API 27.

## How it works

CI publishes every app to the rolling `latest` release of thooton/sonim as
`<app>.apk`, beside `versions.txt` (`<app> = <versionName>`) and
`packages.txt` (`<app> = <package name>`), both read from the built APKs
(see `../.github/workflows/build-apks.yml` and `../AGENTS.md`). On opening,
the Updater fetches the two files from
`https://github.com/thooton/sonim/releases/download/latest/` and lists each
app as:

- **Update**: on the phone with an older `versionName` (compared part by
  part, numbers as numbers: `core/Version`).
- **Not installed**: no app with that package on the phone.
- **Up to date**, or **newer than** the release (a hand-installed build).

Menu, OK or holding OK opens the stock context menu beside the app (as in
List): Update, Install, or Reinstall (for an app already current), plus
Update all and Check again. Chosen apps go one at a time: download to
`files/apks/<app>.apk`, check that the APK is the package the release names,
signed with the phone's copy's key and not older than it, then hand it to
Installer (`../installer`) through `ApkProvider` with `package` set, so
Installer refuses any other package. Without Installer it falls back to the
system installer, which the XP3 refuses for anything not Sonim-signed.

Installer goes after the other apps and the Updater itself last, because
Android stops an app to replace it. Installer replacing itself never answers,
so a result with no message is settled by looking at the phone's copy
(`lastUpdateTime` and `versionName`) a moment later. Back while working only
sends the task to the back, so a download keeps going.

- `android/core`: `Version`, `Catalog` (the two files), `Status`,
  `Releases` (fetches and downloads over HttpURLConnection, following
  GitHub's redirect to its file host). Tests in `src/test` run via
  `:core:selfTest`, including a download through a local server.
- `android/app`: `MainActivity` (the list, its keys, the menu and the
  install queue), `AppAdapter` (rows), `ApkProvider` (serves a downloaded
  APK read-only to the installer).

## Build and validation

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease
```

There are no UI automation tests; key, focus and install changes need manual
checks on the handset. Bump the version with every change (`../AGENTS.md`).
