# Music, for the Sonim XP3

Downloads a song or an album straight to the phone, without opening an agent session. It is the phone end of the command center's music downloads (`POST /music` in [thooton/delta](https://github.com/thooton/delta)), the same ones `delta music` uses, and signs in with the same delta token.

Type `Artist - Title` and press OK. The command center finds the best match and downloads it (trying once more with a broader search if nothing turns up), its log scrolls by below, and the files are saved into the phone's **Music** folder, an album in a folder of its own, where the music player finds them. A file that is already there with the same size is kept rather than saved twice; a different one with the same name gets ` (2)`.

| Key | |
| --- | --- |
| OK | Download what's typed |
| Left / Right | Switch between Song and Album (while the box is empty) |
| Up / Down | Scroll the log |
| Back | Cancel a download, or leave |
| Menu | Switch mode, clear the log, reconnect the music account, command center |

**Reconnect music account** is `delta music re-login`: use it only when downloads keep saying they couldn't connect, because too many new logins get the command center throttled.

## Build

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest :app:assembleRelease
```

The command center login is baked in at build time, as in the Delta app: `-Pdelta.center=<file>`, else `delta-center.json` beside `android/`, else the CLI's `~/.config/delta/config.json`, else the `DELTA_SERVER` and `DELTA_TOKEN` environment variables of an agent's sandbox. A build without one keeps an installed copy's login; Menu → Command center sets one by hand. The APK contains a live token, so treat it like a password; it is gitignored.
