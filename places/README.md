# Places, for the Sonim XP3

Finds places with Google Maps: pizza in Denver, a pharmacy near 85281, coffee
by Union Station. It is the phone end of `delta maps` in
[thooton/delta](https://github.com/thooton/delta): the Google Maps key is the
one `delta maps key set` keeps on the command center, and the app signs in
with the same delta token.

It takes its look from Google Maps in its dark theme, in Google Sans, laid
out for a d-pad: what to find in the header, filter chips under it, then
the places found as a compact list, each with its name, rating, price,
kind, address (without the country), whether it is open and how far. Before a search,
the recent searches are listed.

- **Near** (the first chip): where to search in or close to: a city, zip
  code, address or landmark, or `lat,lng`. It is remembered between searches,
  so set it once. Left as "Anywhere", Google guesses.
- **Open now** and **Top rated** (4.5 and up) narrow the search, as Maps'
  chips do, and are remembered too.

OK on a place opens its page, all on one page to scroll through: the name
(which shrinks to a small line as you scroll), the rating, kind and whether
it is open, one photo, the address, the week's hours (and happy hour and the
like), website and phone, then Reviews (the rating, Google's summary and the
reviews) and About (what it offers, payment, parking, wheelchair access and
fuel prices).

| Key | |
| --- | --- |
| OK | Header: search. Near chip: change it. Other chips: turn on or off. A result: open it. A recent search: run it again. A place: Call, Copy address, Copy website URL |
| Up / Down | Move between the header, the chips and the results; through the results (past the last loads more); scroll a place |
| Left / Right | Along the chips |
| Call | Put the picked place's number in the dialer |
| Back | Stop what's loading, leave a place, go back to the header, clear the results, or leave |
| Menu | Search, more results, change Near, clear; on a place, call or copy; command center |

Photos are billed by Google one by one (Place Photos), so only a place's
first photo is shown, on its page, fetched small and kept until the app
closes. The results show none.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :places:core:selfTest :places:app:assembleRelease
```

The command center login is baked in at build time, as in Source:
`-Pdelta.center=<file>`, else `delta-center.json` beside `android/`, else the
CLI's `~/.config/delta/config.json`, else the `DELTA_SERVER` and
`DELTA_TOKEN` environment variables of an agent's sandbox. A build without
one keeps an installed copy's login; Menu → Command center sets one by hand.
The APK contains a live token, so treat it like a password; it is
gitignored.

The typeface is Google Sans Flex (`app/src/main/res/font`), under the SIL
Open Font License 1.1 (`OFL.txt`).
