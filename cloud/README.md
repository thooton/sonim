# Cloud, for the Sonim XP3

A Claude Code agent running in Anthropic's cloud, driven from the XP3's 240x320 screen, d-pad and keypad.

There is one conversation at a time. Your first message starts a Claude Code session in your first cloud environment, with an empty sandbox and no repository. Each later message goes to that same session until you pick **New session**. Claude's replies appear as they arrive, with a one-line `› Bash: …` record for each tool call. The session runs in **Auto** permission mode, so Claude makes its own permission decisions. If it still asks about a call, the Menu key lets you allow or deny it.

Pictures go along with a message. From the Menu key, **Take picture** opens the phone's camera and **Attach picture** opens the Gallery, the same way the stock Messages app does it. Each picture is shrunk to at most 1568px on its long side, turned upright, and shown above the box until you send. One message can carry up to four, with or without text.

The session also lives on claude.ai/code. **Copy session link** gives you its URL so you can carry on from a computer, and anything sent from there shows up here too.

## Build and test

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The login is baked in at build time. It comes from `-Pcloud.claudeAuth=<file>` if you pass one, then `claude-auth.json` beside `android/`, then `claude_cloud/auth.json` beside this repository, all in Pi's shape under `anthropic`. The app renews the login and saves each rotation. Once the phone renews it, the refresh token in the file stops working, so anything else using that file (such as `cloud_demo.py`) needs a fresh login. Give Cloud its own login if you can.

## Controls

| Key | |
| --- | --- |
| Up / Down | Scroll the transcript, or go back to the box |
| OK | Send |
| Menu or hold OK | Copy text, Allow/Deny a permission request, Take picture, Attach picture, Remove pictures, Interrupt, Paste, Copy session link, New session |

The login, the transcript and the pictures you sent are kept only in the app's private storage. The camera writes straight into it, so nothing lands in the Gallery. A locally built APK contains a live login, so treat it like a password.
