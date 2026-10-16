# AGENTS.md

## Scope

These instructions apply to the entire repository.

Charge is a private, sideloaded Android client for a Sonim XP3 running Android
8.1. It finds Tesla Superchargers, stores favorites, displays account and
charging history information, and starts, monitors, and stops real charging
sessions. The product constraints are unusual and intentional: the target is a
240 x 320 portrait, mdpi screen operated primarily with a physical keypad.

The current repository contains only the native Android application. Do not
reintroduce an older web implementation or assume there is a companion server.

## Toolchain and platform

- Build with the checked-in Gradle 8.9 wrapper and Android Gradle Plugin 8.7.3.
- The supported build environment is JDK 21 with Android SDK Platform 35 and
  Build Tools 35.0.1.
- Source is Java, compiled with Java 8 source/target compatibility. There is no
  Kotlin, Compose, AndroidX, dependency-injection framework, or HTTP library.
- `compileSdk` is 35, but `minSdk` and `targetSdk` are deliberately 27 to match
  the XP3's Android 8.1 firmware. Do not raise either casually; doing so changes
  permissions, foreground-service behavior, notification behavior, and other
  platform contracts.
- The application ID and Java package are `com.teslacharge.xp3`.

## Repository map

- `android/app/build.gradle`: the app's version, R8 settings and the bundled
  secrets (see `../gradle/bundled.gradle`).
- `android/app/src/main/AndroidManifest.xml`: portrait single-activity app and
  the non-exported charging foreground service.
- `MainActivity.java`: four tabs, programmatic detail/session screens, keypad
  navigation, asynchronous request coordination, and UI state.
- `ChargingService.java`: the sole active-session poller and owner of the
  ongoing notification. It polls every 4 seconds while observed, every 30
  seconds in the background, and every 8 seconds while recovering from errors.
- `data/ChargingRepository.java`: process-wide facade for searches, sites,
  account data, history, and safe charging workflows. It also persists the
  small amount of context Tesla omits from later session responses.
- `data/TeslaApi.java`: OAuth, geocoding, GraphQL transport, Tesla request
  metadata, and raw response extraction.
- `data/GraphqlQueries.java`: complete GraphQL documents; the app has no schema
  generation step or external query files.
- `data/Normalizer.java`: converts changing Tesla payloads into the stable,
  compact `JSONObject` models consumed by the UI. It also owns the observed
  charging-session state machine, price schedules, fee totals, and history
  normalization.
- `data/TokenStore.java`: synchronized access-token refresh and refresh-token
  rotation/fallback.
- `data/FavoriteStore.java`: durable bookmark metadata in
  `SharedPreferences`; live availability and pricing are intentionally not
  persisted.
- `data/SiteDirectory.java`: best-effort, in-memory lookup from a historical
  session's coordinates/name to a current `locationGUID`.
- `RowAdapter.java`, `SiteAdapter.java`, and `HistoryAdapter.java`: compact,
  explicitly selected list rows for physical-key navigation.
- `res/layout/activity_main.xml`: stable tab/content shell. Most screens are
  built programmatically in `MainActivity`; the shared site/history rows remain
  XML layouts.

The main data path is:

```text
MainActivity / ChargingService
            -> ChargingRepository (one process singleton)
            -> TeslaApi -> HttpJson -> Tesla/geocoder endpoints
            -> Normalizer -> compact JSONObject UI models
```

## Non-negotiable invariants

### Credentials and live-account safety

- `android/.tokens.json`, `android/.env`, generated `data/Bundled.java`,
  keystores, and every APK are secrets or secret-bearing artifacts. They are
  ignored intentionally. Never commit, paste, print, log, or expose their
  contents. Do not add a task that includes their values in Gradle output.
- The build requires `.tokens.json` with a `refreshToken`; it reads optional
  regional/geocoder settings from `.env` and writes only under
  `android/app/build/generated/bundled/`.
- Every debug and release APK embeds a live Tesla refresh token and must be
  treated as a credential, not as a distributable build artifact.
