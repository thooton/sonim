# List, for the Sonim XP3

A simple ordered todo list with a Claude assistant, for the XP3's 240x320 screen, d-pad, and keypad.

The **List** tab is a checklist. The **Chat** tab is Claude Opus 4.6, which can read and rewrite the list (`get_list` / `set_list`) and nothing else. It must read the list before writing it, and has to read it again if you changed anything in the meantime.

## Build and test

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Claude login comes only from `claude-auth.json` beside `android/` (Pi's shape, under `anthropic`), baked in at build time. The app renews it and saves each rotation. Give List its own login: a refresh token shared with another app stops working when either one renews it.

## Controls

| Key | List tab | Chat tab |
| --- | --- | --- |
| Left / Right | Switch tabs | Switch tabs, or move through draft text |
| Up / Down | Move the highlight | Scroll messages / enter the box |
| OK | Tick or untick the item | Send |
| Menu or hold OK | Insert above/below, Edit, Delete (or Add item) | Copy/rewind the highlighted message, stop, paste, compact, clear |

The list, chat, and login are kept only in the app's private storage. A locally built APK contains a live login, so treat it like a password.
