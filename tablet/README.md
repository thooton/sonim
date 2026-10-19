# Tablet, for the Sonim XP3

A reMarkable 2 companion: browse the files on the tablet, read a page up close, and send a file to **Transform**. An agent reads the instruction written at the top of page 1 ("Transcribe this + format in MLA"), makes what it asks for, and puts the finished file back on the tablet beside the original.

Tablet reaches the tablet through the Delta command center, which the tablet syncs with (through rmfakecloud) and which runs the transform jobs. It signs in with the same address and token as Delta: baked in at build time, or entered from Menu → Settings.

| Key | |
| --- | --- |
| Left / Right | Library: the previous or next file |
| Up / Down | A choice in a menu or sheet; a job in Transforms; a folder or file in Upload; pan the page in the preview |
| OK | Library: the file's menu (Open, Transform, Download PDF, Rename, Move to folder, Details, Delete). Transforms: the job's menu. Elsewhere, the highlighted thing |
| Menu (left soft key) | The drawer: Library, Transforms, Upload, Settings |
| Back (right soft key) | Close the drawer or a sheet, leave the preview or Transforms; on the shelf, leave the app |
| 4 / 6 | Preview: previous, next page |
| * / # / 0 | Preview: zoom out, zoom in, fit the page |

Transforms is the list of jobs, running ones first. OK on one opens its menu: a running job can be cancelled (the menu opens on "Keep it running", so OK, OK never cancels one), a finished one viewed, downloaded, or tried again, and removed from the list. A file's menu also has Delete, which moves it to the tablet's trash.

**Upload** (in the drawer) puts a PDF or ePub from the phone on the tablet: pick Phone or SD card, go into folders with OK (Back goes up one), pick the file, then the tablet folder it goes into. A running job shows how far along it is as a ring, with the percentage inside.

**Download PDF** in a file's menu saves the whole file, as it looks on the tablet with what is written on it, as one PDF into Documents on the SD card.

**Download Word** and **Download PDF** save a finished job's result into Documents on the SD card (never the phone's own storage), where Canvas's file picker finds it under SD card. Hand in the Word document; if the result needs more work, write on the PDF on the tablet and transform it again. A job has a Word document when the agent made one.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :tablet:core:selfTest :tablet:app:assembleRelease
```

The login comes from `delta-center.json` beside this file (`{"server": "https://...", "token": "..."}`, gitignored), or the delta CLI's own config; see `gradle/bundled.gradle`. An APK built with one carries the token: treat it like a password.

The type is Literata and Atkinson Hyperlegible, under the SIL Open Font License 1.1 (`OFL.txt`).
