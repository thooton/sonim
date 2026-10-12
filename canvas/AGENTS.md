# AGENTS.md

## Purpose and scope

This file applies to the whole repository. This is a deliberately small,
dependency-free Android homework client for the Sonim XP3. It reads Canvas,
caches assignments for offline use, and offers a model-backed chat agent with a
fixed set of read-only Canvas and local tools.

Preserve the product's defining constraints:

- The target is a 240x320 mdpi, portrait, d-pad/keypad-driven phone, not a
  modern touch-first handset.
- The app is platform Android only: no AndroidX, Material, Compose, or third-
  party libraries.
- `:core` is plain Java, compiles with `--release 8`, and must run unchanged on
  Android API 27.
- Canvas access is read-only by construction. Never add submission, grading,
  comment, file-upload, or arbitrary-URL capabilities as incidental changes.
- Offline data and partial results are preferable to a blank screen or an
  all-or-nothing failure.

`README.md` is the human quick-start; keep it at or below 30 lines and update
its concise overview when setup, controls, build/install steps, or the user-
visible privacy boundary changes. Detailed behavior lives in tests and the
rationale comments beside the implementation. `docs/bmms-scroll-analysis.md`
records the framework behavior behind the chat list's scrolling decisions;
consult it before altering chat focus or scrolling.

## Repository map

- `android/core`: Android-free Java domain and network code.
  - `data/Assignment.java`, `Todos.java`, `Dates.java`, and `Sync.java` define
    assignment state, grouping, display dates, and Canvas-to-domain mapping.
  - `data/Html.java` converts untrusted Canvas editor HTML to the app's small
    Markdown dialect.
  - `data/Store.java` owns settings, assignment cache, and chat transcript
    persistence. `ChatTurn.java` and `ChatHistory.java` define transcript state.
  - `net/Config.java` is the single validation and serialization boundary for
    settings.
  - `net/Http.java`, `Canvas.java`, `RemoteCookies.java`, and `Cookies.java`
    implement bounded HTTP, fixed Canvas routes, and short-lived cookie access.
  - `net/Tools.java` is the complete capability surface exposed to the model.
  - `net/Agent.java` owns the bounded tool loop and provider-neutral history;
    `Model.java` and `ClaudeModel.java` are the OpenAI/ChatGPT and Anthropic
    streaming transports.
  - `net/CodexAuth.java`, `ClaudeAuth.java`, and `Credentials.java` own model
    credential renewal.
- `android/app`: Android UI and orchestration.
  - `MainActivity.java` owns screens, focus, key handling, and main-thread UI
    state.
  - `Backend.java` is the only bridge from the UI to disk and network work.
  - The adapters own painted selection state; `ChatListView.java` adds the
    oversized-message scrolling behavior.
  - Repeated row shapes are XML layouts. Screens whose contents are dynamic are
    assembled in `MainActivity`.
  - `Markdown.java` renders the dialect emitted by core `Html.java`; changes to
    one side often require checking the other.
- `android/core/src/test`: the framework-free desktop test suite and local HTTP
  server.
- `android/app/build.gradle`: Android configuration and the build-time bundled
  setup generator.

## Architecture and invariants

### Core/app boundary

Keep business rules, parsing, persistence formats, and HTTP behavior in
`:core` whenever Android APIs are not required. Do not import `android.*` into
`:core`. Keep views, Android formatting, clipboard behavior, and physical-key
handling in `:app`.

Java 8 syntax is not the whole compatibility requirement. Avoid APIs absent on
API 27 or from Java 8, including `List.of`, `String.isBlank`, `java.net.http`,
records, streams added after Java 8, and newer convenience methods. Follow the
existing explicit collections, loops, anonymous classes, and null-based
absence conventions.

The application uses two worker threads for assignment work and a separate
single chat worker so a slow model turn cannot block a sync. Network work must
not run on the main thread. Worker results return through the main `Handler`.
When mutable state crosses threads, take a snapshot or use the same deliberate
visibility mechanism already used nearby (`volatile`, synchronization, or a
generation/version check). In-flight chat results must never land in a chat
that was cleared, rewound, signed out, or replaced.

### Assignment and cache semantics

- Assigned/missing/done is derived from current time and Canvas submission
  facts; it is not a stored bucket. Canvas's explicit missing flag outranks a
  score or graded state.
- Personal due-date overrides, including an explicit null override, have
  distinct meaning. Unpublished assignments stay hidden.
- Sections are grouped by due date, including Done. Week boundaries and labels
  are centralized in `Dates` and currently use Sunday-through-Saturday weeks.
- Assignment bodies are fetched lazily. A successful sync must carry already-
  cached details onto replacement objects before saving them.
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

- Canvas routes are fixed in `Canvas.java`, use GET, and interpolate only
  positive numeric IDs validated by `Tools.id`.
- The model must not choose a URL, path, command, or arbitrary code to execute.
  Keep `Tools.DEFINITIONS` and tool dispatch synchronized and narrowly typed.
- Validate Canvas pagination against the configured origin and `/api/v1/`
  path. Do not follow redirects carrying credentials.
- Both user-configured endpoints remain HTTPS-only; the manifest also disables
  cleartext traffic.
- Bound response sizes and request duration. Preserve interrupt handling and
  close streams/connections in all paths.
