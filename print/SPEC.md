# Print

## Purpose

Prints a PDF from the Sonim XP3 (240x320, d-pad, Menu and Back keys, T9
keypad) to a printer on the same Wi-Fi, driven one-handed with keys, never
touch. Pick a PDF, glance at the preview, press OK: a usual job is 1, a
place, the file, OK. No driver, no account, nothing installed on the
printer, no cloud: the phone finds the printer itself (Bonjour/mDNS and a
sweep of the /24) and sends the PDF, or pages it draws itself (PWG, URF,
PCLm, PostScript, PCL), over IPP, port 9100 or LPD.

It must stay a quick, local, key-driven printing tool. It must never become
a PDF reader or editor, a file manager, a cloud-print or account service,
or anything that talks to the internet. A new feature is judged by whether
it gets more PDFs onto more printers in fewer key presses.

## Not wanted

- Menu repeating what a button already does. Every action is a button on
  screen; Menu (Options) only offers what no button does (0.2.1, 0.3.0).
  Menu never goes back; only Back does (0.2.0).
- Pausing a job: jobs can be cancelled or printed again, not paused.
- Pages-left estimates on Ink Levels (printers don't say).
- Password-protected PDFs, USB printers, Windows-only (GDI) printers.
- Typing through the input method for search: T9 matches key sequences
  directly, so the first key is never lost.
- AndroidX, Material, third-party libraries.

## Screens

Every screen but Home and Printing has a top bar with "‹ Back" on the left
and the title in the middle. Back closes an open dialog first; otherwise
goes back one screen, and on Home leaves the app. Menu (or the left soft
key) opens Options when the screen offers any, and does nothing otherwise;
Menu again closes Options. Exactly one thing is focused on every screen.

A choice dialog has radio rows with the saved value marked and focused; OK
picks, Back changes nothing. A question ("Cancel this print job?") has two
buttons, the safe one ("Keep printing", "Keep") focused.

### Home

Top bar "Print" (no Back). Under it a dark printer banner: the printer in
use and a dot with its state ("Checking…", then "Printer is ready",
"Printer is busy", or the problem, e.g. "Can't reach the printer"). With no
printer: "Choose a printer" / "No printer chosen yet". Then six tiles in
two rows, each with its number: 1 Print PDF, 2 Recent, 3 Printers, 4 Print
Queue, 5 Ink Levels, 6 Settings. Tile 4 has a white badge with the number
of active jobs, hidden at 0. Under the tiles, once anything has been
printed, a "Print again" row with key 0, a thumbnail and "<title> · N
pages"; it is hidden when nothing has been printed.

- Focus starts on tile 1. The d-pad moves between banner, tiles and row.
- Keys 0 to 6 act wherever the focus is (on release). 7 to 9 do nothing.
- OK on the banner opens Printers; on a tile or Print again, as its key.
- 0 opens Preview of the last PDF with the settings used last time; if the
  file is gone, a toast "That file has been moved or deleted".
- 1 asks for storage permission the first time; if refused, a toast "Print
  needs to read storage to find PDFs" and nothing opens.
- Back leaves the app (jobs keep printing).

### Choose a PDF (locations)

Header "Locations", "Looking for PDFs…" while scanning, then rows with an
icon, a name and a count: Recent ("N PDFs printed recently", only when some
past prints still exist), Downloads, Documents, Bluetooth ("N PDFs
received"), SD card (when there is one), and Phone storage ("Browse all
folders", always there). Locations with no PDFs are left out. Downloads
and Documents cover the phone and the card; folders named Android at the
top of a root and names starting with "." are skipped.

- Focus starts on the location used last, else Downloads, else the first.
- OK opens that location's file list (Recent opens Recent).
- Going back here keeps the scan; it is not repeated.

### File list

Top bar: the location's name (a sort icon on the right for scanned
locations). A search box "Type to search", a grey line ("12 PDFs · Newest
first"), then rows: thumbnail of page 1, the name without ".pdf", and
"6 pages · 412 KB · Today" (or "Protected" / "Can't be read" in place of
the pages; a lock in place of the thumbnail when protected). Dates read
"Today", "Yesterday", "Oct 14", or "Oct 14, 2024" for another year.

- Phone storage (and its folders) lists folders first, then PDFs, each by
  name; the line reads "3 folders · 2 PDFs" or "No folders or PDFs here".
  OK on a folder opens it as a new file list.
- At most 300 rows are built; then the foot reads "Showing 300 of N. Type
  to find the others."
- Number keys search at once: each key stands for its letters, as in
  contact search. The box shows the letters matched in the first result
  with a "T9" tag and takes the focus; the matches are highlighted in
  yellow; the line reads "N results for “abc”" or "No PDFs match “abc”".
- With the box focused: Down goes to the first row, OK opens the first
  result. Up on the first row returns to the box while searching.
- Delete/Clear removes the last digit; with none left, focus goes to the
  first row.
- Back with a search clears it; Back without one goes back.
- OK on a PDF opens Preview.
- Menu (scanned locations only): Sort → Newest first, By name, Largest
  first. The order is kept between runs.

### Preview

Top bar: the file's name. "Opening…" while it opens. Then the page as it
will print, with a paper chip ("Letter 8.5 x 11 in") top right, arrows at
the sides (dimmed at the ends) and "Page 2 of 6" at the bottom; a line
"<printer> · 1 copy · Color" (adds "2-sided" and the pages when not all;
"N pages, may take several minutes" for 50+ pages; "No printer chosen"
with none); and two buttons, Settings and Print.

- Focus starts on Print, so OK prints.
- Right on Print and Left on Settings turn pages; so do Left/Right on the
  page area. Page 1 and the last page stop there.
- Print sends the job, returns to Home and opens Printing over it.
- Print with no printer opens Printers; the chosen printer becomes this
  job's (and the one in use, if there was none), back in Preview.
- Settings opens Print Settings for this job.
- A protected PDF shows "Can't open this file" / "This PDF is protected
  with a password…"; a broken one "The PDF may be damaged or incomplete.
  Try downloading it again." Either has one button, "Back to files".
- Opened from Recent or Print again, it starts with that print's printer
  and settings (pages fitted to the document); otherwise with the defaults
  from Settings, fitted to what the printer can do.

### Print Settings

Rows: Printer, Copies (◀ N ▶), Pages, Color, Two-sided, Paper size; a foot
"Uses N sheets of Letter paper"; a Print button. Rows the printer can't do
are hidden (no Color for a black-and-white printer, no Two-sided without
duplex).

- Focus starts on Copies. Left/Right there change copies, 1 to 99.
- Printer opens Printers to pick one for this job; options it can't do are
  fitted (colour off, one-sided, nearest paper size).
- Color: Color / Black & white. Two-sided: Off, Long edge, Short edge.
  Paper size: the printer's sizes, with a note "Only sizes this printer
  supports are listed." or "This printer doesn't say which sizes it takes."
- Print closes this screen and prints as Preview's Print does.
- Back keeps the changes for this job (not as defaults).

### Pages

Three radio rows: All pages ("Pages 1–6"), Page range (From [ ] to [ ]),
Current page only ("Page 3", the page showing in Preview). A strip of up to
six thumbnails around the range, those that print outlined in blue; a foot
"2 of 6 pages selected"; a Done button.

- Focus starts on the current choice. OK on a row selects it.
- On Page range: digits type into the focused box (the first digit
  replaces it); Left = From box, Right = To box, * switches. Delete removes
  a digit. Leaving the row or switching boxes clamps to real pages and
  swaps an end before the start.
- Done saves and goes back. Back goes back without saving.

### Printing

Top bar "Printing" (no Back hint). Four states:

- Sending: printer picture with a page feeding out, a headline ("Waiting
  to start", "Preparing page 2 of 6", "Sending page 3 of 6", "Waiting for
  the printer"), a progress bar, "<title> · 1 copy / to <printer>", and
  Hide (focused) and Cancel print. Hide goes Home; the job carries on.
  Cancel print asks "Cancel this print job?" (Keep printing / Cancel
  print).
- Done (from when the printer accepts the job): a green tick, "Sent to the
  printer", "N pages are printing on <printer> in the <location>." (or
  "N pages printed on <printer>." once it says so), Done (focused, goes
  Home) and Print another PDF (goes Home, then opens Choose a PDF).
- Canceled: "Print canceled" (or "Canceled at the printer"), "<title>
  won't print.", Done and Print another PDF.
- Problem: a headline and what to do, with a "Paused at the printer" pill
  while the job waits. Out of paper → "Load Letter paper in the main tray,
  then choose Retry."; paper jam, cover open, tray out, output full, paused
  likewise. Buttons: Retry (focused) and Use another printer. Out of ink
  on a colour job: Print in black & white and Cancel print. Waste ink full,
  password wanted, no format the printer reads: Use another printer and
  Cancel print (Done once failed). Can't reach: "Check that <printer> is
  on and connected to “<Wi-Fi>”."
- Retry on a paused problem hides it until the printer says something
  else; on a failed job it sends the job again. Print in black & white and
  Use another printer cancel this job and send a new one.
- Back goes back (Home after printing, or Print Queue); the job carries on.

### Printers

Top bar "Printers", tabs Nearby and By IP address. Left/Right switch tabs
(except between the two buttons of a pair).

- Nearby: "SEARCHING WI-FI “NAME”" with a spinner for about 15 s, then
  "WI-FI “NAME”" ("WI-FI" without the location permission). Kept printers
  are listed at once; found ones appear as found. Each row: picture,
  name, "Ready · Office · .1.24" (state, location, end of the address;
  "Black & white only" when so), a blue tick on the one in use. Focus is
  on the one in use, else the first.
- OK on a printer makes it the one in use (or this job's, when a job asked)
  and goes back. A chosen printer is kept between runs.
- Nothing found: "No printers found", "Check that the printer is on and
  connected to “NAME”, the same Wi-Fi as this phone.", buttons Search again
  (focused) and Add by IP address. Wi-Fi off: "Wi-Fi is off", "Turn on
  Wi-Fi to find printers on your network.", Open Wi-Fi settings and Add by
  IP address.
- Menu: Forget printer (on a kept printer; it is no longer the one in use)
  and Search again (once a search has ended with printers listed).
- By IP address: "Printer IP address", four boxes with the phone's first
  three parts filled in and focus in the fourth; the hint "Type with the
  number keys. * moves to the next box."; a result card; Add printer
  (greyed until a printer is found). Digits fill a box and move on when it
  can take no more; * or Right = next box, Left = previous; Delete removes a
  digit, then moves back a box. A whole address is checked by itself
  (after ~1 s, or at once with 3 digits in the last box; OK checks again):
  "Looking for a printer…", then "Found printer" with "<name> · PDF,
  color, 2-sided" or, in red, "No printer found" / "Nothing answers at
  <address>". Add printer keeps it, picks it and goes back.
- The first time Printers opens, Android asks for location (once, ever);
  the screen works either way.

### Recent

The last 50 prints, newest first, under "Today", "Yesterday", "This week",
"Earlier". Each row: thumbnail, title, "Printed 2:48 PM · OfficeJet"
("Tue", "Oct 3" for older). A file that's gone reads "Moved or deleted" in
grey and does nothing on OK, but can still be focused. Empty: "Nothing
printed yet" / "PDFs you print show up here, ready to print again."

- OK opens Preview with that print's printer and settings.
- Menu: Remove from Recent (the focused row), Clear Recent (asks "Clear
  Recent?" / "Your PDFs stay where they are.", Keep / Clear). The files
  themselves are never touched.

### Print Queue

"In progress" (running with a bar and chevron, waiting ones "Waiting", the
first of them with a "Next" pill), then "Finished today" newest first
("Printed 1:12 PM" with a green tick, "Canceled 1:15 PM", "Didn't print:
<reason>" in red). Empty: "No print jobs" / "Jobs you send from this phone
show up here until midnight." Updates live, at most twice a second, keeping
the focused job.

- OK opens Printing for that job.
- Menu: Cancel print (active job, asks first) or Print again (finished job;
  sends it to the same printer and opens Printing).

### Ink Levels

The printer in use first, then the other known printers; "2 of 3" when
there are several, Left/Right cycle (wrapping). Head: picture, name, state
("Ready · Letter paper loaded"). Then one tall bar per supply in its colour
with "%" (or "?") and its name; a note for the lowest low supply ("Cyan ink
is low. Have a new one ready." / "…is empty. Replace it to print in
color."); "Updated just now". No printer: "No printer chosen". Unreachable:
"Can't reach the printer". No levels reported: "No ink levels". Nothing on
the screen is focusable but the page itself.

### Settings

"Printing defaults": Default printer (opens Printers), Color, Two-sided
(switch; on means long edge), Paper size (the in-use printer's sizes;
default by the phone's country). "App": Vibrate when done (switch, on by
default), Help, About ("Version x.y.z", not focusable). OK flips a switch
or opens a choice. Jobs already queued keep what they were sent with.

Help lists the keys in brief and how printers are found.

## Behaviour

- Kept in the app's files dir: `printers.json` (printers chosen or added
  by address), `history.json` (last 50 prints with their settings); in
  preferences: printer in use, defaults, sort, last location, whether
  location was asked. Printers only found are known for this run.
- The queue lives in memory: jobs and "Finished today" go when the app's
  process does. Finished jobs from before midnight are not shown.
- Jobs run one at a time in a foreground service with a progress
  notification ("<title>" / status, tapping opens its Printing screen), a
  wake lock and a Wi-Fi lock, so Hide, Back or the screen going off don't
  stop them. A failed job leaves a notification "<title> didn't print".
  When a printer accepts a job the phone vibrates once (200 ms) if Vibrate
  when done is on.
- If the printer refuses the format, the job falls back to the next way
  (PDF over IPP → PWG → URF → PCLm → PostScript → PCL; IPP → 9100 → LPD).
- All traffic is bound to the Wi-Fi, even one without internet. No
  internet is ever used. Offline (Wi-Fi off) shows the Wi-Fi is off screen.
- "Open with" from another app goes straight to Preview (a content link
  is copied into the cache first). Permissions: storage (asked on 1),
  location (asked once, only for the Wi-Fi's name).

## Look

Not the family look of DESIGN.md: its own design, Keypad Grid (grey top
bar with blue "‹ Back", coloured numbered tiles, bundled Source Sans 3,
blue focus outlines; `Look`, `Parts`). Text in sp; portrait only.

## Open questions

1. README's key table says # sorts files and the left soft key goes back;
   since 0.2.0/0.3.0 sorting is in Menu and the left soft key opens Menu.
   I've specified the code; should README be corrected?
2. Several screens spell out keys ("Menu on a job to cancel it or print it
   again." in Print Queue, "Type with the number keys. * moves to the next
   box." in By IP address), which DESIGN.md rule 7 forbids for the family.
   Does that rule apply to Print, or do these stay?
3. Preview's Print with no printer chosen opens Printers and then returns
   to Preview, where Print must be pressed again. Should picking the
   printer print straight away?
4. A job that fails with "Can't open this file" offers "Use another
   printer", which can't help. Should it offer only Done?
5. A PDF opened from another app is copied into the cache, and Recent/
   Print again point at that copy, which Android may clear (then "Moved or
   deleted"). Is that acceptable?
6. Jobs aren't kept across a process restart; a job waiting when the app
   is killed is silently lost. Intended?
