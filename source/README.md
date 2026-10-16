# Source, for the Sonim XP3

Downloads songs, albums and e-books straight to the phone, without opening an agent session. It is the phone end of `delta music` and `delta books` in [thooton/delta](https://github.com/thooton/delta), and signs in with the same delta token.

The bar shows what OK fetches: **Song**, **Album** or **Book**. Left and Right switch between them while the box is empty.

**Music.** Type `Artist - Title` (or `Artist - Album`) and press OK. The command center searches Soulseek (once more, more broadly, if nothing turns up) while its log scrolls by below, and the results replace the log: every one that meets the command center's music config, best first by its preferences, the first one picked. Each shows the file (or an album's folder), its format, bitrate, length and size, and who shares it, how fast, and where in their files. Up and Down move, and OK downloads the picked one while its log scrolls by; Back goes back to the results. The files are saved into the phone's **Music** folder, an album in a folder of their own, where the music player finds them. A file that is already there with the same size is kept rather than saved twice; a different one with the same name gets ` (2)`. Nothing is disguised: the log is the download engine's own.

**Books.** Type a title, author or ISBN and press OK. The results replace the log, the first one picked: Up and Down move, OK downloads the picked book into the phone's **Books** folder as `Author - Title.epub`, and Left and Right turn the page. Searches look for EPUBs, which reflow on a small screen, and show every format when there are none (Menu → Search every format to always do that). The book account is the one `delta books login set` keeps on the command center; it has a daily download limit, and a download that gets a web page instead of a book says the limit is probably used up.

| Key | |
| --- | --- |
| OK | Search for what's typed, or download the picked result |
| Left / Right | Switch between Song, Album and Book (while the box is empty); in book results, the previous or next page |
| Up / Down | Scroll the log, or move through the results (Up from the top goes back to the box) |
| Back | Cancel what's running, stop picking, go back from a download's log to the results, or leave |
| Menu | Switch mode, pages, formats, results or log, clear, reconnect the music account, command center |

**Reconnect music account** is `delta music re-login`: use it only when downloads keep saying they couldn't connect, because too many new logins get the command center throttled.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :source:core:selfTest :source:app:assembleRelease
```

The command center login is baked in at build time, as in the Delta app: `-Pdelta.center=<file>`, else `delta-center.json` beside `android/`, else the CLI's `~/.config/delta/config.json`, else the `DELTA_SERVER` and `DELTA_TOKEN` environment variables of an agent's sandbox. A build without one keeps an installed copy's login; Menu → Command center sets one by hand. The APK contains a live token, so treat it like a password; it is gitignored.

Source was called Music, as `com.music.xp3`. It installs as a separate app, `com.source.xp3`, so uninstall Music once Source is on the phone.
