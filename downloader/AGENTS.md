# AGENTS.md

## Purpose and scope

A deliberately tiny, dependency-free Android app for the Sonim XP3: download
an APK from a typed address, then hand it to the system installer. A sibling
of `../list` and shares its constraints and look.

- Target: 240x320 mdpi, portrait, d-pad/keypad-driven. Not touch-first.
- Platform Android only: no AndroidX, Material, or third-party libraries.
  minSdk/targetSdk 27 (the XP3 runs 8.1), Java 8 source level.
- One screen. Do not add a download list, history, or background service
  unless asked.

## Layout

`android/app` only:

- `MainActivity`: address field, progress, status; OK and Back handling;
  the pre-install checks (signing key and version against the installed copy).
- `Fetch`: `HttpURLConnection` download with manual redirects (it will not
  cross http/https on its own) and cancel/progress callbacks.
- `ApkProvider`: serves the single cached `download.apk` read-only to the
  installer through a URI grant (file:// is refused from targetSdk 24;
  FileProvider would be a library).

## Install and update

`Intent.ACTION_INSTALL_PACKAGE` with `EXTRA_RETURN_RESULT`, which also updates
an installed app in place. Needs `REQUEST_INSTALL_PACKAGES`; the platform
installer itself walks the person through "install unknown apps".

## UI rules

- OK in the address field acts on the *press* (T9 eats the first ENTER
  release), and both halves are swallowed so no newline goes in. Sonim
  reports OK as `DPAD_CENTER` or `ENTER`.
- Back cancels a running download instead of leaving.

## Build and validation

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

No automated tests; download, install, and update flows need a manual check
on the handset.
