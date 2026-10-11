# Charge

Charge is a small native Android app for finding Tesla Superchargers and
starting, monitoring, and stopping a charging session. It is designed for the
Sonim XP3's 240 x 320 display and physical keypad.

- Left/right changes tabs.
- Up/down moves through controls and lists.
- Center/OK activates the selected item.
- Clear or Back leaves a detail screen.
- Menu opens refresh, search-radius, and account actions.

## Build

The project requires JDK 21, Android SDK Platform 35, Build Tools 35.0.1, and a
private `android/.tokens.json` containing a Tesla refresh token.

```bash
cd android
./gradlew assembleDebug
```

For a signed, optimized APK, run `./build-release.sh` from the repository root.

> **Important:** Every APK embeds a live Tesla credential. Keep APKs, token
> files, environment files, and signing keys private and out of source control.

See [AGENTS.md](AGENTS.md) for architecture, development constraints, and
validation guidance.
