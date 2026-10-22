# Books, for the Sonim XP3

An e-book reader for the XP3's 240x320 screen, modelled on Apple Books in dark mode. The page is the whole screen: no status bar, no header, just the text, justified and hyphenated, on black, with how far through the book you are in small grey type in the bottom corner. It reads EPUB (2 and 3) and plain text, Project Gutenberg's included. It has no network access.

## Getting books onto the phone

Books are only looked for in two places: the `Books` folder at the top of the phone's storage (beside `Pictures`, `Music` and the rest) and the `Books` folder at the top of the SD card. Subfolders inside them count; books anywhere else are ignored. Copy `.epub` or `.txt` files there with a USB cable or `adb push`:

```bash
adb push "Some Book.epub" /sdcard/Books/
```

The library finds them the next time it opens, with their covers, the book you read last at the top.

## Deleting and converting

On the SD card, Android sometimes won't let Books open a book another app put there (a file manager's move, Source's downloads), or write beside it. The first time that matters (opening such a book, deleting a book or converting one on the card), Books asks *Allow Books to access SD card?* once; after that it reads, deletes and saves there through that permission.

**Delete Book** in the library's Menu erases the book's file from the phone (it asks first, with Cancel chosen), and its place with it.

**Convert to MOBI** (EPUBs only) saves a MOBI beside the book, under the same name, for a Kindle: `Books/Some Book.epub` becomes `Books/Some Book.mobi`. Converting again replaces it. The header says *Converting…* while it works and *Saved Some Book.mobi* when done. The text keeps its headings, italics, bold, lists, tables and links; the contents become a contents page the Kindle's Go To menu finds; the cover and pictures come along, PNGs and very large pictures redrawn as JPEGs a Kindle accepts. The library itself doesn't list MOBIs.

## Keys

In the library:

| Key | |
| --- | --- |
| Up / Down | Choose a book (Left / Right jump five) |
| OK | Open it |
| Menu | Mark as unread or finished, convert to MOBI, delete the book, look for new books |

Reading:

| Key | |
| --- | --- |
| Down, Right, 6, 8 | Next page |
| Up, Left, 2, 4 | Previous page |
| OK | Show the chapter, pages left in it, and how far through the book you are |
| Menu | Themes & Settings |
| 1 / 3 | Smaller / larger text |
| # | Night Shift on or off |
| * | Contents |
| Back | Back to the library (your place is kept) |

## Themes & Settings

- **Night Shift** warms the whole app toward yellow for reading in the dark: the text, the sheets, the library and the covers. Only the system's own status bar and permission dialog stay as they are.
- **Fonts**: Charter (the face Apple Books uses), Literata, Baskerville, Inter and Atkinson Hyperlegible, each with real italics and bold.
- **Text size**, **line spacing**, **margins**, and **justify** on or off.

The screen stays on while you read, until five minutes after the last key press.

## Build and test

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :books:core:selfTest :books:app:assembleRelease
adb install -r books/android/app/build/outputs/apk/release/app-release.apk
```

## Fonts

Bundled in `android/app/src/main/assets/fonts` with their licences: Literata, Libre Baskerville, Inter and Atkinson Hyperlegible under the SIL Open Font License, and Bitstream Charter under Bitstream's free licence (Matthew Butterick's 2021 packaging). Literata, Libre Baskerville and Inter were cut from their variable fonts to static regular, italic, bold and bold italic instances at text optical size; Charter's `hhea` descent, which shipped with the wrong sign, was corrected.
