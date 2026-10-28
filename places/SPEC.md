# Places

## Purpose

Finds places with Google Maps on the Sonim XP3: pizza in Denver, a pharmacy
near 85281, coffee by Union Station. The user drives it one-handed with the
d-pad, OK, Back, Menu, Call and the T9 keypad; never touch. It also opens
Google Maps links tapped elsewhere (a text message), since the phone's own
Maps is disabled.

It must stay a quick, single-screen finder: type, pick a place, read it,
call it or copy its address. Every Google request is billed, so it asks for
as little as it can: one photo per place, only on its page; search field
masks no higher than Enterprise; reviews and Atmosphere fields only on a
place's page. It must never become a map, a navigator, an account-bound
Maps client, or anything that fetches in the background.

## Not wanted

- Photos in the results list, photo galleries, or more than a place's first
  photo (each photo is billed).
- An app-name bar, a result counter, or any text telling the user how to use
  the app ("Press Menu to...", example input in fields). See DESIGN.md
  rules 7 and 8.
- A button bar or Call/Website chips on a place: OK opens a list of choices.
- Tabs on a place: it is one scrolling page.
- Chips or extra lines in result rows; the picked row does not grow.
- En or em dashes written by the app: hours and price ranges use a hyphen.
- The country at the end of addresses (results, place page, Copy address).
- Keeping the Maps key on disk, or sending searches through the command
  center.
- Following a short link's redirects to anything but Google's own hosts.

## Screens

The app is one activity with two views: Search and a Place page on top of it.
Menu (on release) opens a list of choices on either; each choice has a teal
icon.

### Search

Top to bottom:

- **Header** (42dp): a lead icon, the text field, a round clear (x) button.
  The lead icon is a magnifier before a search, a back arrow once there are
  results, and a teal pin while Near is being edited. The clear button shows
  when the field has text or there are results. The field has a hint and a
  teal cursor, shown only while the header holds the d-pad.
- A hairline; while anything loads, a thin indeterminate teal bar on it.
- **Chips**, in order: Near (pin icon, the Near text or "Anywhere", a
  drop-down arrow, cut with "..." past 130dp), **Open now**, **Top rated**.
  An on chip is filled dark teal with a tick; the chip under the d-pad has a
  pale teal ring.
- **The list**: results, or, before a search, recent searches (a clock in a
  circle, then the search). Rows are parted by hairlines. The picked row is
  on the teal-tinted fill.

A result row: the name (one line); "4.8 ★ (112) · $10-20 · ♿ · Cafe"
(parts missing from Google are left out); the full address without its
country, wrapped; "Open · Closes 9:00 PM · 3.5 mi" with only Open (green)
or Closed (pink) coloured. A line with nothing on it takes no room.
Distance is from the search's Near point; with Near empty there is none.

Distances read "500 ft" under 0.1 mi, "2.4 mi" under 10 mi, "31 mi" beyond.

The d-pad is in one of four zones: header, Near (editing), chips, list.
The app starts in the header with the keyboard up and the field focused.

| Key | Header | Chips | List | Editing Near |
| --- | --- | --- | --- | --- |
| OK | Search what is typed (nothing if empty) | Near: edit it. Open now / Top rated: toggle | Result: open its page. Recent: put it in the header and search it | Save Near; back to the chips, on Near |
| Down | To the chips | To the list, if it has rows | Next row; on the last result, load the next page | Nothing (field only) |
| Up | Nothing | To the header | Previous row; from the first row, to the chips | Nothing |
| Left/Right | Move the cursor in the text | Along the chips; stops at Near and at Top rated | Nothing | Move the cursor |
| Call | Nothing | Nothing | On a result: its number in the dialer | Nothing |
| Back | Stop loading if busy; else clear the results (or message); else leave the app | Stop loading if busy; else to the header | Stop loading if busy; else to the header | Stop loading if busy; else drop the edit (Near back to its saved text), to the chips |
| A letter/digit/Del | Types | Back to the header, and types | Back to the header, and types | Types |

- The keyboard's Search/Done in the header searches; Done in Near saves it.
- Toggling Open now or Top rated, or saving Near, re-runs the current search
  if there is one, and the d-pad stays on the chip.
- A new search shows its results from the first, with the d-pad on the
  first result; with no results it goes to the header and shows
  `No results for “pizza” near Denver` ("near ..." only when Near was set).
- Down on the last result with no next page stays there; while a page
  loads, more Down presses do nothing. When the next page comes, the d-pad
  moves to its first new row if it was on the last.
- The picked row is always scrolled into view.
- With no results and no recent searches the list area is empty.
- Errors show as a centred muted line in place of the list (only while there
  are no results).

**Menu on Search**: Stop (while loading); Search (when the header has text);
More results (when there is a next page); Change where (edits Near); Clear
results (when there are results); Clear recent searches (when there are some
and no results); Test location (temporary, see Open questions); Command
center...

### Place

Opened by OK on a result (or by a Maps link). It shows at once what the
search already knew (name, rating, kind, status, distance, photo, address,
website, phone), then fills in the rest when the details come, without
jumping the scroll position.

Top to bottom:

- **The name**, fixed at the top: 18sp, up to two lines, shrinking to one
  14sp Medium line, with a hairline under it, as the page scrolls.
- Rating "4.3 ★★★★☆ (1,099)" with partly filled stars; "Coffee shop ·
  $1-10 · ♿"; "Closed · Opens 7:00 AM · 3.6 mi".
