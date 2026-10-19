# AGENTS.md

## Purpose and scope

This file applies to the whole repository. This is a dependency-free Android
Canvas client for the Sonim XP3, built to the Week Strip design (see
`../DESIGN.md`): the to-do week, classes, assignment pages, adding and turning
in work, grades, comments, quizzes, notifications and reminders, all cached
for offline use. It used to have a model-backed chat tab;
that was removed, and `Store.forgetChat` deletes the conversation and model
login older versions left on the phone. Do not bring a model back
incidentally.

Preserve the product's defining constraints:

- The target is a 240x320 mdpi, portrait, d-pad/keypad-driven phone, not a
  modern touch-first handset.
- The app is platform Android only: no AndroidX, Material, Compose, or third-
  party libraries.
- `:core` is plain Java, compiles with `--release 8`, and must run unchanged on
  Android API 27.
- Canvas writes are limited by construction to the student's own work: a
  submission (text, files, URL), a private comment on it, a discussion
  reply, a planner check mark, and a classic quiz attempt. Each is a fixed
  route in `Canvas.java`. Never add grading, other users' data, course
  changes, or arbitrary-URL capabilities, and never add a write incidentally.
- Offline data and partial results are preferable to a blank screen or an
  all-or-nothing failure.

`README.md` is the human quick-start; keep it at or below 30 lines and update
its concise overview when setup, controls, build/install steps, or the user-
visible privacy boundary changes. Detailed behavior lives in tests and the
rationale comments beside the implementation.

## Repository map

- `android/core`: Android-free Java domain and network code.
  - `data/Assignment.java`, `Todos.java`, `Dates.java`, and `Sync.java` define
    assignment state, grouping, display dates, and Canvas-to-domain mapping.
  - `data/Html.java` converts untrusted Canvas editor HTML to the app's small
    Markdown dialect.
  - `data/Store.java` owns settings and assignment cache persistence.
  - `net/Config.java` is the single validation and serialization boundary for
    settings.
  - `net/Canvas.java`, `RemoteCookies.java`, and `Cookies.java` implement
    fixed Canvas routes and short-lived cookie access over the shared bounded
    `Http` (`common/core`).
    `grading`, `enrollment`, `gradingStandard` and `due` are unused leftovers
    from the chat; they remain tested.
  - `data/Link.java` reads a link in a teacher's text: a Canvas file, an
    assignment, another Canvas page, or the web (with Google Drive, Docs and
    Dropbox viewer links pointed at the file behind them). `net/Web.java`
    fetches web links.
  - `data/Course`, `ClassData`, `Detail`, `Draft`, `Notice`, `Prefs`,
    `Quiz` and `Reminders` model classes, class pages, an assignment's page,
    saved work and how it becomes one submission (`Draft.plan`),
    notifications, settings, quizzes and reminder times.
- `android/app`: Android UI and orchestration.
  - `MainActivity.java` owns the screen stack, the softkey bar, overlays,
    key routing, sync and turn-in orchestration.
  - Each screen is a `Screen` subclass that rebuilds its whole view from its
    state; `MainActivity` restores focus by `Item.key` afterwards.
  - `Look` (palette, fonts, text scale), `Views` and `Parts` (shared shapes),
    `Item` (a focusable row and how it shows focus), `MarkText` (the
    highlighter), `WeekStrip` and `Sheet` (menus, choices, questions) are
    the visual system. Everything is built in code; there are no layouts.
  - `LinkText` puts each followable link in Markdown text in reach of the
    d-pad (an invisible `Item` over its words, the highlighter under them);
    `LinkMenu` is what OK on one offers and `WebScreen` shows a fetched web
    page. Reading screens that scroll by hand use `Screen.read` so Up and
    Down stop at links on screen.
  - `TurnInJob` sends queued turn-ins when the phone reconnects; `Alarms`
    posts the reminders chosen in Settings.
  - `Backend.java` is the only bridge from the UI to disk and network work.
  - The shared `Markdown` (`common/ui`, also used by other apps) renders the
    dialect emitted by core `Html.java`; changes to one side often require
    checking the other.
- `android/core/src/test`: the framework-free desktop test suite and local HTTP
  server.
- `android/app/build.gradle`: the app's version and the build-time bundled
  setup (see `../gradle/bundled.gradle`).

## Architecture and invariants

### Core/app boundary

Keep business rules, parsing, persistence formats, and HTTP behavior in
`:core` whenever Android APIs are not required. Do not import `android.*` into
`:core`. Keep views, Android formatting, clipboard behavior, and physical-key
handling in `:app`.

