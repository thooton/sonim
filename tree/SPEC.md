# Tree

## Purpose

Tree is the phone's front end for the command center's nightly QA. From it
the user can:

1. See the latest run: its nightly report, or while a run goes, its
   progress app by app.
2. See every app the QA goes through (phone and tablet): its name, whether
   it grows, its open findings, those left for the user, grown features
   waiting for a verdict, and whether it has a spec yet.
3. Open an app: switch Grow on or off, run the QA now for that app alone,
   and read its findings newest first.
4. Open a finding: its kind, title, status, reason, detail, commit and
   screenshots; for a grown feature, give it 👍 (keep) or 👎 (take out), or
   clear the verdict. A 👎 has the next run take the feature out.
5. Run the QA now for every app, and cancel a run that is going.
6. Turn the daily run on or off and choose its hour (0 to 23, in the
   command center's time zone, which is shown and not changed).

## Not wanted

- No touch: everything works with the d-pad, OK, Menu (left soft key), Back
  (right soft key) and the keypad.
- No editing of findings, specs or code; no starting or reading agent
  sessions; no changing the time zone.
- No text that explains how to use a screen ("Press Menu to..."), and no
  header that names the app.
- No notifications, background work or polling while Tree isn't on screen.
- No third-party libraries.

## Screens

Every screen has a dark green 36dp header: on the left where you are or how
things stand, on the right a short fact. A thin green bar under the header
moves while anything is being fetched. On every screen Menu or the left
soft key opens the screen's menu, a popup under the header's right end, and
Back or the right soft key goes back (on Home, leaves the app).

### Home

- Header left: how the latest run stands: `Running · 2 of 12` (apps done or
  skipped, of the run's apps), `Running · refactoring`, `Done 3:41 AM`,
  `Cancelled Yesterday 2:10 AM`, `Done Oct 27`, or `No runs yet`. Empty
  before anything has been fetched. Header right: `Daily at 1 PM` or
  `Daily run off`.
- The list: first the latest run (the one going, else the newest), then
  every app in the order the command center sends them.
  - The run's row, while it goes: the app being worked on in green Medium
    (`Refactoring` in the refactor stage, `Starting` before any app), and
    under it `Run by you` or `Scheduled`, `since <time>` and `2 of 12 apps`.
  - The run's row, once over: the summary's first line in Medium, and up to
    four of its further lines muted (or `Scheduled` / `Run by you` when the
    summary is one line). A run with no summary shows `Cancelled` (or `No
    summary`) and its error.
  - An app's row: its name (its directory capitalised: `list` is `List`) in
    Medium, `phone` or `tablet` small on the right, and under it, joined by
    ` · `: its place in the run going now if it isn't finished (`Running` in
    green, `Waiting` muted), `Grows` if growing is on, `No spec yet` if it
    has none, `N open`, `N left for you`, `N grown`; or `Nothing open` when
    all three are zero and it has a spec. `left for you` and `grown` are in
    the green accent.
- Keys: Up/Down move the focus card. OK on the run opens **Run**; OK on an
  app opens **App**.
- Menu: `Run QA now` (when no run is going) or `Cancel run` (when one is),
  `Refresh`, `Turn daily run off` / `Turn daily run on`, `Run hour…`,
  `Command center…`. Without a login, only `Command center…`.
  - `Run QA now` starts a run of every app and toasts `Running QA on every
    app`.
  - `Cancel run` asks `Cancel this run?` with `Cancel run` and `Keep going`;
    cancelling toasts `Cancelled`.
  - `Turn daily run off/on` toasts `Daily run off` / `Daily run on`, and the
    header's right follows.
  - `Run hour…` lists `12 AM`, `1 AM` ... `11 PM` titled `Run hour,
    <time zone>`, the current hour checked; choosing another toasts `Daily
    at <hour>`. Cancel or Back closes it unchanged.
  - `Command center…` asks for the address (`https://…`) and the token
    (dots when one is saved; left empty, the saved one is kept). Save
    toasts `Saved: <host>`; an address that isn't https, or a missing part,
    toasts why and saves nothing, and the dialog stays open with what was
    typed. Up/Down move between the fields and the buttons; OK in the
    address moves to the token, and OK in the token saves.
- With nothing to list: a spinner during the first fetch; otherwise a
  centred title and a button that has the focus: `No command center` with
  `Sign in` (opens the login dialog), `Couldn't reach the command center`
  with the reason and `Try again`, `Couldn't list the apps` with the
  command center's reason and `Try again`, or `No apps yet` with `Try
  again`.

### Run

- Header left as on Home for this run; right `Scheduled` or `Run by you`.
- A full-width button, drawn focused: `Cancel run` while this run goes,
  `Run QA now` once it is over, or greyed `Another run is going` when it is
  over and another one goes.
- Under it, on white cards: `Started` and (once over) `Ended` times;
  `Summary` with the whole summary; `Apps`: each app's name and its tag
  (small and muted after the name, as on Home), its
  status on the right (`Done` green, `Running` accent, `Waiting` and
  `Skipped` muted) and the reason under it if there is one; `Jobs`: each
  as `Explore · List` with its status on the right (`Done` green, `Running`
  accent, `Failed` red, `Cancelled` muted) and its summary under it. A
  run's error shows in red under the button.
- Keys: Up/Down scroll 48dp a press. OK does what the button says (`Cancel
  run` asks first, as on Home; when greyed it toasts `A run is already
  going`). Menu: the button's action and `Refresh`.
- After `Run QA now` the screen shows the new run.

### App

- Header: the app's name, and `phone` or `tablet`.
- Rows, top to bottom:
  1. `Grow` with `On` (green Medium) or `Off` (muted). OK switches it and
     toasts `Growing on` / `Growing off`.
  2. A full-width button: `Run QA now`, which starts a run of this app only
     and toasts `Running QA`. While any run goes it is greyed and reads
     `Running` or `Waiting` (this app's place in it) or `A run is going`,
     and OK does nothing.
  3. A muted line, not focusable: `Spec written` or `No spec yet`,
     `explored <when>` or `never explored`, and `last run: <status>` with
     its reason if any.
  4. The findings, newest first, at most 100: the title in Medium (up to
     two lines), and one line under it: the verdict's 👍 or 👎 if given,
     the kind (`Bug`, `Problem`, `Inconsistency`, `Feature`), the status
     (`Open`, `Fixed`, `Left for you`, `Declined`, `Grown`, `Reverted`) in
     Medium, and the reason, cut to the line. Status colours: Fixed green;
     Left for you and Grown without a verdict green accent; Open dark;
     the rest muted. With none: `No findings yet` (`Loading findings`
     first, or `Couldn't read the findings` and why).
- Keys: Up/Down move between Grow, the button (passed over while it is
  greyed) and the findings. OK on a finding opens **Finding**.
- Menu: `Turn growing on` / `Turn growing off`, `Run QA now` (only when no
  run is going), `Refresh`.
- The findings are fetched again each time the screen comes back.

### Finding

- Header: `<App> · <Kind>`, and the status on the right.
- A card with the title (16sp Medium), the status in its colour, and
  `Found <when>`.
- For a grown feature only: two buttons side by side, `👍 Keep` and `👎 Take
  out`; the verdict given carries a tick (`✓ 👍 Keep`). The `👍` button
  has the focus on opening.
- Cards, each only when there is something in it: `Why` (the reason),
  `Detail` (the whole detail, its lines kept), `Commit`, and `Screenshot
  1`, `Screenshot 2`... each showing its picture scaled to the card's
  width (`…` while it loads, or why it couldn't be shown).
- Keys: Up/Down scroll 48dp a press. On a grown feature Left/Right move
  between the two buttons; OK gives that verdict (toasts `Kept` or `The
  next run takes it out`), and OK on the one already given clears it
  (toasts `Verdict cleared`). While the buttons are scrolled out of view,
  OK gives no verdict: it scrolls back to them.
- Menu (a grown feature only): `👍 Keep`, `👎 Take out`, `Clear verdict`.

## Behaviour

- Signing in: a build made where the delta CLI is signed in carries the
  command center's address and token and uses them from the first launch.
  Otherwise the first launch opens the login dialog. Only https addresses
  are taken.
- Home shows the last fetched QA at once (kept on the phone), and fetches it
  again on launch and whenever it comes back after more than a minute.
  `Refresh` on Home has the command center list the apps from GitHub again.
- While a run goes, Home and Run ask again every 20 seconds while they are
  on screen.
- Any failure shows the command center's own message (`A run is already
  going.`), `Token refused` for a refused token, or `Offline`; a failure
  while something is already on screen is a toast (on Run, a red line under
  the button), and what is on screen stays.
- If the command center sends an error about listing the apps along with
  the apps it has, Home toasts it.
- Screenshots are fetched once and kept in the app's cache.
- Times are in the phone's time zone: today `3:41 AM`, `Yesterday 3:41
  AM`, this year `Oct 27`, before that `Oct 27, 2025`.

## Look

The family look (DESIGN.md) in a forest green: header and status bar
`#17301C` with white text and `#AECDB4` for the right-hand fact; accent
`#2D7337` for the focus card's outline, the main button, the app being
worked on and what waits on the user; focus wash `#E8F3E9`; page `#EEF3EF`
behind white cards on Run and Finding; text `#1A1D1A`, muted `#5E6A60`;
fixed and done `#1E7B34`, failed `#B3261E`. Text at 10dp from the edge;
Roboto Medium for what leads; 15sp header, 13.5sp lead, 11.5 to 12sp what
follows. The focus is a rounded card 4dp in from the sides. The icon is a
pale round tree crown with a tick, over a white trunk, on the accent.

## Open questions

- `devices` in `GET /qa` is not shown; it isn't clear what the user should
  do about it.
- Jobs are only listed on the Run screen; there is no link from a finding
  to the job that filed it.
- Apps are in the order the command center sends them, not grouped by
  phone and tablet.
- A grown feature's verdict can be changed any number of times before the
  next run; whether the app should stop that once a run has started is
  open.
