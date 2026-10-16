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
never reaches the phone. A change touching several apps bumps each of them.

## Releases

`.github/workflows/build-apks.yml` builds every app on each push to master
and publishes them to the rolling `latest` release as `<app>.apk`, together
with `versions.txt` (`<app> = <versionName>`) and `packages.txt`
(`<app> = <package name>`), both read from the built APKs. The Updater reads
those two files. A new app is only offered by the Updater once it is in that
workflow's build matrix, so add it there when you create one.

## Shared rules

- Every Gradle app is signed with the shared key in `signing/` (see
  `signing/signing.gradle`); never replace or regenerate it. The launcher has
  its own key in `launcher/signing/`.
- Installs on the phone go through Installer (`installer/`), which installs
  as the device owner because the XP3's own installer screen refuses apps not
  signed by Sonim.
