# Authenticator

## Purpose

Two-factor codes (TOTP) on the Sonim XP3, drawn as Authy's list view: one
list of accounts, each with its current code, and nothing else on the
screen. The user opens it with one hand on the flip phone, finds the
account with Up and Down, reads the code (or copies it with OK) and closes
it. It must open straight to the codes, show them without a connection,
and stay quick and plain.

The accounts are kept on the Delta command center, so a lost or reset
phone loses none; the phone keeps a copy so codes show at once and offline.
Codes are worked out on the phone (RFC 6238), so the phone's clock must be
right.

It must stay extremely simple: the list, OK to copy, and Menu's Add by
camera, Add by image, Add manually, Edit and Delete. It must never become
a password manager, a settings-heavy app, or anything with more screens
than the list and the entry form.

## Not wanted

The user decided against these (authenticator/AGENTS.md); don't propose them:

- Search, sorting, or a grid view.
- A PIN, lock screen or any other gate before the codes.
- Backups or exports from the app (the command center is the backup).
- A header, title bar or app name above the list.
- Counter-based (HOTP) accounts.
- Eased or smooth scrolling.
- A camera permission (the phone's camera app is asked for a photo instead).

## Screens

### The list

Opens on launch. Portrait only.

- No header: the first card starts at the top of the screen, under the
  status bar.
- One white pill card per account, in the command center's order (the app
  never sorts). Each card has, left to right: the badge (the service's logo,
  or its initial on a coloured disc) inside a ring; then on one line the
  service in Medium followed by the account in brackets, smaller and muted,
  e.g. "GitHub (sam@example.com)"; under it the code.
- An account with no service leads with the account name and has no
  brackets.
- A long name line is cut with "…" at the end; it never wraps or pushes
  the code off the card.
- The code is grouped in halves with a space: "482 963", "1234 5678",
  "123 4567". Figures are even, so the code doesn't shift as it changes.
- The code changes the moment its period rolls over (checked four times a
  second). The ring runs down clockwise from twelve o'clock over the
  code's period, on a pale grey track, and is full again when the code
  changes.
- The card under the d-pad is outlined in navy; only one card is outlined.
- On launch the first account is selected.

Keys:

| Key | Does |
| --- | --- |
| Down | Selects the next account. On the last account it stays there. |
| Up | Selects the previous account. On the first it stays there. |
| OK | Copies the selected account's code (digits only, no space) and toasts "Copied". |
| Menu or Left soft key | Opens the menu (on key release). |
| Back | Leaves the app. |

- Up and Down move exactly one account per press, at once, with no
  easing. An account already wholly on screen is selected where it is (the
  list doesn't move). One that isn't is brought just into view at the edge
  it comes from, so the list moves by about one card. Repeated presses
  never make the list creep.
- While the app talks to the command center a thin navy bar runs along the
  top of the screen; the list stays usable under it.

### The empty list

- With a command center set and no accounts: "No accounts", centred, and
  under it Authy's round navy + button, which has the focus. OK on the +
  opens the menu, as Menu does.
- With no command center set: "No command center" instead of "No accounts".
- If the last sync failed, a muted line under the title says why
  ("Offline", "The command center didn't answer", …).
- On the very first launch, while the first sync runs and nothing is kept
  yet, only a small spinner shows in the middle.

### The menu

A rounded popup hanging from the top of the screen, whichever card is
selected, so every item shows. Items, in order:

1. Add by camera
2. Add by image
3. Add manually
4. Edit (only when an account is selected)
5. Delete (only when an account is selected)
6. Command center (only when no command center is set, the list is empty,
   or the last sync failed)

Up/Down move in it, OK picks, Back closes it and changes nothing.

- **Add by camera** opens the phone's camera app. After a photo is taken,
  the QR code in it is read and its accounts added (see Adding). Backing
  out of the camera does nothing. With no camera app: toast "There's no
  camera app".
- **Add by image** opens the phone's picture picker (the gallery if nothing
  else answers). The picked picture's QR code is read and added. Backing
  out does nothing. With no picker: "There's no app to pick a picture with".
- **Add manually** opens the entry form, empty.
- **Edit** opens the entry form for the selected account.
- **Delete** asks "Delete <service>?" with the account name under it (no
  line when there is none), and Delete / Cancel buttons. Cancel or Back
  changes nothing. Delete removes it on the command center, toasts
  "Deleted <service>" and selects the account now in the same place (the
  new last one if it was last). Deleting the only account shows the empty
  list.
- **Command center** opens the login dialog.

Reading a picture shows the top bar while it works, then on failure toasts
one of: "No QR code found", "That picture can't be read", "That isn't a
picture", "That picture can't be opened", "The camera didn't send a photo",
"That QR code isn't for two-factor codes", "Only time-based codes are
supported".

### The entry form (Add account / Edit account)

A scrolling page on the same pale grey, its title "Add account" or "Edit
account", then labelled white pill fields, each outlined in navy while it
has the keys:

- Service (words capitalised), Account, Secret key (capitals, no
  suggestions);
- in one row: Digits (default 6), Seconds (default 30), Algorithm
  (default SHA1);
- a full-width navy button, "Add" or "Save".

