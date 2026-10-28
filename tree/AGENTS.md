# AGENTS.md

Tree is the phone's front end for the command center's nightly QA
(thooton/delta). A sibling of `../serve`, `../authenticator`, `../tablet` and
the rest, under the same constraints: 240x320 mdpi, d-pad, soft keys and
keypad (never touch), no AndroidX or third-party libraries, `:core` plain
Java `--release 8` running on API 27. `SPEC.md` is what the nightly QA tests
it against: keep it true when the app changes.

## What the QA is

Every night (or when asked), the command center goes through each phone app
(this repo) and tablet app (thooton/tablet). Per app: a spec job writes
`SPEC.md` once; an explore job drives the app in an emulator and files
findings (bug, problem, inconsistency, feature); a fix job triages each as
fixed, passed (left for the user), declined, grown (a feature built, only
for an app the user lets grow) or reverted (a grown feature the user gave a
👎, taken out by the next run), and pushes fixes. Then a refactor job per
repo, and last a short nightly report.

Keep the app small: what it does is listed in SPEC.md's Purpose, and what
it should not do in Not wanted.

## The API it uses

All on the command center's https address, with `Authorization: Bearer
<delta token>`, JSON both ways; an error is `{"error": "..."}` with a
non-2xx status, and that message is what the app shows (`Center.fail`).

- `GET /qa` (`?fresh=1` on Refresh, to have the server list the apps from
  GitHub again): settings, apps, the run going now, the latest runs.
- `PUT /qa/settings` `{"enabled"}` or `{"hour"}`, each with `"server"`.
- `PUT /qa/apps/{repo}/{dir}` `{"grow"}`.
- `POST /qa/runs` `{"apps": ["sonim/list"], "server"}`, or `{"server"}` for
  every app; 409 when a run is already going.
- `GET /qa/runs/{id}` (the run whole, with apps and jobs), `DELETE
  /qa/runs/{id}` (cancels it).
- `GET /qa/findings?app=sonim/list&limit=100`, newest first.
- `PUT /qa/findings/{id}/verdict` `{"verdict": "up" | "down" | ""}` (409
  unless the finding is grown).
- `GET /qa/shots/{name}`: a PNG.

`"server"` is the login's address exactly as the app uses it
(`https://host/prefix`): the command center sits behind a proxy under a path
prefix and can't work out its own public address, which the run's jobs need.

## Layout

- `android/core` (`com.tree.xp3.qa`): `Qa` (`GET /qa`, with `Settings`,
  `App`, `Progress`, `Run`, `Job`), `Finding`, `Center` (the client) and
  `Words` (every wording the screens use: names, tags, statuses, times,
  an app's counts). Read leniently: a missing field takes its empty value.
  Tests: `src/test/.../SelfTest.java` (`./gradlew :tree:core:selfTest`), the
  parsing, the wording, and the client against `TestServer`.
- `android/app` (`com.tree.xp3`): `Screen` (the base of every screen: the
  family header, the busy bar, the worker, soft keys, the popup menu,
  starting and cancelling runs, the login dialog), `Tree` (the login and the
  last `GET /qa`, kept in `files/qa.json`), `MainActivity` (home),
  `RunActivity`, `AppActivity`, `FindingActivity` (with the screenshots,
  kept in the cache).

## Look

The family look (`../DESIGN.md`) in a forest green: band `#17301C`, accent
`#2D7337`, wash `#E8F3E9`. The accent marks focus, the main button, the app
being worked on, and what waits on the user (findings left for them, grown
features without a verdict). Fixed is the family's green `#1E7B34`, a failed
job its red.

## Credentials

`app/build.gradle` bakes the login into a generated `Bundled.java` under
`build/` (`deltaCenter` in `../gradle/bundled.gradle`); CI's APKs carry
none, so the app asks for the command center's address and token on first
launch (Menu's Command center later). Never print, commit or attach the
config files, the generated source or the APK.