- One photo, rounded, page-wide, 130dp tall (only when Google has one).
- Rows each after a teal icon: the address without its country; the hours
  (status, then "Mon  7:00 AM - 9:00 PM" a day a line, today in Medium, a
  split day's ranges on separate lines, then secondary hours such as Happy
  hour under their own caption); the website as its host ("avenuebrew.com");
  the phone; "Local time: ..." in muted text. Hours and local time come with
  the details. Without hours, no hours row.
- **Reviews** (with the details, if Google has any): the rating large with
  stars and "1,099 reviews"; Google's review summary in a card marked
  "Summarized by Google's AI"; up to 5 reviews, each with the writer's
  initial in a coloured circle, name ("Someone" if none), stars, how long
  ago and the text.
- **About** (with the details, if any): Google's editorial summary; its AI
  overview in a card; then Service options, Payments, Parking,
  Accessibility as lines with a teal tick (has) or muted cross (hasn't);
  Fuel prices as "Regular ... $3.45".

Times are in the place's own UTC offset, not the phone's.

| Key | |
| --- | --- |
| Up/Down | Scroll the page by about 70dp; stops at the top and bottom |
| Left/Right | Nothing |
| OK | List of choices: Call, Copy address, Copy website URL (each only when the place has it). With none: toast "Still loading." while the details load, else "Nothing to call or copy." |
| Call | The phone's number in the dialer (nothing is dialled); with no number, toast "No phone number listed." |
| Back | Close the page (stopping the details load); the results are as they were, same row picked |
| Letters/digits | Ignored |

- Call puts the national number in the dialer (international if no other).
- Copy address copies the address without its country and toasts "Address
  copied"; Copy website URL copies the full URL and toasts "Website copied".
- An error loading details shows as a toast; the page stays as it was.
- Opening the same place again uses the kept details: no request.

**Menu on a place**: Stop (while loading), Call, Copy address, Copy website
URL (each when present), Back to results, Test location, Command center...

### Command center dialog

Title "Command center": an address field (hint "https://...") filled with the
current address, a token field (dots as its hint when a token is saved,
else "Token"), Cancel and Save. Save with the token empty keeps the saved
token. Both needed, else a toast says so. On save: toast "Saved: <host>".

### A Maps link

A link to maps.google.com, maps.app.goo.gl, google.com/maps,
www.google.com/maps, goo.gl/maps or a geo: URI opens Places (one instance,
even if it is already open; an open place page is closed first). It shows
"Opening the link..." with the loading bar, then:

- a place id in the link opens that place's page, with it as the only
  result;
- otherwise the name or address in it (or, failing that, its point) is put
  in the header and searched near the link's point, without the filters,
  and the first result opened; Back from it shows the rest;
- a link it can't read shows "Couldn't tell what that link points at.";
  a short link redirecting off Google's hosts shows "That link is not a
  Google Maps link.";
- the search is added to the recent searches.

## Behaviour

- **Login**: the command center address and delta token, baked in at build
  time (adopted once per new build revision) or typed in the dialog. With
  none, the app starts showing "No command center", and a search or link
  opens the dialog instead.
- **Maps key**: fetched from the command center (`GET /maps-key`) when first
  needed, kept in memory only. 404 shows "The command center has no Google
  Maps key. Put one there with PUT /maps-key." A key Google refuses (400,
  401, 403) is dropped and fetched again next time.
- **Search**: Google Text Search, 20 a page, biased (not limited) to 5 km
  around Near; Open now adds `openNow`, Top rated `minRating` 4.5. Next pages
  use Google's page token with the same query, point and filters.
- **Near**: typed as a place (looked up once with a one-result Text Search)
  or as `lat,lng` (used directly). An unknown place shows `Couldn't find
  "xyz"`. The typed text and the point found are saved, so the next search
  near the same text makes one request, not two. Empty means Anywhere.
- **Survives a restart** (SharedPreferences `places`): login, Near and its
  point, Open now, Top rated, up to 12 recent searches (newest first, a
  repeat moved to the top, compared ignoring case). Near is also saved
  when the app is paused.
- **Kept in memory only**: the key, the results, fetched details (by id),
  and photos (fetched 240px wide, about 10 MB kept). Leaving the app loses
  them.
- **One request at a time**: a new one abandons the old, whose answer is
  never shown.
- **Offline or errors**: the failure's message shows in place of the list
  (or as a toast on a place page); Google's own messages begin
  "Google Maps: ". Nothing retries by itself.
- **Permissions**: Internet. Location only for the temporary location test.
  Dialling goes through the phone's dialer and needs none.
- Nothing is timed and nothing runs in the background.

## Look

DESIGN.md's "Places" section holds: Google Maps' dark theme (`#131314`),
Google Sans Flex, Maps' teal for icons, cursor and loading bar, Maps'
open/closed/star colours, the teal-tinted picked row. Places is not one of
the light family, so the family rules on colour and focus cards don't
apply; rules 7 and 8 (no instructions, no app-name header or counters) do.
Portrait only.

## Open questions

1. Menu → Test location (`LocationTest`, the two location permissions) was
   added as temporary in 1.4.3 to see whether the XP3 can locate itself.
   Is the answer in, and should it be removed (or turned into a "near me")?
2. An error while loading the next page (Down past the last result) is never
   seen: the message line shows only when there are no results. Should it
   be a toast?
3. Back in the header with text typed but no results leaves the app at once,
   losing the text. Intended, or should the first Back clear the text?
4. A Maps link opened with no login shows the dialog but forgets the link;
   after saving, it has to be tapped again. Should it be resumed?
5. The clear (x) button and the back arrow in the header can't be reached by
   any key; they are only signs. Is that intended?
