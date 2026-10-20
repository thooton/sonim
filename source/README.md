# Source, for the Sonim XP3

Downloads songs, albums and e-books straight to the phone, without opening an agent session. It uses the command center's music routes and book account in [thooton/delta](https://github.com/thooton/delta), and signs in with the delta token.

The tabs show what OK fetches: **Song**, **Album** or **Book**. Left and Right switch between them while the keys aren't typing. The bar at the foot says what each soft key does: Menu (left) opens **Options**, the pill in the middle is what OK does, and the right label is what Back does.

**Music.** Type `Artist - Title` (or `Artist - Album`) and press OK. The command center searches Soulseek (once more, more broadly, if nothing turns up) while its log scrolls by below, and the results replace the log: every one that meets the command center's music config, best first by its preferences (or the fastest sharers first, in Options), the first one picked. Each shows the file (or an album's folder), the folders it sits in, its format, bitrate and size, and who shares it; its length, and how fast it comes, are on its page (Options → Details). Up and Down move, and OK gets the picked one; get several and they queue up, one downloading at a time. The button at the end of each row shows where it stands: get, downloading (with how far), queued, saved or failed. OK on a row that is downloading opens its page, with the wavy progress bar, what is left, an album's tracks as they arrive, and the download's own log; on a saved one it plays it; on a failed one it tries again. The files are saved into the phone's **Music** folder, an album in a folder of their own, where the music player finds them. A file that is already there with the same size is kept rather than saved twice; a different one with the same name gets ` (2)`. Nothing is disguised: the log is the download engine's own.

**Books.** Type a title, author or ISBN and press OK. The results replace the search, the first one picked: Up and Down move, OK gets the picked book into the phone's **Books** folder as `Author - Title.epub` (and once saved, opens the Books app to read it), and Left and Right turn the page. A book's page (Options → Details) shows its other formats on the page, to choose between with Left and Right. Searches look for EPUBs, which reflow on a small screen, and show every format when there are none (Options → Formats to always do that). The book account is the one kept on the command center (`PUT /books-login` sets it); it has a daily download limit, and a download that gets a web page instead of a book says the limit is probably used up.

The first download asks two things of Android: storage access, and "Allow Source to access SD card?". Say Allow to both; everything is saved onto the SD card.

Before a search, the screen shows what is downloading, the last few searches (OK runs one again), and how full the phone is; OK on that card opens **Downloads**, which lists what is downloading, what failed, and everything saved, newest first. Options also switches between the light and dark themes.

| Key | |
| --- | --- |
| OK | What the middle of the bar says: search, get, open, play, read, retry, stop |
| Left / Right | Switch between Song, Album and Book (while not typing); in book results, the previous or next page; on a book's page, its other formats |
| Up / Down | Move between the box and the rows, or scroll the log or a page |
| Back | What the right of the bar says: stop the search, back to the box, back to the results, or leave |
| Menu | Options: details, cancel a download, Downloads, sort, formats, pages, results or log, clear, theme, reconnect the music account, command center |

**Reconnect music account** gives the command center a new music account (`POST /music-login`): use it only when downloads keep saying they couldn't connect, because too many new logins get the command center throttled.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :source:core:selfTest :source:app:assembleRelease
```

The command center login is baked in at build time, as in Places and Mail: `-Pdelta.center=<file>`, else `delta-center.json` beside `android/`, else the CLI's `~/.config/delta/config.json`, else the `DELTA_SERVER` and `DELTA_TOKEN` environment variables of an agent's sandbox. A build without one keeps an installed copy's login; Menu → Command center sets one by hand. The APK contains a live token, so treat it like a password; it is gitignored.

Source was called Music, as `com.music.xp3`. It installs as a separate app, `com.source.xp3`, so uninstall Music once Source is on the phone.

The typeface is DM Sans (`android/app/src/main/res/font`), under the SIL Open Font License 1.1 (`OFL.txt`).
