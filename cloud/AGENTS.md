# AGENTS.md

## Purpose and scope

A deliberately small, dependency-free Android client for one Claude Code
cloud session at a time, for the Sonim XP3. It is a sibling of `../list` and
`../canvas` and shares their constraints and much of their code (HTTP, JSON,
Claude OAuth, chat views, Markdown).

- Target: 240x320 mdpi, portrait, d-pad/keypad-driven. Not touch-first.
- Platform Android only: no AndroidX, Material, Compose, or third-party
  libraries. `:core` is plain Java, `--release 8`, and must run on API 27.
- One conversation. No session list, no repository picker, no model or
  environment settings: the first `anthropic_cloud` environment on the account
  is used, with an empty sandbox.

## The protocol

This is the protocol behind `claude --cloud` (see `claude_cloud/cloud_demo.py`
beside this repository), in `Cloud`:

- `GET /api/oauth/profile` gives the organization; every other call sends it
  as `x-organization-uuid`. Session create and environment create also send
  `anthropic-beta: ccr-byoc-2025-07-29`.
- `POST /v1/sessions` creates the session with the first message as an initial
  event. Later sends go to `POST /v1/code/sessions/cse_…/events` (the same id
  with `session_` swapped for `cse_`).
- `GET /v1/code/sessions/cse_…/events/stream` is SSE. `client_event` frames
  have the sequence number as their `id:` and a Claude Code stream-json
  `payload`. `?from_sequence_num=N` (plus `Last-Event-ID`) replays everything
  after N, so `Transcript.sequence()` is saved and a reconnect resumes there.
- Permission mode: `bypassPermissions` is refused for cloud sessions
  (`bypass_not_launched`). Every send is preceded by a
  `set_permission_mode: auto` control request, because the worker restarts
  after it has been idle. Do not auto-allow `can_use_tool` requests: that
  would override Auto's own decisions. They are shown, and the person allows
  or denies them from the menu (`Cloud.answer`).

`Transcript` keeps only what a person reads: client `user` messages (echoes
of our own matched by uuid), top-level `assistant` text and `tool_use` lines,
`result` notes when a turn ends badly, and worker `can_use_tool` requests.
Subagent output (`parent_tool_use_id` set), tool results, logs and control
chatter are dropped. Extend `DataTests` when you change this.

## Pictures

Modelled on the stock Messages app (`com.android.mms`, `BMms.apk`), which
on this phone does it like this:

- Capture: `MediaStore.ACTION_IMAGE_CAPTURE` with `EXTRA_OUTPUT` set to a
  content:// URI served by the app's own provider (Messages uses
  `mms_temp_file/scrapSpace`; here `CaptureProvider`, not exported, granted
  per intent). `com.borqs.camera` shoots, asks "Add Image ?", and writes the
  file.
- Pick: `ACTION_GET_CONTENT` `image/*` aimed at
  `com.android.gallery3d/.app.Gallery` with `multipick`, falling back to a
  chooser when that Gallery is missing.

`Pictures` shrinks each one to 1568px on its long side, applies EXIF rotation,
re-encodes JPEG 85, and keeps it in `files/draft/` (so it survives the
activity dying behind the camera). Sending moves the draft to
`files/sent/<uuid>-<n>.jpg`, and `Cloud` sends each picture as a base64
`image` block before the text. `Entry.pictures` records only a count. The
adapter draws the files for messages sent from this phone and a `[Picture]`
placeholder for any others. New session deletes `sent/`. At most four per
message.

## Layout

- `android/core`: `net/` (`Http`, `Json`, `Config`, `ClaudeAuth`, `Sse`,
  `Cloud`) and `data/` (`Entry`, `Transcript`, `Store`). Tests in `src/test`
  run via `:core:selfTest` with the tiny `Check` harness and loopback
  `TestServer`.
- `android/app`: `MainActivity` (the one screen, keys, menu, camera and
  gallery intents), `Backend` (the only bridge to disk and network), `Link`
  (the reconnecting stream thread), `Pictures` and `CaptureProvider`, and the
  chat views copied from List.

## UI rules

As in List: the composer sends on the OK *press* (T9 eats the first release).
Menu (on its release), or holding OK, opens the stock context menu with
`showContextMenu(x, y)` on the ListView at the row's point, never on the row
itself, and never through the no-argument `showContextMenu()`.

## Build and validation

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

There are no UI automation tests; key, focus, and popup changes need manual
checks on the handset.

## Credentials

There is no in-app sign-in, by design. `android/app/build.gradle` reads
`-Pcloud.claudeAuth=...`, else `../claude-auth.json`, else
`../../../claude_cloud/auth.json` into a generated `Defaults.java` under
`android/app/build/`. The app adopts each new bundle once and saves every
token rotation. Never print, commit, or attach the auth file, the generated
source, or any APK.
