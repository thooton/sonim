# Canvas, for the Sonim XP3

A small Canvas homework app for the Sonim XP3's 240x320 screen, d-pad, and keypad.

It keeps assignments available offline and groups them into Assigned, Missing, and Done. It never submits work, posts comments, changes grades, or writes anything back to Canvas.

## Build and test

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :canvas:core:selfTest :canvas:app:assembleRelease
adb install -r canvas/android/app/build/outputs/apk/release/app-release.apk
```

## Setup

Open **Menu → Settings** and enter the Canvas origin, cookie-service `/cookie` URL and token, and optionally an IANA time zone.
Canvas login cookies come from the refresher in [canvas/cookie-service](../canvas/cookie-service); they are held in memory for fifteen minutes and never saved.

## Controls

| Key | Action |
| --- | --- |
| Left / Right | Change tabs |
| Up / Down | Move the highlight or scroll a page |
| OK | Open an assignment or toggle a section |
| Back / Clear | Leave assignment details or settings |
| Menu | Refresh, expand/collapse sections, open settings, or view About |

The app stores settings and cached work only in its private app storage. Versions before this one had a chat tab; on first launch this version deletes the conversation and model login they left there. A locally built APK may contain live bundled credentials, so treat it like a password.
