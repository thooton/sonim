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
- No repository picker, no model or environment settings: each account's
  Delta environment (see The protocol) is used, with an empty sandbox.

## How it fits into Delta

An agent is started from the Session tab. Its cloud environment does the
setup (see The protocol): the setup script (`Setup.SCRIPT`) installs the
`delta` CLI from the delta repo's `latest` release `install.sh`, and the
`DELTA_SERVER` and `DELTA_TOKEN` variables sign it in to the command center.
The first message of every new session is `Setup.wrap(typed)`: a
`<delta-setup>` block telling the agent to call itself Delta and to read
`delta help`, followed by what was typed. The command center's backup
sessions are set up the same way and open with the same block, word for
word (`agentSetup` in `cmd/delta-cc/agents.go` in thooton/delta); keep the
two in step. `Transcript` removes that block again (`Setup.strip`), so the
phone never shows it, including when an older session is replayed. The agent
then uses `delta push` to put files on the command center, and they appear in
the Files tab. Keep the setup block short. Everything else the agent needs to
know belongs in `delta help` (in the delta repo), not here.

If you change how this app works in a way an agent needs to know about (for
example the tabs, or what happens to a pushed file), update `delta help` in
`cmd/delta/main.go` in thooton/delta to match.

## Tabs

`MainActivity` has four tabs, laid out like Charge's and Canvas's: a
non-focusable tab bar, with left and right switching tabs on their first press
only. In the composer, left and right move the cursor until it reaches an end
of the text (`cursorOwns`, as in Canvas).

- **Chats**: `Backend.sessions()`: `Cloud.sessions()` for every cloud
  account (see Cloud accounts), merged newest first, each `Chat` tagged with
  its account. An account whose list fails is left out and named above the
  list. `Cloud.sessions()` is `GET /v1/code/sessions` (unscoped, as
  the provider's CLI lists them), keeping only `anthropic_cloud` sessions
  (or ones with no kind) that are not archived. `bridge` sessions are Remote
  Control sessions on someone's own computer and are left out on purpose.
  Ids may arrive as `cse_…`; they are always stored as `session_…`
  (`Chat.sessionId`). The list is cached in `chats.json`. Archive is
  `POST /v1/code/sessions/{cse}/archive`, where 409 means already archived.
  Beside them are the command center's backup sessions (`Center.sessions()`,
  `GET /agent-sessions`; ids `session_delta_…`, told apart by `Chat.backup`),
  merged newest first by `Backend.sessions()`, which keeps their last list
  (and names the failure above the list) when only they can't be listed.
  They belong to no account, so usage limits, waiting messages and "Move to
  a new session" never apply to them. Every title is shown with its kind's
  mark (`Chat.label`): ● for a cloud session, ▲ for a backup one. The menu
  offers "New session ●" and "New session ▲" (each only when this build can
  start that kind); a backup session has **Delete** (`DELETE
  /agent-sessions/{id}`, which removes its computer too) in place of Archive,
  and no session link. Backup sessions are created with what was typed
  (`POST /agent-sessions`): the command center writes their setup itself.
  Their stream (`Center.open`, `GET /agent-sessions/{id}/events`) is in the
  cloud's frame format, so `Link` takes either through `EventSource` and
  `Transcript` reads both. They never ask permission. Never name the agent or
  the model behind either kind anywhere in the app.
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
- **Accounts**: `Backend.usage()`: every account (`Center.accounts`, in
  the order new sessions try them) with its usage report
  (`Center.usage`: `five_hour` and `seven_day` utilization and
  `resets_at`), in `RowAdapter` rows with a second line (`Row.extra`): the
  address, "5-hour: 42% · resets 3:05 PM", "Weekly: …", and on the right
  the plan, or "Out" while either window is full or a limit is known. A
  full window it finds is written to `limits.json`, so rotation skips that
  account at once. Fetched each time the tab is shown and from Menu →
  Refresh.

## Cloud accounts

The phone holds no cloud login. The command center keeps one or more
(`delta accounts` in the delta repo adds and removes them; see its
AGENTS.md) and renews them, because a subscription login's refresh token
changes on every renewal and two parties renewing it would kill it.

- `Center.accounts()` lists them (`GET /cloud-accounts`), in the order new
  sessions try them. `CloudAuth.of(center, id)` fetches an account's access
  token (`POST /cloud-accounts/{id}/token`) and keeps it until five minutes
  before it expires; after a 401 the next fetch sends the refused token, so
  the command center renews the login. `Backend` keeps one `Cloud` per
  account.
- A session belongs to the account it was started on. `Backend` learns which
  from the session lists and keeps it in `chats.json` and in each saved
  transcript (`account`); a session it can't place is looked up again.
