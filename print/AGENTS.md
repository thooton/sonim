# AGENTS.md

A dependency-free Android app for the Sonim XP3 that prints a PDF to a
printer on the same Wi-Fi. Same constraints as its siblings: 240x320 mdpi,
d-pad driven, no AndroidX or third-party libraries, `:core` plain Java
`--release 8` running on API 27. There is no printer driver: the phone's own
`PdfRenderer` draws the pages, and `:core` turns them into whatever the
printer reads.

## The engine (`android/core`)

- `find/`: `Mdns` (DNS-SD over multicast, as a legacy unicast query so no
  multicast need be received; `_ipp`, `_ipps`, `_pdl-datastream`,
  `_printer`), `Dns` (the message codec), `Sweep` (knocks on 631, 9100 and
  515 across the /24 around the phone, for printers that don't announce
  themselves) and `Finder`, which runs both at once, merges what they find
  by address (and queue, for a print server), and probes each printer.
- `ipp/`: `Ipp` (RFC 8010 messages, collections included), `IppClient`
  (HTTP/1.1 by hand with a Content-Length, since not every printer takes a
  chunked body; TLS with any certificate, as printers' are self-signed;
  follows a 426 to TLS) and `Capabilities` (what Get-Printer-Attributes
  says: formats, colour, sides, media, resolutions, location, supplies).
- `raster/`: the page formats, fed a strip of rows at a time so no page is
  ever held whole: `PwgRaster`, `AppleRaster` (URF), `Pclm`, `PostScript`
  (level 2, run-length coded in ASCII85) and `Pcl` (PCL 5, dithered black and
  white, compression mode 2). `Back` is how a back side is turned for
  printers that need it.
- `send/`: `Socket9100`, `Lpd` (RFC 1179) and `Pjl` (asks a raw port what it
  is and which languages it reads; wraps raw jobs).
- `Plan` lists every way to print a job to a printer, best first: the PDF
  itself over IPP when the printer reads PDF (and, for part of a file, takes
  page-ranges), then PWG, URF, PCLm, then PostScript and PCL; IPP before the
  raw port before LPD. `Job` makes and sends the document for the first,
  falling back to the next when the printer refuses the format (0x040A, or a
  job aborted for its format), and follows the job with Get-Job-Attributes
  until it is printed, reporting progress and printer problems. `Choices`
  is what a printer offers for each setting, so screens hide what it can't
  do. `T9` is the file search.

Tests are `SelfTest` and its groups under `src/test`: the codecs, each
format decoded back, the plan for many kinds of printer, and whole jobs to a
pretend IPP printer on loopback (a refused PDF falling back to PWG, a jam,
a cancel, an unreachable printer). Beyond the self-tests, the formats were
checked by rendering them with Ghostscript (PostScript, PCLm) and GhostPCL
(PCL), and whole jobs printed to CUPS's `ippeveprinter`; do the same after
changing a format.

## The app (`android/app`)

Built to a design proposal, **Keypad Grid** (18 screens; its colours, type
scale, sizes and focus styles are in `Look`, its components in `Parts`).
Source Sans 3 is bundled (OFL, `OFL.txt`). The screens are a stack on one
activity (`Screen`, `MainActivity`): Home (`HomeScreen`: printer banner,
six tiles on keys 1 to 6, Print again on 0), `LocationsScreen`,
`FilesScreen` (also browses Phone storage folder by folder), `PreviewScreen`,
`PrintSettingsScreen`, `PagesScreen`, `PrintingScreen` (progress, done,
problem), `PrintersScreen` (Nearby, By IP address, nothing found),
`RecentScreen`, `QueueScreen`, `InkScreen`, `SettingsScreen` (and Help).
`MainActivity.choose` is the proposal's choice dialog, `ask` a question
with a button.

- Back, and the left soft key (Menu on the XP3), go back; nothing has a
  menu. Number keys on Home act wherever the focus is. Every action is a
  button on screen, and exactly one thing is focused.
- `PdfDocument` draws a page for the printer through `PdfRenderer` in strips
  of 128 rows, fitted to the paper, turned a quarter when the shapes differ.
  `Pages` reads counts and draws thumbnails and the preview on one worker.
- `Queue` holds the jobs (main thread); `PrintService` runs them one at a
  time in the foreground, with a wake and Wi-Fi lock, a progress
  notification, and a vibration when a printer takes a job (Settings).
  Hide leaves a job printing. The Done screen shows once the printer has
  accepted the job, as the proposal asks, while Print Queue follows it to
  the end.
- `Store`: the defaults, the printers chosen or added by address
  (`printers.json`; printers only found are known for the run), and the
  last 50 prints (`history.json`) for Recent and Print again.
- `Library` finds PDFs: Downloads, Documents and Bluetooth on the phone and
  the card, the whole card, and Phone storage to browse.

### Where it departs from the proposal

- T9 search matches key sequences against names, as a phone's contact
  search does, rather than through the input method: the first key press is
  then never lost, and # stays free for sorting. The box shows the letters
  matched in the first result.
- Password-protected PDFs can't be opened: `PdfRenderer` has no way to take
  a password. They show a lock and say so.
- A job in the queue can be cancelled or printed again, not paused (pausing
  isn't something printers reliably take from a phone). "Retry" on a
  printer problem looks again: the printer resumes by itself once seen to.
  "Use another printer" sends the whole job again.
- Ink Levels says when a supply is low, but not how many pages are left:
  printers don't say.
- Large text: sizes are in sp, so a larger font setting grows the text.
- Android 8.1 only names the Wi-Fi network to apps with the location
  permission, asked once when Printers first opens; without it the screens
  say "the same Wi-Fi as this phone".

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :print:core:selfTest :print:app:assembleRelease
```
