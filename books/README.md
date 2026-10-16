# Books, for the Sonim XP3

An e-book reader for the XP3's 240x320 screen, modelled on Apple Books in dark mode. The page is the whole screen: no status bar, no header, just the text, justified and hyphenated, on black. It reads EPUB (2 and 3) and plain text, Project Gutenberg's included. It has no network access.

## Getting books onto the phone

Copy `.epub` or `.txt` files anywhere on the phone or its SD card (a `Books` folder is the obvious place) with a USB cable or `adb push`:

```bash
adb push "Some Book.epub" /sdcard/Books/
```

The library finds them the next time it opens, with their covers, the book you read last at the top.

## Keys

In the library:

| Key | |
| --- | --- |
| Up / Down | Choose a book (Left / Right jump five) |
| OK | Open it |
| Menu | Mark as unread or finished, look for new books |

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

- **Night Shift** turns the text from soft white to a warm amber for reading in the dark.
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
