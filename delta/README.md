# Delta, for the Sonim XP3

The phone end of [Delta](https://github.com/thooton/delta): Claude Code agents running in Anthropic's cloud, and the files they send back, driven from the XP3's 240x320 screen, d-pad and keypad. It replaces the old Cloud and Downloader apps.

Left and right move between four tabs:

- **Chats** lists your Claude Code sessions in the cloud, on every account the command center keeps, most recently active first. Sessions running on a computer through Remote Control aren't shown, and neither are archived ones. OK opens one in Session; Menu, then New session, starts a new one.
- **Session** is one conversation. Claude's replies appear as they arrive, with a one-line `› Bash: …` record for each tool call. The session runs in **Auto** permission mode; if Claude still asks about a call, Menu lets you allow or deny it. Pictures go along with a message from **Take picture** or **Attach picture**, up to four, each shrunk to 1568px. **Attach file** sends any other file: pick it from the phone's storage, it is uploaded to the command center, and the next message names it so Claude fetches it with `delta pull`. The message shows it as an `Attached:` line.
- **Files** lists what agents have pushed to the command center with `delta push`. OK downloads a file. Once it's on the phone, OK installs it if it's an APK (an app already on the phone is updated in place and keeps its data) or opens it if not. A file pushed again under the same name shows as **Changed**, and OK downloads the new copy. **Menu → Move to…** puts the phone's copy in a shared folder (Music, Pictures, Download and so on, the likeliest for the file first), downloading it first if need be, so a song lands where the music player finds it. Each folder offers **Move here**, **New folder…** and the folders inside it, so a song can go in `Music/Some Album`; Back goes up a level. The first move asks for storage access. A name already taken there gets ` (2)`, ` (3)` and so on. **Menu → Upload from phone…** goes the other way: browse the phone's storage and pick a file to put on the command center (a name already taken there also gets ` (2)` and so on). **Menu → Send to agent** on any file attaches it to your next message in the Session tab.
- **Accounts** lists your Claude accounts, in the order new sessions try them. Each row shows the account's address, how much of its 5-hour limit and its weekly limit is used, and when each resets; on the right is its plan, or **Out** while it has no room left. **Menu → Refresh** checks again.

## Several accounts

The Claude logins live on the command center, not on the phone: add each account with `delta accounts login` (or `delta accounts add <credentials file>`) and the phone fetches short-lived tokens as it needs them. `delta accounts` shows each one's 5-hour and weekly usage. A new session starts on the first account that still has room. When a session's account runs out mid-conversation, a note at the foot of the conversation says until when, with a **Move to a new session** button under it (move down onto it and press OK). Moving stores the whole conversation so far, tool calls and their output included, on the command center, and starts a new session on another account that is told to fetch it (`delta resume`) and read all of it. The old session stays in Chats, and its sandbox doesn't come along, so the agent clones again what it needs. If you send a message instead, it waits on the phone and goes out by itself once the limit lifts. An install from before this hands its own Claude login to the command center the first time it lists the accounts.

The first message of every new session also tells the agent to install the `delta` CLI, connect it to your command center, and read `delta help`. That's how an agent knows to push a fixed APK back to your Files tab. It also tells the agent to call itself Delta, since the app is white-labelled: the phone never shows Claude's name. The text is hidden on the phone, but it stays visible in the session on claude.ai/code, token included.

## Build and test

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The command center login is baked in at build time, and a build without it keeps whatever an installed copy already has. The Claude logins are on the command center (see above).

- **Command center**: `-Pdelta.center=<file>`, else `delta-center.json` beside `android/`, else the CLI's own `~/.config/delta/config.json` (written by `delta set-auth`). The format is `{"server": "https://…", "token": "delta_…"}`. Without one, use **Menu → Command center** on the Files tab. The app only connects over https.

The app makes a cloud environment of its own on each account, named "Delta" and a short code, with full network access and a setup script that installs and signs in the delta CLI. It makes a new one whenever the command center's address or token changes; archive the old one on claude.ai/code if you like.

## Controls

| Key | |
| --- | --- |
| Left / Right | Change tab (inside typed text, move the cursor until it reaches an end) |
| Up / Down | Move through a list or the transcript, or go back to the box |
| OK | Open a session, send a message, download/install/open a file |
| Menu or hold OK | That tab's actions: refresh, archive, copy link, new session; copy text, allow/deny, pictures, attach file, interrupt, paste; send to agent, move to, upload from phone, download again, remove from phone, delete from command center, command center; refresh accounts |
| Back | Cancel a download or upload (on Files), or leave |

The command center login, transcripts, pictures and downloads are kept only in the app's private storage, until you move a download out with **Move to…**. A locally built APK contains a live login, so treat it like a password.
