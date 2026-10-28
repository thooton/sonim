# Tree, for the Sonim XP3

The phone's front end for the command center's nightly QA, which every
night has cloud agents go through each phone app (sonim) and tablet app
(thooton/tablet): write its spec, drive it in an emulator, file what they
find, and fix it. Tree shows the morning summary, every app and what is
open in it, and each finding; it switches growing on or off per app, gives
grown features a 👍 or 👎, runs the QA now, cancels a run, and sets the
nightly hour.

| Screen | Keys |
| --- | --- |
| Home | Up/Down move, OK opens the run or the app, Menu: Run QA now (or Cancel run), Refresh, Turn nightly on/off, Nightly hour, Command center |
| A run | Up/Down scroll, OK runs QA now (or cancels the run), Menu: the same, Refresh |
| An app | Up/Down move, OK on Grow switches it, on Run QA now runs this app, on a finding opens it; Menu: growing, Run QA now, Refresh |
| A finding | Up/Down scroll; for a grown feature Left/Right pick 👍 or 👎 and OK gives it (OK again clears it); Menu: Keep, Take out, Clear verdict |

The left soft key is Menu and the right one Back everywhere.

It signs in to the command center as the other apps do: a build made where
the delta CLI is signed in carries the login; otherwise Menu's Command
center takes the address and the token.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :tree:core:selfTest :tree:app:assembleRelease
```