Focus starts in Service with the cursor at the end. Down/Up move one step
through Service, Account, Secret key, Digits, Seconds, Algorithm and the
button, in that order; Enter (or the keyboard's Next) in a field goes to
the next one, and in Seconds it saves, as OK on the button does.

- Algorithm is not typed: OK or Right steps SHA1 → SHA256 → SHA512 → SHA1,
  Left steps back.
- The Secret key takes the base32 key a service shows ("Can't scan it?"),
  spaces and lower case allowed, or a whole `otpauth://` or
  `otpauth-migration://` link.
- Back leaves the form and saves nothing.

Editing: the fields start as the account has them, except Secret key,
which is empty with "••••••••" as its hint. Left empty, the account keeps
its key. A link typed there when editing takes the link's key, digits,
seconds and algorithm, and its service and account only for fields left
empty.

Checks on save; each toasts its message, keeps the form open and moves the
focus to the field at fault:

- Neither Service nor Account: "The account needs a name" (Service).
- Digits not 6 to 8: "Codes have 6 to 8 digits" (Digits).
- Seconds not 1 to 300: "A code lasts 1 to 300 seconds" (Seconds).
- Empty key when adding: "The secret key is empty"; a key with other than
  letters and 2–7: "The secret key can only have letters and the digits 2
  to 7" (Secret key).
- A bad link toasts what is wrong with it ("That two-factor link has no
  secret key", "Only time-based codes are supported", …).

A form that passes closes, back to the list.

### Adding and saving

- Added accounts go to the command center; when it answers, the list is
  its new list and the first account added is selected.
- Toasts: "Added <service>", "Added 3 accounts", "<service> is already
  here" (same secret already in the list), or "These are already here".
- A Google Authenticator "Transfer accounts" code adds every TOTP account
  in it at once.
- After Edit: "Saved <service>", and that account stays selected.
- With no command center set, adding or saving opens the login dialog;
  once a login is saved there, the account is added (or saved).
- What was typed in the form is never lost: if the command center can't
  take it (offline, a refusal, or the login dialog cancelled), the form
  opens again as it was typed, after the toast saying why.

### The login dialog (Command center)

Title "Command center", an address field (hint "https://…", filled with
the saved address), a token field (masked; hint "Token", or "••••••••"
when one is saved), Save and Cancel.

- Down and Up move between the address, the token and the buttons.
- Save with an empty token keeps the saved one.
- Saved: toasts "Saved: <host>" and syncs at once.
- Missing address or token: "Both the address and the token are needed".
- Cancel or Back changes nothing.
- It opens by itself on launch when the build carries no login and none
  was saved.

## Behaviour

- **Where data lives.** The accounts are on the command center (`GET /otp`,
  `POST /otp`, `PUT /otp/{id}`, `DELETE /otp/{id}`, with the delta token);
  every call answers with the whole list. The last list is kept in the
  app's files (`accounts.json`, private) and shown at once on launch. The
  login is kept in the app's preferences. Logos are kept in `files/logos`.
- **Survives a restart:** the accounts, the login, the logos. Not kept:
  which account was selected (launch selects the first).
- **Sync** runs on launch, and on coming back to the app more than ten
  minutes after the last successful sync. The selected account stays
  selected across a sync (matched by its secret) if it is still there.
- **Offline.** Codes keep working from the kept list. A failed sync leaves
  the list as it was and adds Command center to the menu. Adding, editing
  and deleting need the command center; when one fails, a toast says why
  and nothing changes (an add or edit from the form reopens it). No
  connection is always "Offline", wherever it shows. Messages: "Offline", "The command center didn't
  answer", "The command center needs updating for two-factor accounts", or
  the command center's own words about a bad account.
- **Network.** HTTPS only (cleartext is refused), 30-second deadline per
  request. Logos are fetched from the service's own site, then Google's
  favicon service; three bundled logos (Twitter's bird, Tuta, SpigotMC)
  are never fetched. Until a logo arrives, and for services without one,
  the initial on a coloured disc shows. A logo no account uses any more is
  deleted when the list changes.
- **Permissions.** Internet only. No camera or storage permission; the
  camera app writes its photo to a file the app lends it for that one
  request, which is deleted after reading.
- **Secrets** never show on screen once saved, are never logged, and the
  app is excluded from Android backup.
- **Timed.** Codes and rings update four times a second while the app is in
  front, and stop when it isn't.

## Look

What authenticator/AGENTS.md says ("Look: Authy's list view") is the look;
it sets the family palette aside. In short, for checking:

- Page `#F4F4F6` (status and navigation bars too, with dark icons); cards
  white, fully rounded ends, faint shadow; names, codes, the + button and
  the focus outline navy `#121C2D`; the bracketed account `#606B85`; ring
  track `#E1E3EA`.
- Inter throughout (Regular and Medium); code 21sp, name line 12sp, the
  account part 10sp.
- Cards are 52dp tall, 7dp from the screen's sides; about five fit the
  screen.
- Dialogs and the menu use the family's rounded FamilyDialog and
  FamilyPopupMenu.

## Open questions

1. Command center is hidden from the menu while the list has accounts and
   the last sync worked, so a changed token can't be typed in until a sync
   fails. Is that intended, or should it always be in the menu (last)?
2. Delete doesn't open the login dialog when no command center is set, as
   Add and Edit do; it toasts "There is no command center login". Should it
   match them?
3. The right soft key does nothing on the list. Should it (for example
   copy, or open the menu like the left one)?
4. Digits "0" (and Seconds "0") on the form are taken as the defaults (6
   and 30) rather than refused. Intended?
5. Copying a code with a second or two left gives one that expires at once.
   Should OK wait for, or copy, the next code near the end of the period?
