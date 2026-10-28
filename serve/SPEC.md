# Serve

## Purpose

Serve shares one folder on the Sonim XP3 with anything on the same network,
as `npx serve` does on a computer. You pick a folder, the phone shows an
address, and a browser on a laptop, tablet or other phone opens that address
to browse the folder and download, play or view its files. It runs on the
XP3 (240x320, d-pad, soft keys, T9 keypad) and is driven one-handed with
keys, never touch. Usually it is opened, OK is pressed, and the phone goes
back in the pocket while it keeps serving.

It must stay one screen, one folder, one fixed port, read-only, and instant
to start. It must never become a file manager, an upload or sync tool, a
media server with its own UI, or anything that needs an account, a
password screen or settings. A new feature is judged by whether it makes
"share this folder now" quicker or more dependable on a flip phone.

## Not wanted

- No random or configurable port: it is always **28413**, so a bookmark
  keeps working (serve/AGENTS.md). If it ever must change, keep it above
  1024, below 32768 and off common dev-server ports.
- No writing of any kind: no upload, delete, rename or PUT/POST. GET and
  HEAD only.
- No header or bar naming the app (removed in 1.1.x; DESIGN.md rule 8).
- No key hints at the foot of the screen (removed early), and no text
  telling the user how to use it: no "Press OK to…", no keys named
  (DESIGN.md rule 7).
- No touch-first controls; everything works from keys.
- No restyling of the browser pages away from `npx serve`'s look: the
  listing and error pages follow serve-handler's templates on purpose.
- No third-party libraries, AndroidX or Material components.

## Screens

### Main screen (the only screen)

On a pale teal-grey page, with no header, top to bottom:

