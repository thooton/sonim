# Delta, for the Sonim XP3

The phone end of [Delta](https://github.com/thooton/delta): Claude Code agents running in Anthropic's cloud, and the files they send back, driven from the XP3's 240x320 screen, d-pad and keypad. It replaces the old Cloud and Downloader apps.

Left and right move between three tabs:

- **Chats** lists your Claude Code sessions in the cloud, most recently active first. Sessions running on a computer through Remote Control aren't shown, and neither are archived ones. OK opens one in Session; the top row starts a new one.
- **Session** is one conversation. Claude's replies appear as they arrive, with a one-line `› Bash: …` record for each tool call. The session runs in **Auto** permission mode; if Claude still asks about a call, Menu lets you allow or deny it. Pictures go along with a message from **Take picture** or **Attach picture**, up to four, each shrunk to 1568px.
- **Files** lists what agents have pushed to the command center with `delta push`. OK downloads a file. Once it's on the phone, OK installs it if it's an APK (an app already on the phone is updated in place and keeps its data) or opens it if not. A file pushed again under the same name shows as **Changed**, and OK downloads the new copy.

The first message of every new session also tells the agent to install the `delta` CLI, connect it to your command center, and read `delta help`. That's how an agent knows to push a fixed APK back to your Files tab. It also tells the agent to call itself Delta, since the app is white-labelled: the phone never shows Claude's name. The text is hidden on the phone, but it stays visible in the session on claude.ai/code, token included.

## Build and test

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Both logins are baked in at build time, and a build without them keeps whatever an installed copy already has:

- **Claude**: `-Pdelta.cloudAuth=<file>`, else `cloud-auth.json` beside `android/`, else `claude_cloud/auth.json` beside this repository, all in Pi's shape under `anthropic`. The app renews the login and saves each rotation, so once the phone has renewed it, the refresh token in the file stops working. Give Delta its own login if you can.
- **Command center**: `-Pdelta.center=<file>`, else `delta-center.json` beside `android/`, else the CLI's own `~/.config/delta/config.json` (written by `delta set-auth`). The format is `{"server": "https://…", "token": "delta_…"}`. Without one, use **Menu → Command center** on the Files tab. The app only connects over https.

The cloud environment has to be able to reach the command center's host. If the app has to create your first cloud environment, it gives it full network access. Otherwise, set the environment's network access to **Full** (or add the host) on claude.ai/code.

## Controls

| Key | |
| --- | --- |
| Left / Right | Change tab (inside typed text, move the cursor until it reaches an end) |
| Up / Down | Move through a list or the transcript, or go back to the box |
| OK | Open a session, send a message, download/install/open a file |
| Menu or hold OK | That tab's actions: refresh, archive, copy link, new session; copy text, allow/deny, pictures, interrupt, paste; download again, remove from phone, delete from command center, command center |
| Back | Cancel a download (on Files), or leave |

The logins, transcripts, pictures and downloads are kept only in the app's private storage. A locally built APK contains live logins, so treat it like a password.