- Tesla rotates refresh tokens on use. Always obtain
  `ChargingRepository.get(Context)`; never construct a second repository,
  `TokenStore`, or independent refresh path. Concurrent clients can invalidate
  the credential stored by the other client.
- After an installed app refreshes successfully, the original refresh token
  embedded in that APK is stale. Clearing app data or reinstalling that same
  APK can therefore require rebuilding it with the newest token. Do not run
  another client with the same refresh token; whichever client refreshes first
  invalidates the credential the other one still holds.
- Merely launching network functionality may refresh and rotate credentials.
  Never run live API smoke tests without a reason. Never start or stop a real
  charging session as a test unless the user explicitly authorizes that exact
  real-world action.
- Preserve `TokenStore` synchronization and the rejected-access-token argument
  used to collapse concurrent 401 refreshes. Credential writes use `commit()`
  intentionally so the next request cannot race ahead of persistence.

### Charging correctness

- Starting a session is a paid, real-world mutation. Keep the confirmation UI
  and the `chargingOperation` single-flight guard.
- `ChargingRepository.start` must re-fetch site detail immediately before the
  mutation, verify the selected stall still belongs to the site and is still
  available, require an active pricebook, and run Tesla's pre-start validation.
- There may be only one active session. All screens must respect
  `sessionActive()` and disable competing starts.
- `ChargingService` owns session polling even while the app is visible.
  `MainActivity` observes service results; do not add a second Activity poller.
- Preserve the foreground notification for an active session, stale-data error
  labeling, foreground/background polling cadence, terminal-session shutdown,
  and bounded failure recovery. Its compact line reports charge level when
  available, delivered energy, and running cost; tapping it must return to the
  existing `singleTop` Activity on the tab the user left open.
- Session state is inferred from events plus measurements in
  `Normalizer.session`. Tesla payloads have been observed in several phases;
  update the pattern-based state machine conservatively and keep unknown states
  safe rather than treating them as complete.

### Threads, lifecycle, and stale responses

- Never perform HTTP work on the Android main thread. Use the existing
  `MainActivity.async` helper or the service's scheduled executor, then apply UI
  changes on the main looper.
- Preserve generation/request identity checks around searches, site detail,
  favorites, account requests, history loads, session lookups, and charging
  mutations. A callback from a screen the user left must not redraw over the
  current screen.
- The repository singleton is shared by the Activity and service. Keep objects
  that cross their worker threads thread-safe; do not assume request completion
  order.
- Shut down Activity-owned work in `onDestroy`, observe the service in
  `onResume`, and unobserve in `onPause`/`onDestroy`.

### XP3 user interface

- Physical-key operation is mandatory. D-pad left/right changes tabs;
  up/down moves focus or list selection; center, Enter, and numpad Enter all
  activate; Clear/Delete and Back leave detail; Menu opens app actions.
- Discover intentionally has a focusable `Find` action rather than a permanent
  text field. Activating it opens the address/ZIP dialog. Menu actions refresh
  the current page, change the search radius where applicable, and open account
  information.
- Do not rely on touch, gestures, hover, a software keyboard always being open,
  or a focusable tab bar. Touch support is supplementary.
- Preserve explicit selection state in `RowAdapter`. The opaque row background
  hides the normal `ListView` selector, so adapter selection and
  `row.setSelected(...)` are required for a visible keypad highlight.
- Keep list movement anchored as implemented in `moveListSelection`; selection
  moves within the viewport before the viewport scrolls. History paging must
  append without dropping the current selection.
- Design and manually inspect for 240 x 320 mdpi portrait. Vertical pixels and
  row width are scarce: prefer compact native views, existing metrics/styles,
  short labels, bounded lines, and the established street/city/value hierarchy.
- Do not add touch-only controls, oversized Material defaults, duplicate tab
  headings, marquees, or content that pushes the primary action off screen.
- Keep important state visible in words as well as color. Focus styling,
  unavailable/low-capacity coloring, stale readings, unpaid fees, and
  start/stop progress must remain legible on the device.

