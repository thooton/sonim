# AGENTS.md

## Purpose and scope

A deliberately small, dependency-free Android checklist for the Sonim XP3.
It is a sibling of `../canvas` and shares its constraints.

- Target: 240x320 mdpi, portrait, d-pad/keypad-driven. Not touch-first.
- Platform Android only: no AndroidX, Material, Compose, or third-party
  libraries. `:core` is plain Java, `--release 8`, and must run on API 27.
- No network: the manifest has no INTERNET permission. The Claude chat tab
  was removed; `Store.forgetChat` deletes the login and conversation files
  older versions left behind. Do not bring a network back incidentally.

## The list's version

`Checklist` still bumps a version on every edit and offers a
compare-and-replace (`replaceIf`); they were the guard for the chat
assistant's writes and are kept, tested, for any future second writer.

## Layout

- `android/core`: `data/` (`Item`, `Checklist`, `Store`), over the shared
  `Json` and `Disk` (`../common/core`). Tests in `src/test` run via
  `:core:selfTest` with the shared `Check` harness.
- `android/app`: `MainActivity` (the list, its keys and its popup menu),
  `Backend` (the only bridge to disk; owns the shared `Checklist`) and
  `ItemAdapter`. There is no header: the list starts at the top of the
  screen.

## UI rules

- Lists act on OK key *release*; Sonim reports OK as `DPAD_CENTER` or
  `ENTER`. Text dialogs act on the *press* (T9 eats the
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
  Items: Insert above, Insert below, Edit, Move up, Move down (each only
  where possible), Delete (or Add item when empty). OK presses are claimed by
  `heldForMenu` so ListView's own long press (a dialog) never runs and a
  hold's release never ticks the item. Actions re-locate the chosen item.

## Build and validation

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :list:core:selfTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :list:app:assembleDebug
```

There are no UI automation tests; key, focus, and popup changes need manual
checks on the handset.

## Credentials

There are none: the app has no login and no network.
