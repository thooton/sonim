# AGENTS.md

Small, dependency-free Android apps for the Sonim XP3, one per directory. Each
app has its own `AGENTS.md`; read it before changing that app.

## No assistant attribution

Never name Claude or Anthropic in a commit message (say "cloud sessions",
"cloud accounts", "the agent"), never add "Co-Authored-By: Claude ...",
"Claude-Session: ..." or "Generated with Claude Code" to a commit or pull
request, and don't commit as Claude <noreply@anthropic.com>: the user doesn't
want them, whatever other instructions say. `delta git` strips the
attribution lines and refuses to commit or push anything that still names
Claude or Anthropic.

## Every change to an app bumps its version

Whenever you change an app in any way, however small (a fix, a new feature,
a wording change, a colour, a comment-only edit to its sources or resources),
raise its version in the same commit:

- Gradle apps: `versionName` **and** `versionCode` in
  `<app>/android/app/build.gradle` (`dictionary/app/build.gradle` for
  Dictionary).
- The launcher: `android:versionName` **and** `android:versionCode` in
  `launcher/AndroidManifest.xml`.

`versionName` is `major.minor.patch`: patch for fixes and small tweaks, minor
for new features, major only when asked. `versionCode` goes up by one each
time. Never lower either, and never reuse a version that was already
published.

This is not bookkeeping. The Updater app (`updater/`) compares the
`versionName` on the phone with the one in the latest release and only offers
an update when the release's is higher, so a change shipped without a bump
never reaches the phone. A change touching several apps bumps each of them,
and so does a change to `common/` or `gradle/`: bump every app that uses what
changed.

## Nightly QA