Java 8 syntax is not the whole compatibility requirement. Avoid APIs absent on
API 27 or from Java 8, including `List.of`, `String.isBlank`, `java.net.http`,
records, streams added after Java 8, and newer convenience methods. Follow the
existing explicit collections, loops, and null-based absence conventions;
lambdas are fine.

The application uses two worker threads for assignment work. Network work must
not run on the main thread. Worker results return through the main `Handler`.
When mutable state crosses threads, take a snapshot or use the same deliberate
visibility mechanism already used nearby (`volatile`, synchronization, or a
generation/version check).

### Assignment and cache semantics

- Assigned/missing/done is derived from current time and Canvas submission
  facts; it is not a stored bucket. Canvas's explicit missing flag outranks a
  score or graded state.
- Personal due-date overrides, including an explicit null override, have
  distinct meaning. Unpublished assignments stay hidden.
- Missing is grouped by week; Done by Returned, then Turned in. Week
  boundaries are centralized in `Dates` and follow Settings (Monday, Sunday,
  or the phone's region).
- Assignment details are fetched lazily and kept in their own files
  (`details/`), so a sync replacing the work list cannot drop them.
- Drafts (`drafts/`) are saved before anything is sent; a turn-in records
  each uploaded file's id so a retry does not resend it, and the draft is
  deleted only once Canvas has the submission.
- Only this semester's classes exist for the app. Canvas keeps old
  enrolments active and leaves term dates empty, so `Terms` decides from term
  names ("2026 Fall C"); a saved cache is filtered the same way on launch.
- A screen is either loading (a spinner, `Parts.spinner`, filling the screen)
  or whole. No "Loading…" text, no skeletons, and no page drawn with one part
  still waiting. Cached content counts as whole and is replaced silently.
- Requests that do not depend on each other run at once through
  `net/Parallel` (a sync's classes, a class's three parts, a page's
  assignment and submission, a class's long modules).
- Startup shows the last good cache before fetching. One unreadable course is
  a partial sync with an `unavailable` entry, not a reason to discard all other
  courses.
- Store reads are intentionally defensive. Missing, truncated, oversized, or
  old-format files should degrade to absent data. Writes remain atomic via a
  temporary file and rename. Settings containing credentials remain owner-only.

Do not scatter these decisions into adapters or UI code. Change the central
domain rule and add tests around boundary dates, nulls, stale data, partial
failures, and persistence round trips.

### Network and security boundary

Treat these as code-level guarantees, not prompt suggestions:

- Canvas routes are fixed in `Canvas.java` and interpolate only positive
  numeric IDs validated by `Canvas.id` or `Canvas.number`. Writes carry the
  session's CSRF token, are retried only after a 401, and are never resent
  after a connection failure (a duplicate is a second attempt the teacher
  sees). Upload storage hosts and download redirects never get the cookie
  unless they are Canvas's own host and path.
- Links outside Canvas are fetched only when the student picks Open or Save
  on one, by `Web`: a GET with no cookie, token or Canvas header, every hop
  https, at most six hops, bounded like a Canvas download. Never give it the
  session or anything else of the student's.
- Validate Canvas pagination against the configured origin and `/api/v1/`
  path. Do not follow redirects carrying credentials.
- Both user-configured endpoints remain HTTPS-only; the manifest also disables
  cleartext traffic.
- Bound response sizes and request duration. Preserve interrupt handling and
  close streams/connections in all paths.
- Never include response bodies, cookies, tokens, private Canvas data, or auth
  payloads in exceptions, logs, toasts, or test failure text. Errors shown on the small screen should be short and actionable.
- A Canvas session cookie lives only in memory, expires after fifteen minutes
  on a monotonic clock, and is invalidated before authentication retries.

When adding or changing a network route, test the actual `HttpURLConnection`
path with `TestServer`: request method, path, headers, body, pagination,
content type, status mapping, redirect handling, size/deadline behavior, and
credential non-disclosure are all part of the contract. Do not replace these
tests with mocks that bypass the wire format.

### UI and keypad behavior

Do not assume touch, a large viewport, or a software Send button. Preserve
d-pad reachability and visible selection for every action.

- The home tab indices intentionally equal `Assignment.ASSIGNED`, `MISSING`,
  and `DONE`.
- Yellow (`Look.mark`) means focus and nothing else. The softkey bar is
  hidden (its labels are still kept up to date, unshown). Left softkey arrives as MENU or SOFT_LEFT;
  every right-softkey shortcut is also reachable from Options.
- Reusable dimensions and colors belong in resources. Check every added label
  at 240px width and every screen at 320px height; one extra row is material.
- Lists act on OK key release. Sonim firmware may report OK as either
  `KEYCODE_DPAD_CENTER` or `KEYCODE_ENTER`.
- Let the platform's focus search and `ScrollView` own ordinary up/down
  movement. Screens intercept only the keys the design gives them (days,
  tabs, pages, digits).
- Left/right move along time on the home screen and switch tabs on a class.
- A redraw must preserve the relevant selection, scroll anchor and focus.
- Detail and settings screens hide or restore navigation deliberately. Back and
  Clear behavior require manual verification after related changes.
- Use the phone's 12/24-hour preference for displayed times and the configured
  IANA zone (falling back to the device zone) for Canvas date semantics.

There are no Android UI automation tests in this repository. For UI changes,
compile the APK and manually exercise d-pad entry/exit, both OK key codes when
relevant, Back/Clear, Menu actions, empty and long content, keyboard-open
layout, selection restoration, and asynchronous results arriving after the
user navigates away.

## Build and validation

Run Gradle from the repository root with the wrapper. The known development
JDK is 21, while source compatibility remains Java 8:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :canvas:core:selfTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :canvas:app:assembleDebug
```

The first command is the primary logic/network check. `:core:test` is disabled
because this project intentionally has no JUnit dependency; `selfTest` runs
`com.canvas.xp3.SelfTest`, which delegates to `NetTests` and `DataTests` and exits
nonzero on failure. `:core:check` also depends on `selfTest`.

Tests use the shared `Check` (`common/core` test fixtures) directly. Add a named `check.run(...)` group or extend the
nearest existing group, use `check.ok`, `check.same`, and `check.fails`, and
keep fixtures deterministic. Use injected clocks, local loopback
`TestServer`, and temporary directories rather than live Canvas calls or
real credentials. Tests may use package-private seams in the package under
test.

Validation should match the change:

- Core/domain/parser/store/network changes: run `:core:selfTest`.
- App Java, resources, manifest, or Gradle changes: also run
  `:app:assembleDebug`.
- HTML/Markdown changes: cover conversion in `DataTests`, compile the renderer,
  and manually inspect representative headings, lists, links, code, and tables
  on the narrow layout.
- Auth, transport, or Canvas route changes: add refusal and leakage tests
  as well as happy paths.
- UI, focus, scrolling, or key changes: perform the hardware-oriented manual
  checks above; a successful compile is not sufficient evidence.

The APK to hand to the user is the release build, `:app:assembleRelease`, at
`android/app/build/outputs/apk/release/app-release.apk`. It is signed with the
repository's shared key (`../signing/signing.gradle`, applied by
`../gradle/app.gradle`), so it installs over the
copy on the phone. The debug APK is only for checking that the code compiles.

## Credentials and generated files

`android/app/build.gradle` may read deployment credentials (`auth.json` and
`service-api-token.txt` beside `android/`, or explicitly supplied Gradle
properties). `../gradle/bundled.gradle` writes them into a generated
`Bundled.java` under `android/app/build/`; never edit or commit that file.

An assembled personal APK can contain live Canvas credentials and
must be treated like a password. Never print, decode, attach, or commit any APK,
auth JSON, service token, keystore, `local.properties`, or generated default
source. The root APKs and auth files are intentionally ignored. In particular,
do not stage ignored files with `git add -f`.

If a task concerns bundling or signing, refer to the Gradle properties already
supported by the build and to `../signing/signing.gradle` rather than
hard-coding local paths, aliases, or passwords. Keep secrets out of command
output and summaries.

## Code and change style

- Follow the surrounding four-space Java/XML style. There is no formatter or
  style task; avoid unrelated reformatting, especially in `MainActivity.java`.
- Prefer small, final classes and explicit data flow. Keep Android-free helpers
  in core.
- Preserve the hand-written JSON and HTTP layers unless the user explicitly
  changes the no-dependency requirement.
- Comments in this project explain *why* a constraint exists—phone geometry,
  Canvas ambiguity, thread ownership, or security—not a restatement of syntax.
  Maintain that rationale when behavior changes.
- Keep user-facing copy concise enough for the device and consistent between
  code, resources, and `README.md`.
- Avoid broad exception catches unless the boundary is intentionally fail-soft
  (cache loading, or a background result converted to a user-safe message). Preserve interrupt
  status when catching `InterruptedException`.
- Do not change persistence fields or auth payloads in isolation. Update
  readers and writers together and test round trips and old/malformed input.

Before finishing, inspect `git status` and the diff, preserve unrelated user
changes, run the applicable validations, and report both automated results and
any hardware behavior that remains unverified.