- **Rotation.** `Backend.create` starts a session on the first account not
  known to be out (`limits.json`, account id to epoch ms) whose usage report
  (`GET /cloud-accounts/{id}/usage`, via `Center.limitedUntil`) shows room
  in both the 5-hour and weekly windows. A full one is written down until it
  resets. If none has room, the error says when the first is free again.
- **Running out mid-conversation.** The stream's `rate_limit_event` with
  `status: rejected` and `resetsAt` (seconds) sets
  `Transcript.limitedUntil()`, and `MainActivity.received` records it for
  the session's account. While the open session's account is out, the foot
  of the transcript (`ChatAdapter.replace`'s note and button, neither of
  them entries or saved) says until when. If another account isn't known
  to be out (`Backend.canMove`), a **Move to a new session** row follows,
  which the d-pad reaches like a message and OK presses. The header is
  unchanged.
- **Moving** (`MainActivity.moveSession`, `Backend.move`) reads the old
  session's whole event history (`Cloud.history`: `GET
  /v1/code/sessions/{cse}/events?sort_order=asc&cursor=<last sequence>`,
  page by page) and writes it out as text (`Carry.build`): every
  main-thread message, thinking block, tool call (full input) and tool
  result (full output), nothing shortened; subagents' inner turns are left
  out (their results are in). That goes to the command center
  (`Center.putConversation`, `POST /conversations`), which stores it under
  a random id. Then a session starts on another account, titled the same,
  whose first message opens with a `<delta-continued>` block
  (`Carry.block`) telling the agent to run `delta resume <id>
  conversation.md` and read the whole file. Pictures the user sent ride
  along as images on that message, and messages waiting for the limit
  follow the block. A failed move reopens the old session with its
  messages still waiting.
  `Transcript` shows the block as one note (`Carry.NOTE`). `delta help`
  explains the block; keep the two in step.
- **Waiting messages.** A message sent while the session's account is out
  is shown (`Transcript.queued`, no turn starts) and kept in `queue.json`
  (`Queued`: session, uuid, text, files, picture count, when, tries) until a
  minute after the limit lifts. A tick (`MainActivity.sendDue`, on a
  `Handler`, also run at every start) sends what is due; a limit found to
  last longer pushes it back, and a failed send is tried again each minute,
  up to ten times. Moving the conversation takes its waiting messages along.
- **Older installs** kept the cloud login in `settings.json`. The first
  account listing hands it to the command center (`POST /cloud-accounts`,
  which keeps its place if the account is already there) and drops it from
  the phone; a login the provider no longer takes is dropped too.

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
- Sessions run in the account's Delta environment, found by its name
  (`Setup.environmentName`: "Delta" and a short hash of the setup script,
  the command center's address and its token) and made when there is none.
  A made one has the setup script as `init_script`, the CLI's login as
  `environment` variables, and `allowed_hosts: ["*"]`, which is "Full"
  network access in the web app, so the sandbox can run `delta` against
  the command center and reach anything else. There is no OAuth call to
  change an environment, so changing the script, the address or the token
  makes a new one; the old one is left in the web app to archive. The
  cloud keeps the setup script's result for later sessions (for about a
  week), so a new `delta` release reaches new sessions only after that.

`Transcript` keeps only what a person reads: client `user` messages (echoes
of our own matched by uuid), top-level `assistant` text and `tool_use` lines,
`result` notes when a turn ends badly, and worker `can_use_tool` requests.
Subagent output, tool results, logs and control chatter are dropped. Extend
`DataTests` when you change this.

## Storage

`Store` writes `settings.json` (the command center login, owner-only; an
older install's cloud login under `cloud` or `claude` is read once to hand
it over), the `bundled-center.txt` revision marker, `open.txt`,
`chats.json`, `sessions/<session id>.json`, `limits.json`, `queue.json` and
`downloads.json`. Session ids
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
  `EventSource`, `Cloud`, `Center`) and `data/` (`Entry`, `Transcript`, `Store`, `Chat`,
  `RemoteFile`, `Setup`, `Attached`, `Carry`, `Queued`, `Folders`). Tests in `src/test` run via `:core:selfTest` with
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

There is no in-app cloud sign-in, by design: the cloud logins are on the
command center (see Cloud accounts). `android/app/build.gradle` bakes in
only the command center (`-Pdelta.center`, `../delta-center.json`, the CLI's
`~/.config/delta/config.json`), into a generated `Defaults.java` under
`android/app/build/` with a revision marker. A build without one, such as a
CI build or one made in an agent's sandbox, installs over a configured copy
and keeps its login. Never print, commit, or attach the config files, the
generated source, or any APK.
