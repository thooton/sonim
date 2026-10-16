# Serve, for the Sonim XP3

Shares a folder on the phone with anything on the same network, as `npx serve` does on a computer: choose a folder, and open the address on the screen in a browser to get its listing, folders first, and every file in it. A folder with an `index.html` is served as that page.

It always uses port **28413**, so the address only changes when the phone's IP does, and a bookmark keeps working. Wi-Fi addresses (including the phone's own hotspot) are shown first.

| Key | |
| --- | --- |
| OK | Start or stop serving (chooses a folder the first time) |
| Menu | Start/Stop, Choose folder |
| Back | Leave; serving carries on under its notification |

Serving runs in the background with a notification, which has a **Stop** button, and keeps going with the phone shut. Audio and video can seek, since byte ranges are supported. It is read-only, and there is no password: anyone on the network can read the folder while it is being served.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :serve:core:selfTest :serve:app:assembleRelease
```
