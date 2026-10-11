# Downloader, for the Sonim XP3

Type the address of an APK, press OK, and the install prompt opens when the download finishes. Built for the XP3's 240x320 screen, d-pad, and T9 keypad, like List.

An app that's already on the phone gets updated in place and keeps its data. Before the prompt opens, Downloader checks the two things Android would otherwise refuse with only "App not installed", and explains them instead:

- the APK is signed by a different key than the installed copy (uninstall the old one to switch)
- the APK is an older version than the installed one (Android won't downgrade)

## Build

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first time it installs something, Android asks you to allow Downloader to install unknown apps (Settings opens from the prompt). Turn it on, go back, and press OK again if the prompt has closed.

## Controls

| Key | Does |
| --- | --- |
| OK | Download the address, or reopen the install prompt for the file you just downloaded |
| Back | Cancel a download, or leave the app |

An address with no `http://` or `https://` at the front gets `https://`. Redirects are followed, including between http and https. The screen stays on while a download runs. The address you typed is kept between launches.
