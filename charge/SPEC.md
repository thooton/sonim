# Charge

## Purpose

Charge is the user's own Tesla Supercharger client for the Sonim XP3 flip
phone: find a nearby Supercharger, see its live price and open stalls, start
a paid charging session at a chosen stall, watch it (in the app and in the
notification shade), stop it, and look back at past sessions and what they
cost. It is driven one-handed with the d-pad, OK, Back, Clear and Menu on a
240x320 screen; touch is never needed.

It must stay small, quick and trustworthy with money: every figure on screen
is either live from Tesla or plainly marked as missing or stale, and nothing
that costs money happens without a confirmation. It is a single-user,
sideloaded app with one Tesla login baked into the build. It must never
become a general EV-charging map, a car remote (climate, locks, trips), a
multi-account client, or an app that needs a companion server.

## Not wanted

- No text telling the user how to use the app: no "Press OK to…", no
  "Start one from…", no second line under an empty state saying what the
  screen is for (DESIGN.md rule 7). Confirmation dialogs carry a title and
  buttons, and a message only for facts (stall, card, hold).
- No heading repeating the tab's name; the tab bar is the title.
- No permanent search field on Discover; Find opens a dialog.
- No focusable tab bar, touch-only controls, marquees, or Material-sized
  widgets that push the main action off screen.
- No caching of live availability, prices or account state across launches;
  favorites are bookmarks only.
- No second session poller in the Activity; the charging service owns it.
- No older web implementation, no companion server.

## Screens

Layout: a navy tab bar (Favorites, Discover, Session, History) above a
content area. The open tab is marked. The app opens on Favorites.

### Keys everywhere

- Left / Right: previous / next tab, wrapping (Favorites ← History). Only the
  first press counts; holding the key does not cycle tabs. In a text field
  with text, Left/Right move the cursor, and switch tabs only at either end.
- Up / Down: move focus or the list selection.
- OK (centre, Enter, numpad Enter): activate the focused button or open the
  selected row.
- Back or Clear on a detail screen: return to that tab's list (focus on its
  content). Back on a tab's top level leaves the app. Clear does nothing in a
  text field (it deletes there).
- Menu: a dialog titled Menu with "Refresh this page", "Search radius: N
  miles" and "Account info", on every screen.

### Lists (Favorites, Discover results, History)

- The selected row is drawn highlighted; on entering a list the first row is
  selected.
- Down on the last row stays there; Up on the first row stays there (on
  Discover it moves focus up to Find).
- Selection moves through visible rows first; at the edge the rows scroll
  under a fixed highlight, one row per press.
