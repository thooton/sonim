# AGENTS.md

Small, dependency-free Android apps for the Sonim XP3, one per directory. Each
app has its own `AGENTS.md`; read it before changing that app.

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

## Building

Every Gradle app, and the shared code, is one Gradle build at the root, run
with JDK 21:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew selfTest assembleRelease
```

builds every app and runs every self-test. One app is `:<app>:app` (and
`:<app>:core`), as in `./gradlew :delta:core:selfTest :delta:app:assembleRelease`.
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
  generated `Bundled` class (Canvas, Charge, Delta, Mail, Places, Source,
  Tablet). The files it reads are gitignored, and CI has none, so its APKs
  carry no login.

## Shared code

`common/` holds what more than one app uses. Use it rather than copying.

- `common/core` (plain Java, `com.xp3.common`): `Json` (strict codec), `Http`
  (deadlines, bounded bodies, no redirects), `Disk` (whole-file reads and
  atomic writes), `Login` (the command center address and delta token, as
  Source, Places and Mail sign in); and, as test fixtures, `Check` (the test
  harness) and `TestServer` (a loopback HTTP server).
- `common/ui` (Android, `com.xp3.common.ui`): `BaseActivity` (`dp`, `color`,
  `toast`, `alert`, `vertical`, `text`, `listDialog`), `Ui` (workers, `fill`,
  `weighted`, `isOk`, `onSelect`), `Card` (the SD card, where downloads
  go), `Tabs` (tab bar with d-pad cycling),
  `SelectableAdapter` (rows that paint their own highlight), `Markdown`, and
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
newest commits (`git log` on the app's directory, the "App 1.2.3:" lead cut
from each subject and its version kept beside it). The Updater reads only
that. `versions.txt` and `packages.txt` are still published from the same
data, solely so phones on an Updater older than 1.3.0 can update to one
that reads the manifest. A new Gradle app is built and published as soon as
it exists; nothing needs adding to the workflow (an app whose sources aren't
in a directory of its release name goes in `DIRS` in the script, as the
launcher's `AppDrawer` does).

## Shared rules

- Every Gradle app is signed with the shared key in `signing/` (see
  `signing/signing.gradle`); never replace or regenerate it. The launcher has
  its own key in `launcher/signing/`.
- Installs on the phone go through Installer (`installer/`), which installs
  as the device owner because the XP3's own installer screen refuses apps not
  signed by Sonim.
