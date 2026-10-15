# List, for the Sonim XP3

A simple ordered checklist for the XP3's 240x320 screen, d-pad, and keypad. It has no network access.

## Build and test

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Controls

| Key | Action |
| --- | --- |
| Up / Down | Move the highlight |
| OK | Tick or untick the item |
| Menu or hold OK | Insert above/below, Edit, Move up/down, Delete (or Add item) |

The list is kept only in the app's private storage. Versions before this one had a Claude chat tab; on first launch this version deletes the login and conversation they left there.
