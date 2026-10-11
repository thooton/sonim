# AGENTS.md

## Purpose and scope

A deliberately small, dependency-free Android todo list for the Sonim XP3,
with a Claude chat assistant that can organise the list. It is a sibling of
`../todo` and shares its constraints and much of its code (HTTP, JSON, Claude
OAuth and transport, chat UI).

- Target: 240x320 mdpi, portrait, d-pad/keypad-driven. Not touch-first.
- Platform Android only: no AndroidX, Material, Compose, or third-party
  libraries. `:core` is plain Java, `--release 8`, and must run on API 27.
- One model, `claude-opus-4-6` (`Config.MODEL`), over a Claude Pro/Max OAuth
  login. No other provider.
- The assistant's whole capability surface is `ListTools`: `get_list` and
  `set_list`. Do not add web access, URLs, files, or other tools incidentally.

## The list write rule

Enforced in code (`ListTools`, `Checklist`, `Agent`), not in the prompt:

- `set_list` is refused until the model has *seen* a `get_list` result in the
  current conversation. A read only counts after its round ends
  (`ListTools.endRound`), so a set issued alongside its get is refused.
- Every edit, from the person or the model, bumps `Checklist.version`.
  `set_list` is a compare-and-replace against the version last seen; a
  mismatch is refused with an instruction to call `get_list` again.
- A successful `set_list` counts as a read of what it wrote.
- Any history reset (clear, rewind, stop, compaction restore, relaunch)
  calls `ListTools.forget`, and an in-flight turn whose history was replaced
  cannot run tools at all (`Agent.execute` checks under the same lock as
  `reset`).

Change these together and extend `NetTests` when you do.

## Layout

- `android/core`: `data/` (`Item`, `Checklist`, `Store`, `ChatTurn`,
  `ChatHistory`) and `net/` (`Http`, `Json`, `Config`, `ClaudeAuth`,
  `ClaudeModel`, `Agent`, `ListTools`). Tests in `src/test` run via
  `:core:selfTest` with the tiny `Check` harness and loopback `TestServer`.
- `android/app`: `MainActivity` (tabs, keys, list popup, chat), `Backend`
  (the only bridge to disk/network; owns the shared `Checklist`),
  `ItemAdapter`, and the chat views copied from Todo.

## UI rules

- Lists act on OK key *release*; Sonim reports OK as `DPAD_CENTER` or
  `ENTER`. Text dialogs and the chat composer act on the *press* (T9 eats the
  first release); `consumedOkDownTime` stops that release toggling the item
  underneath.
- Menu (on its release), or holding OK, opens the platform's stock context
  menu at what it acts on (as the stock Music app does):
  `registerForContextMenu(content)`, `onCreateContextMenu` /
  `onContextItemSelected`, opened with `showContextMenu(x, y)` on the
  *ListView* at the row's point, never on the row itself (the popup scrolls
  its anchor's parent to fit). Opening on the Menu press lets the release
  land on the popup, which closes it. Passing a point is what makes Android
  draw it as a popup beside the item instead of a centred dialog; never call
  the no-argument `showContextMenu()`. Rows are rebuilt on every opening.
  List tab: Insert above, Insert below, Edit, Move up, Move down (each only
  where possible), Delete (or Add item when empty).
  Chat tab: a highlighted message's Copy text / Rewind to here first, then the
  chat-wide items. OK presses are claimed by `heldForMenu` so ListView's own
  long press (a dialog) never runs and a hold's release never ticks the item.
  Actions re-locate the chosen item, since the assistant may have rewritten
  the list while the menu was open.
- Left/right switch tabs except while the caret can move inside a draft.

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
`../claude-auth.json` (or `-Plist.claudeAuth=...`) into a generated
`Defaults.java` under `android/app/build/`; the app adopts each new bundle once
and saves every token rotation. Never print, commit, or attach the auth file,
the generated source, or any APK.
