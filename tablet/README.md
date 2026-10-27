# Tablet, for the Sonim XP3

A reMarkable 2 companion: browse the files on the tablet, read a page up close, and send a file to **Transform**. Tick the formats you want (PDF to begin with; Word, Markdown, Text, Email, Flashcards and Images if you like), and an agent reads the instruction written at the top of page 1 ("Transcribe this + format in MLA"), makes what it asks for in those formats, and puts the finished PDF back on the tablet beside the original. With nothing written as an instruction, it types up what is handwritten.

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

Transforms is the list of jobs, running ones first. OK on one opens its menu: a running job can be cancelled (the menu opens on "Keep it running", so OK, OK never cancels one), a finished one viewed, downloaded (Download… lists All and each format), or tried again with the same formats, and removed from the list. A file's menu also has Delete, which moves it to the tablet's trash.

**Upload** (in the drawer) puts a PDF or ePub from the phone on the tablet: pick Phone or SD card, go into folders with OK (Back goes up one), pick the file, then the tablet folder it goes into. A running job shows how far along it is as a ring, with the percentage inside.

**Download PDF** in a file's menu saves the whole file, as it looks on the tablet with what is written on it, as one PDF into Documents on the SD card.

A finished job's **Download…** saves its files onto the SD card (never the phone's own storage): documents into Documents, where Canvas's file picker finds them under SD card, and page images into DCIM/Camera, where the gallery finds them. If the result needs more work, write on the PDF on the tablet and transform it again.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :tablet:core:selfTest :tablet:app:assembleRelease
```

The login comes from `delta-center.json` beside this file (`{"server": "https://...", "token": "..."}`, gitignored), or the delta CLI's own config; see `gradle/bundled.gradle`. An APK built with one carries the token: treat it like a password.

The type is Literata and Atkinson Hyperlegible, under the SIL Open Font License 1.1 (`OFL.txt`).
