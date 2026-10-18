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
- **Filters**: Open now and Top rated add Text Search's `openNow` and
  `minRating` (4.5); the next page is asked for with the same ones.
- **A place** is `GET /places/{id}` with the CLI's details mask: contact
  details, hours (this week, usual, and secondary such as happy hour),
  price, Google's summaries, amenities, payment, parking, accessibility,
  fuel prices and up to 5 reviews, plus the photos. Fetched details are
  kept in memory by id.
- **A photo** is `GET /{photo name}/media` with `skipHttpRedirect=true`
  (the shared `Http` follows no redirects), then the image from the
  address Google answers, which needs no key. Each is billed (Place
  Photos), so only a place's first photo is shown, on its page, fetched at
  240px and kept in memory by `Photos`.

- **A Maps link** opened from elsewhere (a text message, say: the phone's
  own Maps is disabled) comes to `MainActivity` through the manifest's
  VIEW filters for maps.google.com, maps.app.goo.gl, google.com/maps,
  goo.gl/maps and geo:. `Places.expand` follows a short link's redirects by
  hand (only to Google's hosts, sending nothing), and `Link` reads the full
  one for a place's id, a name or address, and a point. An id opens that
  place's page; otherwise the name (or the point) is searched for near the
  point and the first result opened, with the rest behind it.

Field masks decide what Google bills, so keep them the CLI's: search asks
for nothing past the Enterprise tier, only a place's details ask for
reviews and the other Atmosphere fields. The additions are `priceRange`,
`accessibilityOptions.wheelchairAccessibleEntrance`, the phone numbers,
`websiteUri` and `photos` in the search mask, so a place's page has its
photo, number and website before its details come: Enterprise and Pro
fields, so the search still bills as Enterprise; and `photos` in the
details mask. If the CLI's masks or wording
change, change `Places` and `Place` here and keep the test in step.

Times are shown in the place's own offset (`utcOffsetMinutes`). The dash
Google writes between opening and closing times becomes a hyphen, and price
ranges use one too: the app writes no en or em dashes of its own.

- `android/core`: `net/` over the shared `Http`, `Json` and `Login`
  (`../common/core`): `Places` (the key and the three requests), `Place`
  (Google's object, read lazily: an open/closed status,
  the CLI's sections of a place as text, and the same facts in pieces for
  the details screen: hours, about, amenities as yes/no options, reviews),
  `Anchor` (where to search near, distances), `Link` (a Maps link read). Tests in `src/test` run via
  `:core:selfTest`, against a loopback server standing in for both the
  command center and Google.
- `android/app`: `MainActivity` (the one screen: the header, the filter
  chips, the compact results or recent searches, the keys, OK's and Menu's
  lists of choices with icons, the login dialog), `PlacePage` (a place, on
  one scrolling page under a name that shrinks), `Look` (Maps' dark look in
  code: Google Sans, round buttons, rounded photos, the lines of facts),
  `Photos` (fetching and keeping photos) and `Stars` (a rating's stars,
  partly filled). The look is Google
  Maps' dark theme; see `../DESIGN.md`.

## Credentials

`app/build.gradle` bakes the login into a generated `Bundled.java` under
`build/`, from the same places as Source (`deltaCenter` in
`../gradle/bundled.gradle`). A bundled login is adopted once per revision.
Never print, commit or attach the config files, the generated source, the
Maps key or the APK.