### API and model boundaries

- Keep raw Tesla field/path knowledge in `TeslaApi` and `Normalizer`, not spread
  through view code. UI code should consume the normalized JSON contracts.
- Update `GraphqlQueries` and the matching extraction/normalization together
  when a response shape changes. Preserve Tesla's operation names, endpoint
  choice, screen metadata, locale/country inputs, and NTSLA vehicle filters
  unless verified captures show otherwise.
- Treat remote JSON fields as optional. Use `Jsons` helpers and `opt*` access,
  retain explicit `JSONObject.NULL` where “unknown” differs from zero/false,
  and turn boundary failures into actionable `ApiException` messages.
- Do not cache live availability, prices, current charger identity, or account
  state as durable facts. Favorites store only site identity/metadata;
  `SiteDirectory` caches matches only for the current process and remembers
  unambiguous matches only.
- History pages are one-based and newest-first. Stop paging on an empty page
  even if Tesla incorrectly reports `hasMoreData`.

## Implementation conventions

- Match the existing Java style: four-space indentation, braces on the same
  line, lambdas for listeners and callbacks, imports grouped by
  platform/project/JDK/JSON, and small helpers for repeated view construction
  (shared ones live in `common/ui`).
- Prefer focused changes over architectural rewrites. `MainActivity` is large,
  but its state and generation counters are tightly coupled to navigation and
  lifecycle behavior; extract code only with equivalent behavior and clear
  ownership.
- Use existing colors, dimensions, selector drawables, row layouts, money/unit
  formatting, and view helpers before introducing another visual convention.
- Comments should explain an XP3 constraint, Tesla payload quirk, concurrency
  decision, or non-obvious invariant. Do not add comments that merely narrate
  the next line.
- Avoid new dependencies when platform Java/Android APIs already cover the
  need. Any dependency must support API 27, the no-AndroidX project, R8, and the
  constrained private APK use case.
- Keep user-visible copy concise and specific. Preserve the distinction among
  absent data, stale data, empty results, recoverable network failure, and a
  rejected charging mutation.

## Build and validation

Run commands from the repository root unless a command says otherwise.

```bash
./gradlew :charge:app:assembleDebug
```

This is the minimum validation for Java, resource, manifest, or Gradle changes.
It runs secret generation and writes the debug APK beneath
`android/app/build/outputs/apk/debug/`; that APK is sensitive and ignored.

There are currently no unit or instrumentation test sources. When logic can be
tested without Android or live Tesla calls, add focused tests under
`android/app/src/test/` and configure only the minimal test dependency. Put
device/lifecycle tests under `android/app/src/androidTest/` when they genuinely
need Android. Never make tests depend on a live account or mutate charging
state.

Before handing off a change, also run from the repository root:

```bash
git diff --check
git status --short
```

Review the diff for secret material and generated/binary artifacts. Validate
the smallest relevant manual flows on an API 27, 240 x 320 portrait target (or
equivalent emulator), especially:

- left/right tab switching and up/down/OK/Clear/Back/Menu behavior;
- visible focus and stable selection while lists update or history appends;
- search dialog entry, radius changes, empty/error states, and favorites live
  refresh;
- detail-screen sizing, pricing, unavailable stalls, and active-session guards;
- session notification, screen-off/background/resume behavior, stale-reading
  warning, stop/cancel states, terminal cleanup, and unplug messaging;
- history paging, retry footer, receipt rendering, and best-effort site link.

Only exercise the charging items above with an explicitly authorized real
session. Otherwise review those paths statically and limit runtime checks to
non-mutating behavior.

## Release builds

```bash
./gradlew :charge:app:assembleRelease
```

This builds the minified, resource-shrunk APK, signed with the repository's
shared key (`../signing/signing.gradle`), at
`android/app/build/outputs/apk/release/app-release.apk`. That is the APK to hand to the
user; the debug build is only for checking that the code compiles. Never commit
it, or distribute it beyond the user's own phone, unless the user explicitly
asks and understands that it embeds a live account credential.