- Never include response bodies, cookies, tokens, private Canvas data, or auth
  payloads in exceptions, logs, toasts, test failure text, or model-facing
  errors. Errors shown on the small screen should be short and actionable.
- A Canvas session cookie lives only in memory, expires after fifteen minutes
  on a monotonic clock, and is invalidated before authentication retries.
- Model transports may POST to their fixed provider endpoints, but requests
  keep provider-specific auth behavior and `store: false` where applicable.

When adding or changing a network route, test the actual `HttpURLConnection`
path with `TestServer`: request method, path, headers, body, pagination,
content type, status mapping, redirect handling, size/deadline behavior, and
credential non-disclosure are all part of the contract. Do not replace these
tests with mocks that bypass the wire format.

### UI and keypad behavior

Do not assume touch, a large viewport, or a software Send button. Preserve
d-pad reachability and visible selection for every action.

- The first three tab indices intentionally equal `Assignment.ASSIGNED`,
  `MISSING`, and `DONE`.
- Reusable dimensions and colors belong in resources. Check every added label
  at 240px width and every screen at 320px height; one extra row is material.
- Lists act on OK key release. Sonim firmware may report OK as either
  `KEYCODE_DPAD_CENTER` or `KEYCODE_ENTER`.
- The chat compose field is the exception: it sends on key down because the T9
  keyboard consumes the first ENTER release while committing a word.
- Let `ListView` own ordinary up/down selection and scrolling. Do not broadly
  intercept arrow keys. `ChatListView` only specializes movement through rows
  taller than the viewport.
- Left/right belongs to the caret while it can move inside the draft; at a text
  edge it returns to tab navigation. Preserve this escape path.
- A redraw must preserve the relevant selection, scroll anchor, draft, caret,
  keyboard composition, and focus. Streaming updates adapter rows in place so
  they do not repeatedly rebuild the compose field.
- Detail and settings screens hide or restore navigation deliberately. Back and
  Clear behavior, and focus transfer between transcript and composer, require
  manual verification after related changes.
- Use the phone's 12/24-hour preference for displayed times and the configured
  IANA zone (falling back to the device zone) for Canvas/model date semantics.

There are no Android UI automation tests in this repository. For UI changes,
compile the APK and manually exercise d-pad entry/exit, both OK key codes when
relevant, Back/Clear, Menu actions, empty and long content, keyboard-open
layout, selection restoration, and asynchronous results arriving after the
user navigates away.

## Build and validation

Run Gradle from `android/` with the wrapper. The known development JDK is 21,
while source compatibility remains Java 8:

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :core:selfTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

The first command is the primary logic/network check. `:core:test` is disabled
because this project intentionally has no JUnit dependency; `selfTest` runs
`com.canvas.xp3.SelfTest`, which delegates to `NetTests` and `DataTests` and exits
nonzero on failure. `:core:check` also depends on `selfTest`.

Tests use `Check` directly. Add a named `check.run(...)` group or extend the
nearest existing group, use `check.ok`, `check.same`, and `check.fails`, and
keep fixtures deterministic. Use injected clocks, local loopback
`TestServer`, and temporary directories rather than live Canvas/model calls or
real credentials. Tests may use package-private seams in the package under
test.

Validation should match the change:

- Core/domain/parser/store/network/agent changes: run `:core:selfTest`.
- App Java, resources, manifest, or Gradle changes: also run
  `:app:assembleDebug`.
- HTML/Markdown changes: cover conversion in `DataTests`, compile the renderer,
  and manually inspect representative headings, lists, links, code, and tables
  on the narrow layout.
- Auth, transport, tool, or Canvas route changes: add refusal and leakage tests
  as well as happy paths.
- UI, focus, scrolling, or key changes: perform the hardware-oriented manual
  checks above; a successful compile is not sufficient evidence.

The APK to hand to the user is the release build, `:app:assembleRelease`, at
`android/app/build/outputs/apk/release/app-release.apk`. It is signed with the
repository's shared key (`../signing/signing.gradle`), so it installs over the
copy on the phone. The debug APK is only for checking that the code compiles.

## Credentials and generated files

`android/app/build.gradle` may read deployment credentials from the sibling
Canvas project, `~/.codex/auth.json`, `~/.pi/agent/auth.json`,
`claude-auth.json`, or explicitly supplied Gradle properties. It writes a
generated `Defaults.java` under `android/app/build/`; never edit or commit that
file.

An assembled personal APK can contain live Canvas and model credentials and
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
  in core and provider-specific translation at the transport boundary.
- Preserve the hand-written JSON and HTTP layers unless the user explicitly
  changes the no-dependency requirement.
- Comments in this project explain *why* a constraint exists—phone geometry,
  Canvas ambiguity, thread ownership, or security—not a restatement of syntax.
  Maintain that rationale when behavior changes.
- Keep user-facing copy concise enough for the device and consistent between
  code, resources, and `README.md`.
- Avoid broad exception catches unless the boundary is intentionally fail-soft
  (cache loading, credential persistence after an in-memory rotation, or a
  background result converted to a user-safe message). Preserve interrupt
  status when catching `InterruptedException`.
- Do not change persistence fields, tool schemas, auth payloads, or transcript
  reconstruction in isolation. Update readers/writers or provider adapters
  together and test round trips and old/malformed input.

Before finishing, inspect `git status` and the diff, preserve unrelated user
changes, run the applicable validations, and report both automated results and
any hardware behavior that remains unverified.