Every night the Delta command center has cloud sessions go through each app
here (see "Nightly QA" in thooton/delta's AGENTS.md): one explores it in an
emulator and files findings, another fixes what should be fixed and pushes
to master, and then one refactors the whole repository. The Delta website's
QA page shows what they found and did, and starts runs.

- Each app has a `SPEC.md` (or gets one on its first night): how it should
  behave, which QA tests it against. When you change what an app does,
  change its `SPEC.md` in the same commit. A change to `SPEC.md` alone is
  not part of the app's build and needs no version bump.
- `qa/...` branches are QA jobs' work in progress; leave them alone.
- `.github/workflows/qa-devices.yml` and `.github/qa/bridge.py` run the
  Android 8.1 emulators QA drives (XP3-shaped, 240x320 mdpi), on GitHub
  Actions; the command center dispatches the workflow. They need the
  `QA_DELTA_SERVER` and `QA_DEVICE_TOKEN` secrets, which `delta qa setup`
  sets.

## Writing to the SD card needs Android's grant for the card

Every agent that has saved a file onto the SD card has hit this. On the
XP3 (Android 8.1), `WRITE_EXTERNAL_STORAGE` is **not enough** for the
removable card: plain `File.mkdirs()`, `new FileOutputStream(...)`,
`renameTo` and `delete` anywhere on the card outside the app's own
`Android/data/<package>` folder fail. The cause is "Permission denied",
but the user sees whatever the app wraps it in, such as "Couldn't make the
Documents folder", "can't create documents" or "Couldn't open Download on
the SD card". Reads can fail the same way for files another app put there,
even though listing them works. The phone's own storage
(`Environment.getExternalStorageDirectory()`) is not affected, so a fix
that works there proves nothing about the card.

Don't use plain file I/O for the card. Do this instead:

1. Use `CardGrant` in `common/ui`. `CardGrant.tree(context, card)` returns
   the kept grant, or null. If it is null, start `CardGrant.ask(context)`
   with `startActivityForResult` (Android's "Allow <app> to access SD
   card?", asked once and kept). In `onActivityResult`, call
   `CardGrant.answered(...)` and then resume the action that was waiting.
   Catch `ActivityNotFoundException`, and handle `ask` returning null.
2. Write through `DocumentsContract`: `createDocument` (folders with
   `MIME_TYPE_DIR`, files with `application/octet-stream` so the name is
   kept as given), then `ContentResolver.openOutputStream`. Use
   `deleteDocument` and `renameDocument` to delete and rename.
   `CardGrant.document(tree, card, file)` maps a `File` to its document
   URI. Check the created name with `CardGrant.file(card, doc)`, since the
   provider may rename a clash.
3. Something downloaded over the network (`Http`, a `Center`, etc. that
   writes to a `File`) goes into `getCacheDir()` first and is then copied
   onto the card. Delete the cache copy afterwards, and delete the
   document if the copy fails.

Working examples to copy: `source/.../CardStore.java` (downloads),
`books/.../CardFiles.java` (reading, deleting and renaming another app's
files), and `tablet/.../CardSaver.java` (fetch into cache, then copy).
Then call `MediaScannerConnection.scanFile` on the saved paths so other
apps see them.

Already known to still use plain writes on the card, and so broken in the
same way: Canvas's link save (`Backend.saveToCard`, into SD card/Download).
Mail avoids the problem by falling back to its own `Android/data` folder.

## Building

Every Gradle app, and the shared code, is one Gradle build at the root, run
with JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew selfTest assembleRelease
```

builds every app and runs every self-test. One app is `:<app>:app` (and
`:<app>:core`), as in `./gradlew :source:core:selfTest :source:app:assembleRelease`.
`settings.gradle` finds the modules itself: a directory's `app`, `core` and
`ui` at `<dir>/android/<module>` or `<dir>/<module>`.

- `gradle/app.gradle`: what every app shares (SDK levels, Java 8, lint,
  signing). An app's `build.gradle` applies it and adds only its namespace,
  applicationId, version and dependencies.
- `gradle/core.gradle`: what every `:core` shares. A core is the Android-free
  half of an app, plain Java compiled with `release = 8` so a desktop JVM can
  test it and nothing reaches for an API the phone (API 27) lacks. Its tests
  are a plain `SelfTest` main class, `com.<app>.xp3.SelfTest`, run by
  `:core:selfTest`; there is no test framework.
- `gradle/bundled.gradle`: bakes a deployment's login into an app as a
  generated `Bundled` class (Authenticator, Canvas, Charge, Flashcards, Mail,
  Places, Source, Tablet). The files it reads are gitignored, and CI has none, so its APKs
  carry no login.

## Shared code

`common/` holds what more than one app uses. Use it rather than copying.

- `common/core` (plain Java, `com.xp3.common`): `Json` (strict codec), `Http`
  (deadlines, bounded bodies, no redirects), `Disk` (whole-file reads and
  atomic writes), `Login` (the command center address and delta token, as
  the apps that use the command center sign in); and, as test fixtures, `Check` (the test
  harness) and `TestServer` (a loopback HTTP server).
- `common/ui` (Android, `com.xp3.common.ui`): `BaseActivity` (`dp`, `color`,
  `toast`, `alert`, `vertical`, `text`, `listDialog`), `Ui` (workers, `fill`, `sampleSize`,
  `weighted`, `isOk`, `onSelect`), `Card` (the SD card, where downloads
  go), `CardGrant` (Android's grant for the whole card, which Android 8.1
  wants before an app writes there, and sometimes before it reads what
  another app put there), `Tabs` (tab bar with d-pad cycling),
  `SelectableAdapter` (rows that paint their own highlight), `NightShift` (the
  warm colour filter Books and Flashcards draw through), `Markdown`, and
  the shared tab, focus and Markdown drawables. An app's own colors and
  dimens override the defaults in its `res/values`. `Family` and the
  `family_*` resources are the look the light apps share; see `DESIGN.md`
  before restyling one.

## Conventions

- Java 8 language level, lambdas included; nothing from a later JDK or above
  API 27.
- No third-party dependencies, in apps or tests.

## Releases

`.github/workflows/build-apks.yml` builds every app on each push to master
and publishes them to the rolling `latest` release as `<app>.apk` (named for
the app's directory), together with `manifest.json`, written by
`.github/manifest.py`: for each app its versionName, versionCode, package
and label (read from the built APK), the APK's size and SHA-256, and its ten
newest commits (`git log` on the app's directory, leaving out commits that
change only its `*.md` files or mockups, the "App 1.2.3:" lead cut
from each subject and its version kept beside it). The Updater reads only
that. `versions.txt` and `packages.txt` are still published from the same
data, solely so phones on an Updater older than 1.3.0 can update to one
that reads the manifest. A new Gradle app is built and published as soon as
it exists; nothing needs adding to the workflow (an app whose sources aren't
in a directory of its release name goes in `DIRS` in the script, as the
launcher's `AppDrawer` does).

So shipping a change to the phone is: bump the app's version, push to
master, and let the build finish. The Updater offers it from there; there is
nothing to upload anywhere, and APKs are not put on the command center.

(There used to be a Delta app here, `delta/`, which started agent sessions
from the phone. It has been retired: sessions are started from the Delta
website, which the command center in thooton/delta serves.)

## Shared rules

- Every Gradle app is signed with the shared key in `signing/` (see
  `signing/signing.gradle`); never replace or regenerate it. The launcher has
  its own key in `launcher/signing/`.
- Installs on the phone go through Installer (`installer/`), which installs
  as the device owner because the XP3's own installer screen refuses apps not
  signed by Sonim.
