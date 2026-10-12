# Canvas, for the Sonim XP3

A small Canvas homework app for the Sonim XP3's 240x320 screen, d-pad, and keypad.

It keeps assignments available offline, groups them into Assigned, Missing, and Done, and includes a model-backed chat that can read Canvas. It never submits work, posts comments, changes grades, or writes anything back to Canvas.

## Build and test

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Setup

Open **Menu → Settings** and enter the Canvas origin, cookie-service `/cookie` URL and token, model and provider (OpenAI API key, ChatGPT/Codex, or Claude Pro/Max), its login, and optionally an IANA time zone.
Canvas login cookies come from the refresher in [canvas/cookie-service](../canvas/cookie-service); they are held in memory for fifteen minutes and never saved. For Claude login, complete the browser flow, copy its final unreachable localhost URL, then return and choose **Paste → Finish**.

## Controls

| Key | Action |
| --- | --- |
| Left / Right | Change tabs, or move through draft text |
| Up / Down | Move the highlight or scroll a page/message |
| OK | Open, toggle, send, copy, or rewind a chat message |
| Back / Clear | Leave assignment details or settings |
| Menu | Refresh, paste, manage chat, open settings, or view About |

The app stores settings, cached work, and chat only in its private app storage. A locally built APK may contain live bundled credentials, so treat it like a password.