1. **Status card**
   - A state line with a dot: "Serving" in green Medium with a green dot
     while serving; "Not serving" in muted grey with a grey dot otherwise.
   - The address in large Medium text: `http://<ip>:28413`, with
     "http://" smaller and muted. The IP is the first Wi-Fi address
     (wlan/ap/swlan, so the phone's own hotspot counts), else any other
     non-loopback IPv4. With no network at all it reads "No network".
   - Under it, in small muted text, one line per further address
     (`http://<ip>:28413`). Hidden when there is only one.
   - The address is shown whether or not serving is on.
2. **Folder card**: a small muted label "Folder", then the folder shown
   under its storage: "Phone/Music", "SD card/DCIM", just "Phone" for the
   root, "Storage 3/…" for any further volume. "None chosen" before a folder
   has ever been picked. A long path wraps to at most 3 lines and is cut at
   the **start** with "…" so the end of the path stays visible.
3. **Button**, always drawn focused (it is what OK does): "Choose a folder"
   when none is chosen, "Start serving" when stopped, "Stop serving" while
   serving. It is not itself focusable.
4. **Problem line**, in red, centred, hidden unless something went wrong and
   serving is off (see Behaviour → Errors).

Keys:

| Key | Does |
| --- | --- |
| OK (centre / Enter) | Serving: stops. Stopped with a folder: starts serving it. No folder yet: opens the folder picker. Acts on key release. |
| Menu | Opens a menu: "Start serving" / "Stop serving" (does what OK does), "Choose folder…". Before a folder is chosen, only "Choose folder…". |
| Back | Leaves the app. Serving carries on under its notification. |
| D-pad, number keys | Nothing. |

The screen updates by itself when serving starts, stops (including from the
notification) or fails, and re-reads the addresses every time it is shown.

### Menu

A plain list dialog with no title and no buttons: two rows as above (one
before a folder is chosen). Up/Down
move, OK picks, Back closes it with nothing changed.

### Storage list (only with an SD card)

"Choose folder" with rows "Phone", "SD card" (and "Storage 3"… for more
volumes), and a Cancel button. OK opens that volume's folder list. Back or
Cancel closes it. With only the phone's storage, this step is skipped and
the folder list opens at the phone's root.

### Folder list

A list dialog titled with the folder it shows ("Phone", "Phone/Music"),
with a Cancel button. A long title is cut at the start with "…", as on the
main screen, so the folder itself is always named.

- First row: "✓ Serve this folder".
- Then each sub-folder as "name/", case-insensitive A–Z. Hidden folders
  (starting with ".") and files are not shown. An empty or unreadable folder
  shows only the first row.
- OK on "✓ Serve this folder": closes the picker, remembers the folder, and
  starts serving it straight away. If it was already serving another folder,
  it moves over to the new one (same address).
- OK on a folder: opens that folder's list.
- Back: goes up one level, with the folder just left selected. At the top of a volume, goes back to the storage
  list if there is a card, else closes the picker.
- Cancel: closes the picker entirely, from any depth; nothing changes.
- Up on the first row / Down on the last row stay put (stock list dialog).

### Notification (while serving)

Low-importance, ongoing (can't be swiped away), no time shown, teal accent.

- Title "Serving <folder name>" (just the last part of the path); text is
  the first address, or "No network". Briefly "Starting…" while it starts.
- Opening it brings the main screen back (no second copy of it).
- **Stop** action: stops serving and removes the notification; the main
  screen, if open, shows "Not serving".

### What the browser sees

- `/` and any folder without `index.html`: "Index of /a/b/" with each part of
  the path a link, then ".." (except at the root), then folders, then files,
  each group case-insensitive A–Z, with a folder, file or image icon. `.git`
  and `.DS_Store` are never listed or served; other dot-files are.
- A folder with an `index.html` is served as that page instead of a listing.
- A folder asked for without its trailing slash gets a 301 to the slash
  (query kept).
- A file is sent as-is with a type from its extension (unknown:
  `application/octet-stream`; text types with `charset=utf-8`),
  `Last-Modified`, and `Accept-Ranges: bytes`. A single byte range answers
  206; a range past the end answers 416; several ranges, or a malformed one,
  get the whole file. Audio and video can seek.
- Anything missing, outside the folder (via `..`, `%2F`, symlinks) or
  unreadable answers 404 "The requested path could not be found" in serve's
  error style. Malformed requests: 400. Methods other than GET/HEAD: 405 with
  `Allow: GET, HEAD`.
- Names with spaces, `#`, `?`, `&`, quotes or non-ASCII characters list,
  link and download correctly.

## Behaviour

- **Port**: always 28413, on every interface.
- **Data**: only the chosen folder's path, in the app's private
  preferences. It survives restarts and app updates. Nothing else is stored;
  no backup.
- **Restarts**: if Android kills the service for memory it comes back
  serving the same folder. After a reboot, or after the app is
  force-stopped, it is not serving until OK is pressed.
- **Background**: serving runs in a foreground service with a partial wake
  lock and a high-performance Wi-Fi lock, so it keeps answering with the
  phone shut. Both are released when serving stops.
- **Connections**: HTTP/1.1 keep-alive, a thread per connection, idle
  connections dropped after 15 s. Stopping drops every open connection,
  downloads included. Request heads over 16 KB are refused.
- **Permissions**: storage read is asked the first time a folder is chosen
  or serving starts; once granted, the action that asked carries on by
  itself. Refused: "No storage access", and nothing else happens; the line
  goes once access is granted.
- **Errors** (the red problem line, shown only while not serving):
  - Saved folder deleted or card removed, then OK: "That folder is gone".
  - Port already taken: "Port 28413 is in use". A failed start's message
    stays until a start succeeds.
- **Network changes**: the screen shows the current address each time it is
  opened. Serving needs no restart when the IP changes.
- **Security**: no password, no HTTPS. Anyone on the network can read the
  folder while it is served; that is accepted and stated in README.md.

## Look

DESIGN.md's family look in Serve's teal: dark frame `#0D3236`, accent
`#11707A`, page `#EEF3F3`, white cards. Green (`#12A150` dot, `#12783E`
text) only for "Serving"; red `#B3261E` only for the problem line. The
address is the biggest text on the screen; the main action is a visible
accent button, not a menu item. Launcher icon is a teal rounded square with
pale signal arcs.

## Open questions

1. The notification's address is taken when serving starts and is not
   updated if the phone's IP changes (e.g. switching Wi-Fi or turning on the
   hotspot) while it serves. Should it follow the network?
2. Files on the SD card that another app wrote may be unreadable even though
   they list (root AGENTS.md); Serve shows them in the listing but answers
   404 or drops the download. Should Serve ask for the card grant
   (`CardGrant`) when the folder is on the card, or is that acceptable?
3. If storage permission was refused with "Don't ask again", OK just shows
   "No storage access" each time, with no way forward inside the app. Is
   that acceptable, or should it open the app's settings?
4. A failed start's message (e.g. port in use) stays on the screen across
   later visits until a start succeeds, even after the cause has gone.
   Should it clear when the screen is reopened?
5. The folder picker hides dot-folders, but the browser listing shows
   dot-files and dot-folders (as serve-handler does). Intended?
6. With no storage volume found at all (unusual), "Choose folder" shows an
   empty storage list. What should it show?
