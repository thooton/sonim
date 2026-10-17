# Canvas, for the Sonim XP3

Week Strip: a calm, planner-style Canvas app for the XP3's 240x320 screen, d-pad and keypad.

The home screen is the week: Assigned work on a timeline under a strip of days, with Missing and Done beside it. This semester's classes (worked out from Canvas's term names) have Stream, Classwork, People and Grades. An assignment page shows the due date, instructions and the teacher's files. You can add typed answers, photos of paper work (saved as one PDF), audio, files and links, then turn them in. Grades, private comments, class comments, classic quizzes, notifications and opt-in reminders are all here. Everything is kept for offline use. A turn-in made with no signal waits on the phone and finishes when the phone reconnects.

## Build and test

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :canvas:core:selfTest :canvas:app:assembleRelease
adb install -r canvas/android/app/build/outputs/apk/release/app-release.apk
```

## Setup

Open **Options → Settings → Account** and enter the Canvas origin, the cookie-service `/cookie` URL and its token, and optionally an IANA time zone. Canvas login cookies come from the cookie service. The app holds them in memory for fifteen minutes and never saves them.

## Controls

| Key | Action |
| --- | --- |
| Left softkey (Menu) | Options for this screen |
| OK | What the middle of the softkey bar says: Open, Select, Turn in, Send |
| Right softkey | The screen's one shortcut (also in Options) |
| Up / Down | Move through the list; Up from the top of the list reaches the strip, then the tabs |
| Left / Right | Change the day, the tab or the page |
| 1–7, 0 | Jump to a day of the week, or back to today |
| Back / Clear | Back one screen (deletes a character in a text field) |

What the app sends to Canvas is limited to the student's own work: submissions, comments on them, discussion replies, planner check marks and classic quiz answers. A locally built APK may contain live bundled credentials, so treat it like a password. Bricolage Grotesque is under the SIL Open Font License (`OFL.txt`).
