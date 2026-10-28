# Installer

## Purpose

The XP3's own installer screen refuses any APK not signed by Sonim, so the
user's apps can't be installed or updated the normal way. Installer is the
device owner of the phone, which lets it install an APK with no confirmation
screen at all. The Updater (and, before it was retired, Delta) hands it an
APK and gets back whether it worked.

The user almost never opens it. They set it up once over adb and then only
see it indirectly, as the Updater's installs succeeding. Opening it answers
one question: is it set up?

It must stay tiny and inert:

- One status screen and one invisible install activity. No list of apps, no
  history, no settings, no network, no storage of its own.
- It installs only for apps signed with the shared key in `signing/`
  (`InstallActivity` is behind a signature permission). Never loosen that,
  never sign Installer with another key, never add a way for an unsigned
  app, a file manager or a browser to install through it. Being device
  owner is only safe because of this.
- It holds no device-admin policies and must never take any (no locking,
  wiping, restrictions, hidden apps).

## Not wanted

- Instructions on screen. The status page used to explain what device owner
  means and how to connect the phone; that was removed on purpose (DESIGN.md
  rule 7). Not set up shows the one adb command and nothing else.
- Error messages that tell the user what to do ("Open Installer for how to
  set it up" was cut to "Installer isn't the device owner.").
- Falling back to the system installer's confirmation screen. On the XP3 it
  always refuses, so Installer gives up instead.
- A window during installs. The caller stays on screen and shows progress.
- Tests or a `:core`: there is nothing Android-free to test (see AGENTS.md).

## Screens

### Status (MainActivity, the launcher icon)

A white page with 16dp padding, scrollable if it overflows. Contents depend
on whether Installer is the device owner, checked when the screen opens.

**Not set up** (not device owner):

- Heading "Not set up", 22sp, red.
- Under it, in grey monospace 14sp, exactly:
  `adb shell dpm set-device-owner com.installer.xp3/.Admin`
- No button, nothing else. Nothing is focusable; the d-pad and OK do nothing.
- The command wraps at 240px wide; all of it is visible without scrolling.

**Ready** (device owner):

- Heading "Ready", 22sp, dark green (`#1F4037`).
- No command line.
- One button, "Stop being device owner", focused when the screen opens.
  Focused, it is the accent green (`#2E5B4F`) with white text; unfocused,
  the stock grey.
- OK on the button opens the dialog below. Up/Down keep focus on it.

**Stop being device owner?** dialog (stock AlertDialog):

- Title "Stop being device owner?", no message.
- Buttons "Cancel" and "Stop". Left/Right move between them.
- Cancel, or Back, closes the dialog; the screen still says Ready.
- Stop gives up the role and the screen at once switches to Not set up
  (heading, command, no button). Installs through the Updater then fail with
  "Installer isn't the device owner." until the adb command is run again.

**Keys anywhere on the screen**: Back closes the app. There is no Menu, no
soft-key labels and no header (rule 8: no header names the app). Touch works
but is never needed.

To test Ready on the emulator: `adb shell dpm set-device-owner
com.installer.xp3/.Admin` (needs no accounts on the device), then open the
app. To test Not set up, choose Stop.

### Install (InstallActivity, no window)

Started with `startActivityForResult` by an app signed with the shared key:
data = the APK's content URI (with a read grant), optional string extra
`package` = the package name the APK must be. It draws nothing (translucent
theme, no animation, not in recents); the caller stays visible throughout.

Results to the caller:

| Situation | Result | `message` extra |
| --- | --- | --- |
| Installed | `RESULT_OK` | none |
| No data URI | `RESULT_CANCELED` | `No file to install.` |
| Not device owner | `RESULT_CANCELED` | `Installer isn't the device owner.` |
| URI unreadable | `RESULT_CANCELED` | `Can't read the file.` |
| Not enough storage | `RESULT_CANCELED` | `There isn't enough storage.` |
| `package` given and the APK is a different app | `RESULT_CANCELED` | `The file is a different app.` |
| Older than the installed version | `RESULT_CANCELED` | `A newer version is already installed.` |
| Corrupt or not an APK | `RESULT_CANCELED` | `The file isn't a working app.` |
| Signed differently from the installed app (or another conflict) | `RESULT_CANCELED` | `It doesn't match the installed app.` |
| Needs a newer Android or another CPU | `RESULT_CANCELED` | `It doesn't work on this phone.` |
| Blocked / aborted by Android | `RESULT_CANCELED` | `Android blocked the install.` / `The install was stopped.` |
| Any other rejection | `RESULT_CANCELED` | Android's message, or `The install failed.` |
| Android asks for confirmation anyway | `RESULT_CANCELED` | `Android asked for confirmation even though Installer is the device owner.` |
| The APK is Installer itself | `RESULT_CANCELED` | none: Android stops Installer to replace it; the caller checks the installed version |

- No confirmation screen ever appears, and nothing appears on success.
- Exactly one result is returned per call.
- A failed or abandoned session is cleaned up (no stray sessions left).
- Rotation or a keyboard change mid-install does not restart the install.
- An app not signed with the shared key gets a `SecurityException` when it
  tries to start InstallActivity; nothing is installed.
- Back during an install: the activity has no window, so Back reaches it
  and finishes it with `RESULT_CANCELED`; the install may still complete
  (see Open questions).

How to check: install or update an app from the Updater, which shows the
outcome. `adb shell am start -n com.installer.xp3/.InstallActivity` must be
refused with a permission error (the shell lacks the signature permission).

## Behaviour

- **Data**: none. Nothing is saved; `allowBackup` is off. The only state is
  the device-owner role, kept by Android across restarts and reboots until
  Stop is chosen (or a factory reset).
- **Network**: none.
- **Permissions**: `REQUEST_INSTALL_PACKAGES`; defines
  `com.installer.xp3.permission.INSTALL` (signature) and requires it of
  callers (and holds it itself); `Admin` is a device-admin receiver with no policies.
- **Threads**: the APK is streamed into a `PackageInstaller` session off the
  main thread (64 KB buffer); the outcome comes back as a broadcast to
  Installer only, taken only from a sender with the signature permission
  and only for the session it committed.
- **Timing**: no timeouts. An install takes as long as Android does.
- **Screen state**: the status page reads the role when it is created, not
  each time it is shown (see Open questions).
- Portrait only.

## Look

Not one of the family-look apps in DESIGN.md: a stock Material Light page
(white, status bar `#1F4037`, accent `#2E5B4F`) with a stock button and
dialog. Buttons read in sentence case, as written here, not in capitals.
Heading 22sp; command monospace 14sp dark grey. The launcher icon
sits in the family's 40-unit rounded square.

## Open questions

1. The status page checks the role only when it opens. If the user runs the
   adb command while it is on screen (or comes back to it from Recents), it
   still says Not set up until it is closed and reopened. Should it recheck
   on every return (`onResume`)? The removed text said "reopen this screen
   to check", so reopening may be intended.
2. If Android kills Installer's process mid-install and recreates the
   activity, it returns early without restarting the session or listening
   for the outcome, so the caller may never hear back and an invisible
   activity sits on top. Should it report a failure instead?
3. Back during an install returns Canceled to the caller while the session
   may still install the app. Should Back be ignored until the outcome
   arrives?
4. Should the status page follow the family look (DESIGN.md) like its
   siblings, or stay stock since it is opened about once?
5. The manifest's comment points to `README.md`, which Installer doesn't
   have; `AGENTS.md` now holds that. Harmless, but should the comment be
   corrected (that is a code change and so a version bump)?
