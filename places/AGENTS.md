# AGENTS.md

A small, dependency-free Android app for the Sonim XP3 that finds places
with Google Maps. A sibling of `../source`, `../delta` and the rest, under
the same constraints: 240x320 mdpi, d-pad driven, no AndroidX or third-party
libraries, `:core` plain Java `--release 8` running on API 27.

## How it works

Places works the way `delta maps` does (`cmd/delta/maps.go`,
`cmd/delta-cc/maps.go` in thooton/delta). The Google Maps Platform key lives
on the command center: `GET /maps-key` with the delta token answers
`{"key"}` (404 while none is set; `delta maps key set` sets it). The app
fetches it the first time it needs it and keeps it in memory only, never on
disk, then calls the Places API (New) itself, so the command center never
sees a search. A key Google refuses (400, 401, 403) is dropped and fetched
again on the next request, in case it was replaced.

- **Near** is looked up once with a Text Search asking only for Pro fields
  (`Places.locate`), or read directly when typed as `lat,lng`. What was
  typed and the point found (`Anchor`) are kept in SharedPreferences
  (`places`: `near`, `anchor`), so the next search near the same place makes
  one request, not two.
- **Find** is a Text Search, `pageSize` 20, with a 5 km `locationBias`
  circle around Near: a preference, not a limit. Down past the last result
  asks for the next page with Google's `pageToken`.
- **A place** is `GET /places/{id}` with the CLI's details mask: contact
  details, hours (this week, usual, and secondary such as happy hour),
  price, Google's summaries, amenities, payment, parking, accessibility,
  fuel prices and up to 5 reviews. Fetched details are kept in memory by id.

Field masks decide what Google bills, so keep them the CLI's: search asks
for nothing past the Enterprise tier, only a place's details ask for
reviews and the other Atmosphere fields. The additions are `priceRange` and
`accessibilityOptions.wheelchairAccessibleEntrance` in the search mask, for
the results list: an Enterprise and a Pro field, so the search still bills
as Enterprise. If the CLI's masks or wording
change, change `Places` and `Place` here and keep the test in step.

Times are shown in the place's own offset (`utcOffsetMinutes`). The dash
Google writes between opening and closing times becomes a hyphen, and price
ranges use one too: the app writes no en or em dashes of its own.

- `android/core`: `net/` over the shared `Http`, `Json` and `Login`
  (`../common/core`): `Places` (the key and the three requests), `Place`
  (Google's object, read lazily: an open/closed status,
  the CLI's sections of a place as text, and the same facts in pieces for
  the details screen: hours, about, amenities as yes/no options, reviews),
  `Anchor` (where to search near, distances). Tests in `src/test` run via
  `:core:selfTest`, against a loopback server standing in for both the
  command center and Google.
- `android/app`: `MainActivity` (the one screen: the two fields, the
  results, a place's details, the keys, the menu, the login dialog) and
  `PlacePage` (a place's details, laid out as Google Maps lays out a place;
  see `../DESIGN.md`), with `Stars` (a rating's stars, partly filled) and
  `Flow` (chips that wrap).

## Credentials

`app/build.gradle` bakes the login into a generated `Bundled.java` under
`build/`, from the same places as Source (`deltaCenter` in
`../gradle/bundled.gradle`). A bundled login is adopted once per revision.
Never print, commit or attach the config files, the generated source, the
Maps key or the APK.
