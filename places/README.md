# Places, for the Sonim XP3

Finds places with Google Maps: pizza in Denver, a pharmacy near 85281, coffee
by Union Station. It is the phone end of `delta maps` in
[thooton/delta](https://github.com/thooton/delta): the Google Maps key is the
one `delta maps key set` keeps on the command center, and the app signs in
with the same delta token.

Two fields:

- **Near**: where to search in or close to: a city, zip code, address or
  landmark, or `lat,lng`. It is remembered between searches, so set it once.
  Leave it empty to let Google guess.
- **Find**: what to look for: a kind of place or a name.

OK in Near moves to Find; OK in Find searches. Each result shows its name,
its street address and town, its rating, price and kind, and how far it is
from Near and whether it is open now, in green or red. Pick one with OK to see everything Google has on
it: address, phone, website, the week's hours (and happy hour and the like),
price range, what it offers, payment, parking, wheelchair access, fuel
prices, summaries and reviews.

| Key | |
| --- | --- |
| OK | Near: on to Find. Find: search. Results: open the picked place. A place: its menu |
| Up / Down | Move between the fields and into the results; move through results (past the last loads more); scroll a place |
| Left / Right | On a place: the previous or next result |
| Call | On a place: put its number in the dialer |
| Back | Stop what's loading, leave a place, leave the results, or leave |
| Menu | Search, more results, change Near, clear, call, website, command center |

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