- Site rows: street address (or Tesla's site name if none) bold; city line
  beneath (hidden if unknown); then "4.2 mi · 3/8 open" (distance only in
  search results). "0/8 open" is red, a quarter or less open is amber. No
  live count shows "8 stalls" or "Availability unknown". Right column: price
  for the site's current local hour, e.g. "$0.48" over "/kWh"; "-" in grey
  when unknown.

### Favorites

- No favorites: "No favorites yet" centred, and a focused "Go to Discover"
  button that opens Discover.
- With favorites: on every app open (resume) all are re-fetched from Tesla,
  at most five at a time; a spinner fills the screen until every one has
  answered, then the list shows. Stored favorites never show an old price or
  stall count.
- If some fail: a red line above the list, "1 favorite could not be
  refreshed" / "N favorites could not be refreshed"; those rows show "-" and
  no live count.
- Order: the order they were added.
- OK opens the charger detail.

### Discover

- Top: a filled "Find" button (focused on entry), a grey status line, a
  divider, then results.
- Status line: "Within 12 miles" before any search; "Searching for
  chargers…" while searching; "N chargers near <what was typed>" after;
  "No chargers found" for none; the error message on failure. One line,
  never wraps.
- Find opens a dialog "Find Chargers" with a single-line field (hint "ZIP
  code or address", prefilled with the last query), Find and Cancel. Find
  or the keyboard's Enter with an empty field shows "Enter a ZIP code or
  address." on the field and keeps the dialog open. The cursor starts at the
  end of the prefilled query, so Clear erases it. Cancel / Back closes it.
  "lat, lng" (e.g. "33.4, -112.0") is accepted as a location.
- After a successful search focus moves to the first result, nearest first.
  Down from Find also enters the list when it has rows.
- Results persist while switching tabs during the run of the app. A search
  still running when the user switches away keeps going; coming back shows
  "Searching for chargers…" and then its outcome.
- OK on a result opens the charger detail.

### Charger detail

A scrolling page; focus starts on the page itself (not a button), so Down
scrolls through the facts before reaching the buttons. A spinner shows while
it loads; a failure shows a dialog "Could not complete request" with the
message, OK to dismiss.

In order:
- Street address heading (site name if none), city line, divider.
- "Starting charging…" / "Stopping current session…" in red while either
  request is in flight.
- Price: current local-hour price, e.g. "$0.48/kWh", or "Unavailable".
- Stalls: "3 of 8 open", or "8 total" when live counts are missing.
- Max power: "250 kW" (only when known).
- Red lines for an outage notice, critical info, and "Paid parking may
  apply. …" when Tesla reports them.
- ACCESS: access instructions, when present.
- PRICING (SITE LOCAL TIME): bands of equal price, e.g. "00:00–06:59
  $0.31/kWh"; hidden when Tesla gives no hourly schedule.
- OTHER FEES (idle, duration, session fees) and AMENITIES ("Wi-Fi · Food"),
  each only when present.
- "Add favorite" / "Remove favorite": toggles, toasts "Added to Favorites" /
  "Removed from Favorites", relabels itself, and the Favorites tab reflects
  it at once.
- Primary button, one of: "Start charging" (enabled), "No available stalls",
  "Session already in progress", "Starting…", "Stopping current session…"
  (all disabled). It is enabled only with no session running, at least one
  available stall, and an active price book.

Starting a charge (real money; never press Start in QA):
1. "Start charging" opens "Choose your stall": available stalls only, as
   "3A  ·  250 kW", plus Cancel.
2. Picking one opens "Start charging session?" with "» Stall 3A", "» Billed
   to Visa ending in 1234" (once the card is known; it appears in the open
   dialog when it arrives) and "» $X.XX pre-authorization requirement" when
   Tesla sets a hold. Buttons: Start, Cancel.
3. Start: the button reads "Starting…"; the app re-reads the site, checks
   the stall is still free and the price book active, runs Tesla's
   pre-start check, then starts. Success opens the Session tab. Failure
   shows the error dialog (e.g. "Stall 3A is not available. Pick another
   stall.") and the detail returns to "Start charging".
- Only one start or stop request can be in flight; a second press toasts
  "A charging request is already in progress."

### Session

- No session: "No active charging session" centred, and a focused "Check
  again" button that asks Tesla again. If the app could not ask (no
  connection, sign-in expired), it says "Could not check for a charging
  session." with the reason instead, whenever the tab is shown.
- With a session, a scrolling page:
  - Banner: status in large bold text: "Authorizing at the stall…",
    "Ready, waiting for plug in", "Plugged in, starting up…", "Charging"
    (green), "Ending session…", "Unplug to finish" (red), "Session
    cancelled", "Session complete", or "Stopping…" (red) while a stop
    request is in flight. Under it the street, then "City · Stall 3A".
  - "Lost contact with Tesla" in red when the last poll failed; the figures
    below are the last ones received.
  - Once charging (or energy has been reported): Power "11.3 kW", Energy
    "4.27 kWh", Cost "$1.84", Battery "62% · 25m to 80%" (each part only
    when known). Before that: Rate "$0.48/kWh" when known.
  - After the session ends: "Congestion fee" and "Total" rows, only if a
    congestion fee was charged or waived.
  - "Charging has stopped" in red while waiting for unplug.
  - Primary button "Stop charging" (while charging) or "Cancel session"
    (before charging), absent once the session is ending or over. It opens
    "Stop charging?" (Stop / Keep session) or "Cancel session?" (Cancel
    session / Keep session). While the stop is in flight it reads
    "Stopping…" and is disabled.
- The page updates every ~4 s while the app is open on any screen (the
  service's readings); no key press needed.
- If the app opens with a session already running (started elsewhere), the
  Session tab shows it.

### History

- Spinner on first visit; then past sessions, newest first.
- Row: street address (or Tesla's site label), "Oct 12, 3:45 PM" (+ " ·
  Unpaid" when unpaid), "1h 23m · $0.48/kWh · 62.5 kWh" (missing parts
  dropped), and the total on the right, e.g. "$29.70".
- Empty: "No charging sessions yet". Load failure: "Could not load your
  charging history." with the reason under it, and a focused "Try again".
- Paging: when the selection is within two rows of the last loaded session
  the next page loads; the footer row reads "Loading more…" (with spinner),
  "Load older sessions", or the error text (OK retries). The footer
  disappears at the end. Appending never moves the selection or the rows.
- OK on a session opens its receipt (a spinner first, the first time a site
  is looked up in a run).

### Receipt (history detail)

- Heading: street (or site label), then city (or the label), divider.
- Started, Ended ("Oct 12, 3:45 PM"), Charged for ("1h 23m"), Plugged in
  after (only if idle 5 min or more), Energy "62.50 kWh", Cost per kWh.
- CHARGES: each fee that is non-zero ("Waived" if waived to zero).
- TOTAL: "Total paid $29.70" or "Payment pending $29.70".
- "Open this charger", only when the site could be matched: opens that
  charger's live detail. Back from there returns to the History list.
- Focus starts on the page, as on the charger detail.

### Menu items

- Refresh this page: re-fetches what is shown: the open detail or receipt,
  the active session, all favorites, history from page one, or re-runs the
  last search on Discover.
- Search radius: "5 miles", "12 miles" (default), "30 miles", "60 miles".
  The choice applies to the next search and to the status line; it resets to
  12 when the app is restarted.
- Account info: a dialog with a spinner, then:
  "-- Payment --" (card brand and last four, "Expires 07/2028" or
  "Expired…", or "No card on file"), "-- Balance --" ("$0.00 owed" or "$x
  owed across N sessions"), "-- Vehicle --" (name, plate, range, "NACS
  adapter registered" / "No NACS adapter registered", or "No vehicle
  registered in the Tesla app"), and "-- Sign-in --" ("Valid for N more
  days", "Expires in N days", "Expires tomorrow/today", "Expired"). Each
  block shows its own error instead if only it failed; a total failure
  shows "Your account could not be loaded." and the reason. OK closes it.
  Re-read every time it opens.

## Behaviour

- Network: Tesla's API over HTTPS for everything; address search through
  Google geocoding when the build has a key, otherwise OpenStreetMap
  Nominatim. No other server.
- Login: a Tesla refresh token baked into the build; refreshed and rotated
  automatically. A build without one (CI and QA builds) answers every Tesla
  request with "This build does not contain sign-in credentials." That must
  show as the normal error states above, never a crash or endless spinner.
- Errors never replace data already on screen except where stated: a failed
  search sets the status line; a failed detail shows the dialog (Back then
  returns to the list); a failed session lookup with no session known shows
  in place on the Session tab; with a session on screen it shows the dialog
  only on the Session tab, and is silent elsewhere.
- Error wording: a login Tesla rejects reads "Tesla sign-in has expired."
  (it will not recover by itself); no signal reads "No connection." and a
  stalled request "The connection timed out." (try again later).
- Stored on the phone: favorites (identity and address only), the login,
  and the stall/site context of sessions this phone started. Survives a
  restart: favorites, login. Not kept: search results, radius, history,
  live figures.
- Charging service: starts when a session is known to be running and holds
  an ongoing, silent notification titled with the status ("Charging") and a
  line like "62%  ·  4.27 kWh  ·  $1.84" ("  ·  reconnecting…" appended
  after a failed poll; "Connecting…" before the first reading). Tapping it
  returns to the app on the tab that was open. It polls every 4 s while the
  app is in front, every 30 s otherwise, every 8 s after a failure, and
  gives up (notification removed) after 20 failures in a row. The
  notification goes away when the session ends.
- Permissions: internet only; no runtime permission prompts.
- Portrait only; rotation and keyboard changes do not restart the screen.

## Look

- Charge keeps its own look (DESIGN.md exempts it): navy tab bar #0A2A57,
  white rows on a #FAFAFA page, label-left/value-right metric rows, 10dp
  text edge, red #B3261E for warnings, green #1B7A3E only for "Charging",
  amber #B45309 for low capacity.
- State is said in words as well as colour (open counts, "Unpaid", "Lost
  contact with Tesla", "Stopping…").
- On a detail screen the primary action is the last thing on the page; the
  session screen's Stop button fits on the 320px screen without scrolling
  while charging.

## Open questions

1. Changing the search radius on Discover redraws the old results under
   "N chargers near …" without searching again, so the list no longer
   matches the radius until Refresh or a new Find. Should a radius change
   re-run the last search?
2. A finished session stays on the Session tab ("Session complete", no
   button) until Menu > Refresh. Should it clear itself, or offer "Check
   again"?
3. History paging responses carry no generation check: a Refresh while an
   older page is loading could append that page to the new list. Intended
   to be guarded like the other requests?
4. On the Session tab with a session, nothing explicitly takes focus, so
   which control Up/Down/OK act on first depends on the platform. Should
   focus start on the page (as on details) or on Stop?
5. The search status line is one line, so long errors (geocoder messages)
   are cut off. Acceptable, or should errors get a second line?
6. When a charger detail fails to load, the error dialog closes onto the
   loading spinner, which stays until Back. Should it return to the list
   (or show the error in place) instead?
