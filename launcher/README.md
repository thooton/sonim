# App Drawer

A tiny, standalone Android 8.1 app drawer for the Sonim XP3800. On first launch it
shows:

- Todo (`com.todo.xp3/com.todo.xp3.MainActivity`)
- Charge (`com.teslacharge.xp3/com.teslacharge.xp3.MainActivity`)
- Flashcards (`com.flashcards.xp3/com.flashcards.xp3.MainActivity`)
- List (`com.list.xp3/com.list.xp3.MainActivity`)
- Dictionary (`com.dictionary.xp3/com.dictionary.xp3.MainActivity`)

The list can be changed on the device (see [Customize](#customize)).

It deliberately does not register as a Home app and requests no permissions. The
layout copies the stock Sonim launcher's 3-column, 76 dp grid, icon sizing, labels,
white background, and lavender D-pad selection.

## Build

```sh
./build.sh
```

The signed output is `AppDrawer.apk`. Intermediate files are built in a temporary
directory and removed automatically. The local signing key is retained in
`signing/app-drawer.p12`, so later builds can be installed as updates.

## Install and assign

```sh
adb install -r AppDrawer.apk
```

Then choose **App Drawer** as the application assigned to the programmable PTT
key. The first app (Todo by default) is selected when the drawer starts; while
it stays open in the background, reopening it keeps the selection where you left
it. Use Left/Right and the D-pad center key to launch an app. If a target package is absent, its dimmed
slot remains visible and selecting it shows a short error instead of crashing.

## Customize

Press **Menu** (or long-press the D-pad center key on an app) to open the
drawer's context menu:

- **Add program** lists every launchable app installed on the device that is
  not already in the drawer. Choosing one appends it to the end of the grid.
- **Remove program** removes the currently selected app from the drawer. It does
  not uninstall anything.

The customized list is stored in the app's private preferences and survives
updates installed with `adb install -r`. Clearing the app's data (or
uninstalling it) restores the default list above.
