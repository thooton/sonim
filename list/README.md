# List, for the Sonim XP3

A simple ordered checklist for the XP3's 240x320 screen, d-pad, and keypad, styled after Google Tasks. It has no network access.

## Build and test

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :list:core:selfTest :list:app:assembleRelease
adb install -r list/android/app/build/outputs/apk/release/app-release.apk
```

## Controls

| Key | Action |
| --- | --- |
| Up / Down | Move the highlight |
| OK | Tick or untick the task (it moves to or from Completed); on Completed, show or hide finished tasks |
| Menu or hold OK | Add a task, Insert above/below, Edit, Move up/down, Delete, Delete all completed |

The list is kept only in the app's private storage. Versions before this one had a Claude chat tab; on first launch this version deletes the login and conversation they left there.
