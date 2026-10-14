# AGENTS.md

## Purpose and scope

Delta is white-labelled: the app, its README and the agent itself say
"Delta", never the provider's or model's name. Only the wire protocol
(endpoints, headers, OAuth scopes, `anthropic_cloud`) and legacy storage keys
keep the provider's names, because the service and older installs need them.
`Transcript.rebrand` renames the product in the worker's status and error
lines; the agent's own replies are left as written.

The phone end of Delta (https://github.com/thooton/delta): a small,
dependency-free Android client for cloud agent sessions and for the
Delta command center, for the Sonim XP3. It replaced `cloud` (one session) and
`downloader` (fetch and install an APK). It is a sibling of `../list`,
`../canvas` and `../charge`, and shares their constraints and much of their
code (HTTP, JSON, cloud OAuth, chat views, Markdown, tabs).

- Target: 240x320 mdpi, portrait, d-pad/keypad-driven. Not touch-first.
- Platform Android only: no AndroidX, Material, Compose, or third-party
  libraries. `:core` is plain Java, `--release 8`, and must run on API 27.
- No repository picker, no model or environment settings: the first
  `anthropic_cloud` environment on the account is used, with an empty
  sandbox.

## How it fits into Delta

An agent is started from the Session tab. The first message of every new
session is `Setup.wrap(typed, server, token)`: a `<delta-setup>` block telling
the agent to call itself Delta, install the `delta` CLI with the delta repo's
`latest` release `install.sh` (`curl -fsSL … | sh`), run `delta set-auth <server> <token>`, and read `delta help`, followed by what
was typed. `Transcript` removes that block again (`Setup.strip`), so the phone
never shows it, including when an older session is replayed. The agent then
uses `delta push` to put files on the command center, and they appear in the
Files tab. Keep the setup block short. Everything else the agent needs to know
belongs in `delta help` (in the delta repo), not here.

If you change how this app works in a way an agent needs to know about (for
example the tabs, or what happens to a pushed file), update `delta help` in
`cmd/delta/main.go` in thooton/delta to match.

## Tabs

`MainActivity` has three tabs, laid out like Charge's and Canvas's: a
non-focusable tab bar, with left and right switching tabs on their first press
only. In the composer, left and right move the cursor until it reaches an end
of the text (`cursorOwns`, as in Canvas).

- **Chats**: `Cloud.sessions()` (`GET /v1/code/sessions`, unscoped, as
  the provider's CLI lists them), keeping only `anthropic_cloud` sessions
  (or ones with no kind) that are not archived. `bridge` sessions are Remote
  Control sessions on someone's own computer and are left out on purpose.
  Ids may arrive as `cse_…`; they are always stored as `session_…`
  (`Chat.sessionId`). The list is cached in `chats.json`. Archive is
  `POST /v1/code/sessions/{cse}/archive`, where 409 means already archived.
- **Session**: the old Cloud screen for whichever session is open, under a
  slim title/state strip. The stream (`Link`) stays connected while the
  activity is started, whichever tab is showing. Opening another session bumps
  `generation`, which drops anything still in flight for the old one. You can't
  switch sessions while the first message of a new one is being created.
- **Files**: `Center` (the same routes the CLI uses: `/auth`, `/files`,
  `/files/{name}`) lists, downloads and deletes. Downloads go to
  `files/downloads/<name>` through a hidden `.part` file. `downloads.json`
  records each local copy's remote size and modified time, which is how a
  newer push under the same name shows as "Changed". `ShareProvider` serves a
  downloaded file read-only to the installer or a viewer. `Apks` runs the old
  Downloader's checks (signing key, downgrade) before `ACTION_INSTALL_PACKAGE`.
  A successful install deletes the local copy. "Move to…" copies the local
  copy into a shared folder (`Folders`: Music, Pictures, Download, …, the
  likeliest for the extension first, then any folder inside one, browsed
  with `Folders.subfolders`, where Back goes up a level and "New folder…"
  makes one) under a free name (`Folders.unused`), then deletes it here and
  tells the media scanner. A missing or Changed copy is downloaded first.
  It and Upload are the only uses of shared storage, so storage access is
  asked for on first use, before the folders are listed.
  "Upload from phone…" browses shared storage from its top (`Folders.files`
  lists a folder's files) and `Center.upload` PUTs the pick to
  `/files/{name}`, streamed through `Http.upload` with progress. The command
  center never replaces a file, so `Backend.upload` takes the first name not
  in the list (`RemoteFile.numbered`) and moves on if it is refused with 409.
  Back on Files cancels it. "Send to agent" attaches a file to the next
  message (see Files sent with a message).

## The protocol

`Cloud` is the provider's cloud session protocol (see the cloud demo's
`cloud_demo.py` beside this repository):

- `GET /api/oauth/profile` gives the organization; most calls send it as
  `x-organization-uuid`. Session create and environment create also send
  `anthropic-beta: ccr-byoc-2025-07-29`.
- `POST /v1/sessions` creates the session with the first message as an initial
  event. Later sends go to `POST /v1/code/sessions/cse_…/events` (the same id
  with `session_` swapped for `cse_`).
- `GET /v1/code/sessions/cse_…/events/stream` is SSE. `client_event` frames
  have the sequence number as their `id:` and a stream-json
  `payload`. `?from_sequence_num=N` (plus `Last-Event-ID`) replays everything
  after N, so `Transcript.sequence()` is saved per session and a reconnect
  resumes there.
- Permission mode: `bypassPermissions` is refused for cloud sessions. Every
  send is preceded by a `set_permission_mode: auto` control request, because
  the worker restarts after it has been idle. Do not auto-allow
  `can_use_tool` requests, because that would override Auto's own decisions.
  They are shown, and the person allows or denies them from the menu.
- A newly created environment (only when the account has none) has
  `allowed_hosts: ["*"]`, which is "Full" network access in the web app,
  so the sandbox can run `delta` against the command center and reach
  anything else. An existing environment is used as it is; there is no
  OAuth call to change it, so its network access is set in the web app.

`Transcript` keeps only what a person reads: client `user` messages (echoes
of our own matched by uuid), top-level `assistant` text and `tool_use` lines,
`result` notes when a turn ends badly, and worker `can_use_tool` requests.
Subagent output, tool results, logs and control chatter are dropped. Extend
`DataTests` when you change this.

## Storage

`Store` writes `settings.json` (both logins, owner-only), the
`bundled-cloud.txt` / `bundled-center.txt` revision markers (an older
`bundled-claude.txt`, and a `claude` key in `settings.json`, are still read), `open.txt`,
`chats.json`, `sessions/<session id>.json` and `downloads.json`. Session ids
and file names are validated (`Chat.validId`, `RemoteFile.validName`) before
they become file names. `prune` keeps the 20 most recent transcripts plus the
open one, and sent pictures that no kept transcript refers to are deleted
after a day.

## Pictures

Modelled on the stock Messages app (`com.android.mms`): capture through
`CaptureProvider` (not exported, granted per intent) into `files/capture/`,
pick from `com.android.gallery3d` with `multipick`. `Pictures` shrinks each to
1568px, applies EXIF rotation, re-encodes as JPEG at quality 85, and keeps it
in `files/draft/` until sent. Sending moves it to `files/sent/<uuid>-<n>.jpg`.
At most four per message.

## Files sent with a message

Session's "Attach file" uploads a file as above and attaches it; Files'
"Send to agent" attaches one already there. Attached names (at most ten) are
held in `attachedFiles` (kept across a restart of the activity), shown in a
line above the box, and sent as a `<delta-files>` block at the end of the
message (`Attached.wrap`) telling the agent to `delta pull` each one. The
transcript shows the block as one "Attached: …" line (`Attached.show`),
including in a replay. `delta help` explains the block to the agent; keep
the two in step.

## Layout

- `android/core`: `net/` (`Http`, `Json`, `Config`, `CloudAuth`, `Sse`,
  `Cloud`, `Center`) and `data/` (`Entry`, `Transcript`, `Store`, `Chat`,
  `RemoteFile`, `Setup`, `Attached`, `Folders`). Tests in `src/test` run via `:core:selfTest` with
  the tiny `Check` harness and loopback `TestServer`.
- `android/app`: `MainActivity` (tabs, screens, keys, menus, intents),
  `Backend` (the only bridge to disk and network), `Link` (the reconnecting
  stream thread), `Pictures`, `CaptureProvider`, `ShareProvider`, `Apks`, and
  the views: `ChatAdapter`/`ChatListView` (the transcript), `RowAdapter` (Chats
  and Files), `Markdown`.

## UI rules

- The composer sends on the OK *press* (T9 eats the first release).
- Lists act on the OK *release*, but only for a press that began on the list
  (`okDown`), so a release left over from a dialog or another tab does
  nothing.
- Menu (on its release), or holding OK, opens the stock context menu with
  `showContextMenu(x, y)` on the list at the row's point, never on the row
  itself, and never through the no-argument `showContextMenu()`. A tab with no
  list opens it on its focusable message view.
- Rows paint their own highlight (`SelectableAdapter`); the ListView selector
  is transparent.
- Back cancels a running download while on Files.
- In the Move to folder dialogs, Back goes up a level instead of closing
  (`setOnKeyListener`, acting on the release).

## Build and validation

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

There are no UI automation tests. Key, focus, and popup changes need manual
checks on the handset.

## Credentials

There is no in-app cloud sign-in, by design. `android/app/build.gradle` bakes
in the cloud login (`-Pdelta.cloudAuth`, `../cloud-auth.json`, or the cloud
demo's `auth.json` beside this repository) and the command center (`-Pdelta.center`,
`../delta-center.json`, the CLI's `~/.config/delta/config.json`). It writes
them into a generated `Defaults.java` under `android/app/build/`, with one
revision marker per login, so a new command center doesn't replace a rotated
cloud login. A build with neither, such as a CI build or one made in an
agent's sandbox, installs over a configured copy and keeps its logins. Never
print, commit, or attach the auth files, the generated source, or any APK.
