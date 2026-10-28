# Canvas

## Purpose

A Canvas LMS client for the student's own schoolwork on the Sonim XP3
(240x320, portrait, d-pad, two softkeys, T9 keypad), used one-handed with
keys and never touch. It is built around the week: open it, see what is
due Tuesday, open the assignment, add the work (typed, photographed,
recorded, a file or a link), turn it in, close it. It also shows classes,
grades, comments, quizzes and notifications. Everything is cached, so it
opens at once and reads offline, and a turn-in made with no signal
finishes on its own later.

It must stay calm, quick and small: one yellow highlighter for focus, dates
instead of countdowns, nothing red, no nagging. Canvas writes are only the
student's own work: a submission (text, files, URL), a private comment, a
discussion or class-comment reply, a planner check mark, and a classic
quiz attempt. It must never grade, change a course, show other users'
private data, send the login to arbitrary URLs, or grow a chat or model.

## Not wanted

- The model-backed chat tab and its AI logins (removed; leftovers are
  deleted on launch). Don't bring a model back.
- A visible softkey bar, or any job for Menu or Back but "open Options"
  and "go back". No action lives on a softkey alone.
- Text that teaches the app ("Press Menu to…", key lists, example input),
  "Loading…" lines, skeletons, half-drawn pages (DESIGN.md rule 7).
- Countdowns, red, grades that change colour, counts or dots on the
  Missing and Done tabs, a week range beside the home tabs.
- Classes from past terms; AndroidX, Material, Compose or any library.

## Screens

Keys everywhere unless a screen says otherwise: Up/Down move between rows;
OK (DPAD_CENTER or ENTER, acted on release) opens or presses the row under
the d-pad; Menu (MENU or SOFT_LEFT) opens Options; Back goes back one
screen, and on Home leaves the app; Clear does Back except in a text
field, where it deletes a character. Where Options would be empty, Menu
does nothing. Every loading state is one spinner filling the screen.

### Options and sheets

- Options is a bottom sheet titled "Menu" of numbered rows: the screen's
  own entries, then (on Home, Classes, a class, an assignment,
  Notifications) Classes, Notifications ("N new" when unseen), Refresh,
  Settings, leaving out the screen you are on. Digits 1–9 pick a row;
  Menu or Back closes it.
- Choice sheets (Settings) are radio rows, the current value focused.
  Question dialogs focus the yes button; Back cancels.

### Home: Assigned, Missing, Done

Three zones, top to bottom: tabs, week strip (Assigned only), list. The
open tab is large ink text, the others small and muted.

- Tabs row: Left/Right switch tab, wrapping; OK moves one tab right.
- Week strip: seven days from Monday, Sunday or the region's first day
  (Settings), a dot per piece of assigned work due, in its class colour.
  The day in view is ink, yellow while the strip has focus. Left/Right
  move it a day, across weeks. OK or Down goes to that day's first row
  (Down on an empty day: the first row on screen).
- Assigned list: a heading ("TUESDAY OCT 20", "2 due") for each day with
  work, and for the day in view even when empty ("Nothing due"). A row:
  due time (AM/PM under it on a 12-hour phone), a class-coloured dot on a
  rail, the title, "CODE · Course title". Undated work comes last under
  "No due date", in the current week only. The day in view follows the
  focus down the list.
- On a row: Left/Right jump to the previous/next day with work (into the
  next or previous week when this one has none left); Up on the first row
  goes to the strip and scrolls to the top; OK opens the assignment.
- Digits 1–7 jump to that day of the week in view, 0 to today; from a row
  the focus lands on that day's first row, or the strip if it has none.
- An empty week: the paper-plane drawing, "Nothing due this week" (and
  "Press 0 for today." on another week); OK on the strip goes to today.
- Launch opens on today, unless nothing is due this week, then on the
  first day with work due.
- Missing: sections No due date, This week, Last week, Earlier, with
  counts, newest first; meta "CODE · Was due Tue, Oct 20". Empty:
  "Nothing missing".
