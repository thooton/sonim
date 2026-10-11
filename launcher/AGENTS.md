# Repository Guidelines

## Project overview

This repository contains a small, standalone Android app drawer for the Sonim
XP3800 (Android 8.1/API 27). It is intentionally an ordinary launcher activity,
not a replacement HOME app, and it requests no permissions. Preserve that
limited scope unless a task explicitly requires changing it.

The drawer starts with a default set of applications. Each default entry has an
explicit package/activity pair and a fallback label in `DEFAULT_TARGETS` in
`src/com/custom/appdrawer/AppDrawerActivity.java`; user-visible fallback text
lives in `res/values/strings.xml`. The Menu key opens a native context menu to
add any launchable app (chosen from a native `AlertDialog` list) or remove the
selected one; the resulting ordered list, with each entry's last-known label, is
persisted as JSON in private `SharedPreferences` and replaces the defaults once
saved. Missing applications must remain visible as dimmed entries and fail with
a toast rather than crashing.

## Repository layout

- `AndroidManifest.xml`: package, SDK compatibility, and launcher activity.
- `src/com/custom/appdrawer/AppDrawerActivity.java`: all application logic and
  the programmatic `GridView` UI.
- `res/`: selector, icon, colors, dimensions, strings, and theme resources.
- `res/values-mdpi/dimens.xml`: XP3800-specific mdpi icon-size override.
- `build.sh`: complete command-line build, alignment, signing, and verification.
- `AppDrawer.apk`: generated signed output; it is ignored by Git.
- `signing/`: generated local keystore; it is ignored and must never be
  committed or replaced casually because installs signed with a different key
  cannot update an existing installation.

## Build and verification

This is not a Gradle project. Do not add Gradle machinery just to build or test
it. Build from the repository root with:

```sh
./build.sh
```

The script expects `aapt`, `javac`, `zip`, `keytool`, Android platform 35, and
Android build-tools 35.0.1. It honors `ANDROID_SDK_ROOT` (then `ANDROID_HOME`)
and otherwise uses `/opt/android-sdk`. It compiles Java 8 source while keeping
the app's minimum and target SDK at API 27. A successful build ends by running
`apksigner verify --verbose` and writes `AppDrawer.apk`.

There is no automated test suite. For code or resource changes, run
`./build.sh` at minimum. For interaction changes, also install with
`adb install -r AppDrawer.apk` and verify on an XP3800 or equivalent API 27
device that D-pad focus starts on the first item, navigation and center-key
launching work, labels/icons render correctly, absent target apps remain
safe to select, and the Menu key's Add/Remove program actions update and
persist the grid.

## Change conventions

- Keep the implementation dependency-free and compatible with Android 8.1 and
  Java 8; do not use APIs newer than 27 without a guarded fallback.
- Follow the existing four-space Java indentation and Android XML formatting.
- Put user-visible text, colors, and dimensions in resources rather than adding
  new literals in Java. Preserve the mdpi override when changing icon sizing.
- Keep target order intentional: it controls both grid order and initial focus.
- When adding or removing a default target, update its `DEFAULT_TARGETS` entry,
  string resource, and the application list in `README.md` together. Changing
  the defaults only affects installs that have not yet saved a customized list.
- Preserve explicit component launching with a package-level fallback, and
  continue handling unavailable or non-exported activities without crashing.
- Do not introduce permissions, HOME intent categories, background services,
  analytics, or network access unless the requested feature specifically needs
  them and the documentation is updated to explain the broader scope.
- Do not commit generated APKs, temporary build output, IDE files, or anything
  under `signing/`.

## Documentation

Keep `README.md` aligned with user-visible behavior, supported applications,
build requirements, installation steps, and programmable-key usage whenever
those details change.
