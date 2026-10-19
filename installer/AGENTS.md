# AGENTS.md

Installer lets the other apps install APKs on the XP3, whose own installer
screen refuses anything not signed by Sonim. Read `../AGENTS.md` first.

- `MainActivity`: a status page. It says whether the app is the device owner
  and, if not, gives the one `adb shell dpm set-device-owner` command that
  makes it one; if it is, one button gives the role up again.
- `InstallActivity`: started for a result (by the Updater, and formerly the retired Delta app) with an
  APK's content URI. As device owner it commits a `PackageInstaller` session
  with no confirmation screen, and returns RESULT_OK, or RESULT_CANCELED with
  `EXTRA_MESSAGE`. `EXTRA_PACKAGE` makes Android refuse an APK for any other
  package. It draws no window.
- `Admin`: the device-admin receiver the device-owner role needs.

`InstallActivity` is guarded by a signature permission, so only apps signed
with the shared key in `../signing/` can call it. That key is what makes this
app safe to leave as device owner: never loosen the permission or sign the
app with another key.

No `:core` and no tests; check changes by building and by installing an app
through it on the phone:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :installer:app:assembleRelease
```