- Done: Returned (newest first, grade chip, "Returned Tue, Oct 20"), then
  Turned in ("Turned in Oct 20", "Not graded" if it has points, "Marked
  done" for check marks). Empty: "Nothing turned in yet".
- Not set up: "Canvas isn't set up". No cache and the sync failed:
  "Couldn't reach Canvas" and why. No cache yet, syncing: the spinner.

### Classes

"Classes" with the term on the right; a row per class this semester: a
tile in the class colour with its code ("THF" over "101"), title, teacher,
"N due" when it has dated assigned work. OK opens the class. None: "No
classes this semester".

### A class

Header: colour square, title (one line, "…"), "CODE · Teacher · Term";
tabs Stream, Classwork, People, Grades. Left/Right switch tabs (wrapping)
wherever the focus is. Options: Next topic (Classwork with more than one
topic; wraps), "Turn reminders off/on" for this class.

- Stream: an Upcoming card (the class's soonest dated assigned work), then
  announcement cards (author, date, title, body cut at four lines) each
  ending in a "Class comments" / "N class comments" row that opens the
  post. None: "No announcements yet."
- Classwork: topics with "N items", text headers, rows with an icon and
  "Assignment · Due …", "· Turned in", or "· Returned" and a grade chip.
  OK: work in the list opens its page; a page opens the page reader; a
  file the viewer; a discussion the post screen; a link asks "Open in
  browser" / "Close"; else "This one opens on a computer." Empty: "No
  classwork posted".
- People: Teachers, then Classmates; OK does nothing. Empty: "This class
  doesn't show its people".
- Grades: "Overall NN.N%" if Canvas gives one; Graded work, returned
  newest first, then turned-in work "Not graded yet". Empty: "Nothing
  graded yet."
- Nothing cached and the fetch failed: "Couldn't open this class".

### Assignment page

One scrolling page, the focus first on the title (on "Cancel turn-in" when
queued). Top line "‹ <where you came from>", class dot and code. Then:

- Not returned: a tear-off calendar (weekday, day number) beside "DUE /
  Tuesday, Oct 20 / at 10:30 PM", or "No due date"; a green "Turned in"
  stamp with date, time and item count once in; pills for status
  (Assigned, Missing, Turned in, Marked done, Excused), "N points", "N of
  M tries".
- Returned or excused: the grade large in ink ("18/20", "A", "Excused"), a
  green Returned pill and date, the teacher's latest comment under "From
  your teacher"; instructions and files are not shown.
- Instructions, formatted. Each link is a stop for Up/Down and wears the
  highlighter; OK opens the link sheet. Not fetched yet: "The instructions
  haven't downloaded yet." (or "…couldn't be loaded." and why).
- "From your teacher": file boxes; OK opens the viewer.
- "Your work": what was turned in (typed answer with word count, link,
  files that open), the draft's pieces, then the dashed "Add work" box
  ("Write, take photos, record, attach or add a link", as allowed), or
  "Add more work" / "Turn in again"; then "Turn in" when the draft has
  pieces, "Mark as done" / "Mark as not done" for work with nothing to
  submit, else "This one is turned in on a computer." or "This assignment
  is locked." Quizzes show "Start quiz" / "Take it again"; discussions an
  answer box.
- Rubric rows ("earned/points", "–" unscored), then the "Private
  comments" box.

Options: Rename and Remove (on a piece), Add work (when allowed), Private
comments, "No reminders for CODE" / "Remind me about CODE".

- Add your work: Write an answer, Take photos, Record audio, Choose a
  file, Add a link (only what the assignment accepts). Camera, microphone
  and storage are asked for on first use; refused: "The phone didn't
  allow it."
- OK on a piece: the editor, the link screen, play/stop audio, or the
  viewer. Remove asks "Remove <name>?".
- Turn in: a draft that doesn't fit says "Can't turn in yet" and why;
  otherwise "Turn in your work?" / "N items will be turned in for
  <title>." with Turn in focused. Uploads show "Uploading NN%" and a bar.
- Discussion: "Your answer" (formatting from Options), saved on the phone
  as typed; Post asks "Post your answer?" / "Everyone in CODE can read
  it." Empty: "Write your answer first."

### Editor (Write an answer)

"‹ Your work", the piece name ("My answer", "My answer 2"), a full-height
field, footer "N words" and "✓ Saved on phone" (gone while typing, back
2 s after a pause). OK or Back is Done: an empty answer is dropped,
otherwise it joins the draft with the focus on it. Options: Bold, Italic,
Underline, Bulleted list, Numbered list (each "On" when it applies; with
no selection it applies to what is typed next), Paste (Markdown and rich
text arrive formatted), Cut, Copy, Delete word, New line, Symbol, Rename.

### Camera and photo check

- Camera: preview, corner guides, a "Page N" chip. OK or the camera key
  captures (keeping what is inside the guides) and opens the check.
  Options: Gallery (a picture from storage), Done ("N pages"). Back
  discards unkept pages. No camera: "The camera couldn't be opened."
- Check: the page, numbered thumbnails, "Page N of M"; Left/Right move
  between pages. OK keeps all pages as one PDF ("Notes on paper", "Notes
  on paper 2") and returns to the assignment. Options: Add page, Retake.

### Record audio

Starts on opening: "Recording" / "Paused", an m:ss clock, a level wave. OK
or Back stops and adds "Recording N"; under half a second: "Nothing was
recorded." Options: Pause/Resume, Discard (asks first).

### Choose a file

Places: Phone, SD card (if mounted), Downloads, with "N items"; then
folders before files, newest first (Options: "A to Z" / "Newest first"),
hidden files left out; file rows show size and date. OK copies the file
into the draft; over 50 MB: "That file is too large to send from the
phone." Back goes up a folder, from a top folder to Places, then out.
Empty: "Nothing here" ("No pictures here" from Gallery: jpg, png, webp).

### Add a link

"Link" starting "https://", chips 1 https:// 2 www. 3 .com 4 / (OK
inserts at the cursor), "Title (optional)". OK in a field adds it; not
like "https://x.y": "Type the whole address, like https://example.com". No
title: the address without https:// and www. Options: Clear, Cancel.

### Viewer

The file name and, for PDFs, "Page N of M" on top. PDFs and pictures fit
the width, text files show as text, others "This file can't be shown on
the phone". Up/Down scroll; Left/Right and OK turn PDF pages (wrapping).
Options: Zoom / Fit (double width; Left/Right pan). A teacher file is
downloaded once and kept; failing: "Couldn't download" and why.

### Links, web pages, Canvas pages

- A link to an assignment this semester opens its page; to one outside:
  "That assignment isn't in this semester's classes."; to another Canvas
  page: "That Canvas page opens on a computer."
- Otherwise a sheet titled with the link text (cut at 60): Open (with the
  site), Save to SD card. Open shows a Canvas file, PDF or picture in the
  viewer, a web page as its text. Save: "Downloading…", then "Saved to SD
  card/Download/<name>" (a clash gets " (2)"), or "No SD card".
- Web and Canvas pages scroll with Up/Down, stopping at links in view.
  No text: "This page has no text to show." / "This page is empty."

### Comments

- Private: "‹ <assignment>", "Private comments", "Only you and <teacher>",
  bubbles ("No comments yet."), and "Write to <teacher>" focused at the
  bottom. OK sends at once ("Sending…"); a failure toasts and keeps the
  text. Up/Down scroll while the box is one line. Options: Clear,
  Refresh. Back returns, and the page shows the new comments.
- Class (a post or discussion): the post, "Class comments N", replies
  with avatar, name and time, "Add a class comment"; OK asks "Post a class
  comment?" / "Everyone in CODE can read it." Closed: "Comments are
  turned off for this post." Options: Clear, Refresh.

### Quiz

Classic quizzes only. An attempt in progress resumes; otherwise "Start the
quiz?" states questions, time limit and tries ("Not now" doesn't start
it); an access code: "Take it on a computer." One question per screen,
a bar of equal steps, "N of M" on top. Choices are radio rows (OK picks;
many-answer toggles); typed and number answers have a field where OK is
Next. Below: Previous (not on the first), Next, or Turn in on the last,
which counts blanks and says "You can't change answers afterwards." Each
answer saves to Canvas as chosen or on leaving it; a failure toasts "Not
saved yet: …". Menu does nothing; Back leaves, all answers kept.

### Notifications

Grouped Today, Yesterday, "Tuesday, Oct 20"; rows: icon by kind (returned,
posted, comment, reminder, message), title, "detail · time". OK: a comment
opens private comments, other work its page, else its class, else "This
one opens on a computer." Opening the screen marks all seen. Empty:
"Nothing new".

### Settings, Account, field

- Settings: Theme (Light, Dark, Follow phone), Text size (Default,
  Large), Reminders (Off, Morning of 08:00, Evening before 18:00, Both),
  Week starts on (Monday, Sunday; "(region)" until chosen), Download
  files (Always, On Wi-Fi only, Never), Account (the Canvas host, or "Not
  set up"). OK opens the choices; theme and size apply at once.
- Account: "Signed in to Canvas" / "Not set up"; Canvas address, Cookie
  service, Service token (dots), Time zone ("Not set"); "Use the bundled
  setup" when the build has one; Sign out, which asks, then removes the
  settings, saved work and everything fetched. A rejected value (both
  addresses must be https): "Not saved" and why. A new address, service
  or token drops the work on screen and syncs.
- Field: "‹ Back", title, one-line field focused; OK saves and returns,
  Back returns unsaved. Options: Paste (with text on the clipboard),
  Clear, Cancel.

## Behaviour

- Everything lives in the app's private files and survives a restart:
  settings (owner-only, with the token), prefs, the work cache, details,
  class pages, notifications, drafts and their files, downloaded teacher
  files. No backup.
- Launch draws the last cache at once (this semester's classes only, by
  term name) and syncs if it is over 10 minutes old, as does returning to
  the app. Refresh syncs now: "Up to date", "Updated. Some classes
  couldn't be read." or why; with no setup, "Canvas isn't set up. See
  Settings, then Account." One unreadable class never blanks the rest.
- The session cookie comes from the cookie service, is held only in
  memory for 15 minutes and never written down. Links outside Canvas are
  fetched only on Open or Save, https only, with no login.
- Drafts are saved before anything is sent. With no signal a turn-in is
  queued: "You're offline. It will turn in when you reconnect.", pieces
  "Saved on phone · will upload", and "Cancel turn-in". It sends when the
  signal returns, app open or closed, and a notification says it went
  in. An online failure: "Couldn't turn in" and "Your work is saved on the
  phone." Uploaded files are never sent twice; the draft goes only once
  Canvas has the submission.
- Reminders: one notification at 08:00 for work due that day and/or 18:00
  for the next day's: "Reminder for Wednesday", "<title>, due Oct 21 and N
  more". Never for work already in or a silenced class; re-set after a
  reboot.
- Dates use the Time zone setting or the phone's; times follow the
  phone's 12/24-hour setting. Errors are short and never show Canvas
  data, cookies or tokens.

## Look

DESIGN.md "Canvas: Week Strip" holds. Text sizes are 1.15x (Default) and
1.3x (Large) of its steps; due times never wrap at either.

## Open questions

1. "Download files" is saved but nothing reads it: teacher files always
   download when opened. Should it control that, or go?
2. Reminders default to "Evening before, 18:00", yet README and AGENTS.md
   call them opt-in. Should the default be Off?
3. "Save to SD card" uses plain file writes, which root AGENTS.md says
   fail on the XP3's card without the card grant. This spec assumes it is
   meant to work (through CardGrant).
4. Lines that tell the user what to do survive the "show, don't tell"
   sweep: "Press 0 for today.", "Press OK to stop.", "Fit the page inside
   the corners", "Sign in under Settings, then Account.", the token and
   time zone help, the per-class reminders note, "Type the whole address,
   like https://example.com". Keep or cut?
5. PDF pages wrap from the last back to the first. Intended?
