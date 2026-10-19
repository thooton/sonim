# Print, for the Sonim XP3

Prints a PDF from the phone to a printer on the same Wi-Fi. Pick a PDF, check the preview, press Print. Nothing to install on the printer and no account.

It finds printers by itself, both those that announce themselves (AirPrint, Mopria, IPP Everywhere) and those that don't, by knocking on the printer ports of every address on the network. A printer it can't find can be added by its IP address. It sends the PDF as it is to a printer that reads PDFs; for any other printer it draws the pages on the phone in a language the printer takes: PWG Raster, Apple Raster, PCLm, PostScript or PCL. That covers nearly every network printer of the last fifteen years, over IPP, the raw port (9100) or LPD.

| Key | |
| --- | --- |
| 1 to 6 | Home: Print PDF, Recent, Printers, Print Queue, Ink Levels, Settings |
| 0 | Home: print the last PDF again |
| Number keys | File list: search; each key stands for its letters, as in contacts |
| # | File list: newest first, by name, largest first |
| Left / Right | Preview: turn pages. Print Settings: copies. Printers: tabs. Ink Levels: the next printer |
| * | By IP address: the next box |
| OK | The highlighted thing |
| Back (or the left soft key) | Back a screen. Printing goes on in the background |

The preview opens with Print focused, so a usual job is 1, a place, the file, OK. Print Settings has copies, pages, colour, two-sided and paper size, showing only what the printer can do. Printing carries on with the app hidden; Print Queue shows today's jobs. A printer problem (out of paper, a jam, low ink) is shown in words with what to do next.

Not printable: PDFs protected with a password (the phone's PDF reader can't open them), USB printers, and the cheapest printers that only work with their maker's Windows driver.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :print:core:selfTest :print:app:assembleRelease
```
