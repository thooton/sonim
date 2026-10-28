# Launcher

## Purpose

Launcher is a small app drawer for the user's own sideloaded XP3 apps. The
user opens it with the programmable PTT key, picks an app with the d-pad and
presses center to start it, one-handed, with no touch. It sits alongside
Sonim's stock launcher; it does not replace it.

It must stay tiny and instant: one screen, a 3-column grid, open the moment
PTT is pressed. It is an ordinary LAUNCHER activity, never a HOME app. It
requests no permissions, uses no network, runs no services and keeps no
analytics. It is built by `launcher/build.sh`, not Gradle, and has no
dependencies. It must never grow into a home screen, a widget host, a
search tool or a settings app; new features are judged against "pick an
app and go".

## Not wanted

- Registering as Home (the HOME category), or taking over telephony, Wi-Fi
  or quick settings.
- Remembering the last selection between openings: it was tried (Launcher,
  Oct 12) and reverted at the user's request. Every opening selects the
  first app.
- Turning off the window open/close animation: tried, and the drawer
  "appeared abruptly"; the animation came back.
- Permissions, network access, background services, Gradle machinery.
- Any text telling the user how to use it (DESIGN.md rule 7): no hints,
  no "Press Menu to...".
- Uninstalling apps. Remove only takes an app out of the drawer.
- A new signing key: `launcher/signing/app-drawer.p12` must sign every build.

## Screens

### Drawer (the only screen)

A white page, black status and navigation bars, and a grid of apps in 3
columns, each cell 76dp tall: icon (40dp at mdpi) above a one-line black
13sp label, centred. No header, no softkey bar, no counter.

Contents, in order: the saved list, or, before anything was ever saved,
the defaults: Todo, Charge, Flashcards, List, Dictionary.

- Each entry shows the app's own icon and label as installed now. If the
  app's label is empty, its saved label is shown.
- An entry whose app is not installed stays in its place, dimmed (icon and
  label at about 45% opacity), with the generic Android icon and its saved
  label.
- If the exact activity is gone but the package is installed, the entry is
  not dimmed and launching starts the package's main activity.
- Long labels are cut to one line with an ellipsis; the cell never grows.
- An empty drawer shows "No programs", centred.
- More apps than fit (about 3 full rows below the status bar) scroll; the
  selection always stays on screen.

Selection: a lavender (`#9599E4`) 2dp outline on a faint lavender wash
around the whole cell.

- On opening (from PTT, from the app list, or on returning from an app
  started here) the first app is selected, every time.
- Up/Down/Left/Right move the selection one cell. Up on the first row and
  Down on the last row stay put (Down onto a short last row lands on its
  last app); Left in the first column and Right in the last column (or on
  the last app) stay put.
- Center (or Enter) on an app starts it, as a new task. The drawer stays
  behind it; Back from that app returns to the drawer with the first app
  selected.
- Center on a dimmed app, or on one that refuses to start, shows a short
  toast "<Label> is not installed" and nothing else happens. It never
  crashes.
- Back closes the drawer. It does not appear in Recents.
- Menu (short press, on release), or a long press of center on an app,
  opens the program menu for the selected app.
- Package installs, updates and removals while the drawer is open refresh
  the grid: icons and labels change, dimmed apps light up and vice versa.

### Program menu

A stock context menu. Its title is the selected app's label (or "Launcher"
when the drawer is empty). Items, in order:

- **Add program**: always there.
- **Move program**: only when an app is selected and the drawer holds at
  least two apps.
- **Remove program**: only when an app is selected.

Back dismisses it with nothing changed.

- Remove program takes the app out at once, with no confirmation, and
  saves. The app now in its place is selected (the new last app if it was
  last). Removing the only app leaves "No programs". Nothing is
  uninstalled.

### Add program

A stock list dialog titled "Add program": every launchable app on the
phone that is not already in the drawer, excluding Launcher itself, sorted
by label in the phone's collation (case- and accent-insensitive). An app
with no label is listed by its package name.

- Up/Down move through the list; Center adds that app to the end of the
  grid, saves, and selects it (the grid scrolls to it if needed).
- Cancel or Back closes the dialog with nothing changed.
- If every launchable app is already in the drawer, no dialog opens; a
  toast says "No other programs to add".
- An app with several launcher activities shows one row per activity; each
  can be added separately.

### Moving an app

Move program lifts the selected app: its cell gets a solid lavender card
(lavender fill, 2dp outline, 6dp corners) and the d-pad now carries it.

- Left/Right carry it one slot back/forward through the list order
  (wrapping across rows); Up/Down carry it one row (3 slots). The other
  apps shift over to make room, and the grid scrolls with it.
- Past either end it stops: carried past the first slot it sits first,
  past the last slot it sits last. Down from a short last row puts it last.
- Center, Enter or Menu puts it down there (on key release, so the same
  press neither launches the app nor opens the menu) and saves the order.
  The app stays selected.
- Back puts it back where it started, with the order before the move; that
  app stays selected.
- Leaving the drawer mid-move (Home, PTT to another app, screen off) keeps
  the app where it was carried and saves.
- Long press of center does nothing while moving. Package changes during
  a move are applied after the drop.

## Behaviour

- **Data**: the drawer's list lives in private SharedPreferences
  (`drawer`, key `targets`) as a JSON array of
  `{package, class, label}`, the label being what the app was called when
  added. It is written on every Add, Remove and kept Move. Before the first
  save the built-in defaults are used. Changing the defaults in code
  affects only installs that never saved.
- **Survives**: app updates (`adb install -r` or the Updater) and restarts
  keep the list and order. Clearing data or uninstalling restores the
  defaults. Selection never survives: the first app is selected each time.
- **Corrupt data**: unreadable saved JSON falls back to the defaults
  instead of an empty or broken drawer.
- **Speed**: icons and labels are read once per process and cached; the
  cache empties on any package change. Opening from PTT should show the
  grid with no visible delay beyond the window animation.
- **No network, no permissions, nothing timed.** Backups are off
  (`allowBackup="false"`). Portrait only; it does not restart on keyboard
  or orientation changes. One instance (`singleTask`): pressing PTT while
  it is open reselects the first app.
- **Versioning**: `android:versionName` and `android:versionCode` in
  `launcher/AndroidManifest.xml`; it is published as `AppDrawer.apk`
  renamed to `launcher.apk` via `DIRS` in `.github/manifest.py`.

## Look

Launcher does not follow DESIGN.md's family look. It deliberately copies
the stock Sonim drawer: white background, black status and navigation
bars, 3 columns of 76dp cells, black 13sp labels, and the stock lavender
`#9599E4` for focus and for the moving card. Its dialogs and menus are the
stock Material Light ones, not `FamilyDialog`/`FamilyPopupMenu`. Its icon
is a deep indigo rounded square with a 3x3 grid of tiles, the first lit
lavender.

## Open questions

1. The default "Todo" entry points at `com.todo.xp3/com.todo.xp3.MainActivity`.
   That package is now Canvas, whose activity is `com.canvas.xp3.MainActivity`,
   so on a fresh install the first slot falls back to the package launch:
   it opens Canvas but reads "Todo" with the generic icon. Should the
   default be Canvas (`com.todo.xp3/com.canvas.xp3.MainActivity`), and is
   the default list still the right five?
2. The saved label is never refreshed after an app is renamed, so an
   uninstalled app shows its name from when it was added. Is that fine?
3. Remove program has no confirmation and no undo. Wanted, or should it ask?
4. The toast for an app that is installed but refuses to start (a
   SecurityException) still says "is not installed". Should it say
   something else?
5. With an empty drawer the menu's title is "Launcher", which names the
   app (DESIGN.md rule 8). Should the menu have no title there?
6. Should Launcher keep the stock-Sonim look, or move to the family look
   (`FamilyDialog`, focus cards) like the other light apps?
