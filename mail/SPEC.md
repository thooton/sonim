# Mail

## Purpose

Gmail on the Sonim XP3 flip phone: read, reply, forward, write, archive,
delete, star, label, search, drafts and attachments, driven one-handed with
the d-pad, OK, Back, Menu and the T9 keypad (never touch) on a 240x320
screen. It is the phone end of `delta gmail`: the command center keeps the
Gmail login, and the phone talks to Google itself.

It must stay a mail client a flip phone can drive: one screen at a time,
quick to open, every action reachable from Menu, the common ones on a
single keypad key. It must never become Gmail's every feature (no
settings screens, multiple accounts, filters, rich-text editing, offline
mailbox), never store the Gmail login on the phone, never delete mail for
good, and never save files to the phone's own storage.

## Not wanted

- Text that explains how to use the app ("Press Menu to…", keys to press,
  example input, a sentence under an empty state saying what it is for).
- The conversation's position ("3 of 20+") in the bar while reading.
- A header naming the app: the bar names the folder or what is written.
- Smooth scrolling of the list as the pick moves (it ran from the top
  back down); the list jumps just enough to show the picked row.
- Showing a conversation in parts while it loads: a spinner until it is all
  there.
- Saving attachments on the phone's own storage; only the SD card.
- Deleting anything for good (the login's `gmail.modify` scope can't).
  Delete means move to Trash. Only a draft can be deleted.
- Keeping the Gmail login (client secret, refresh token) on disk.
- The old oxblood family band: Mail looks like Thunderbird for Android.

## Screens

One screen shows the list, a conversation, or a message being written;
the folder pane slides over the list from the left. Menu on any screen
(also from the pane, which it closes) opens that screen's menu: a rounded
list of actions, each with an icon and, if it has one, its key on a keycap.
Menu while sending or saving shows nothing.

### Starting

- With no command center login (none bundled, none typed), the list is
  empty with "Mail isn't set up" and the Command center dialog opens.
- With a login, the Inbox loads. If a message was being written when the
  app was last stopped, writing opens instead with it, and the status line
  says "Your unfinished message was kept."
- Coming back to the app on the list more than 2 minutes after it last
  loaded fetches it again.

### The list

Bar: menu icon, the folder name, under it the account address (or what is
under way, e.g. "Loading Inbox…", "Checking for mail…", "Loading more…"),
with a thin progress bar while busy, and on the right the folder's unread
count (999+ above 999; none at 0; none for Drafts). A status line under
the bar shows errors and notes, and takes no room when empty.

Each row: the monogram of the other person (in Sent, whom it went to), an
unread dot, who is in it ("me" for the user; in Sent "To: …"; "Draft" in
red for unsent; "(nobody)" if no one), a star if starred, the date
("3:04 PM" today, "Oct 13" this year, "10/13/25" before), the subject
("(no subject)" when empty) with a paperclip when it has files and a
message count badge when more than one (99+), and the start of the latest
message. Unread rows are white, Medium, with a blue time; read rows grey.
Newest first, 20 at a time.

- Up/Down move the pick. Up on the first row stays. Down on the last row
  loads the next 20 (and moves onto the first new one); with nothing more,
  it stays.
- OK or Right opens the picked conversation; one holding only a draft
  opens in writing instead ("Opening the draft…"; if it was sent or
  deleted meanwhile: "That draft was sent or deleted.").
- Left opens the folder pane.
- Back: while loading, stops it; else in any folder but Inbox, goes to
  Inbox; in Inbox, leaves the app.
- Keys: 0 write; 4 archive (or Move to inbox); 5 star/unstar; 6 mark
  read/unread; 7 report spam (or Not spam); 8 labels; 9 delete (or Restore
  in Trash); * search; # refresh. 1 to 3 do nothing here. A key whose
  action isn't offered for the picked conversation does nothing.
- Menu: Stop (while busy), Write, Open email file…, the conversation
  actions for the picked row, Labels… (only if the user has labels),
  Search…, Folders (◀), Refresh, Load more (when there is more), Command
  center….
- Empty: the folder's icon and one of "Loading Inbox…" (what is under
  way), "Inbox didn't load" (with the reason in the status line), or
  "Nothing in Inbox", naming the folder shown.

### The folder pane

Slides in from the left over a scrim. At its head the account's name (or
"Gmail") and address (or "Not set up"). Then Inbox, Starred, Sent, Drafts,
All mail, Spam, Trash; the open search, if any, as “query”; a Labels
heading and the user's labels; an Account heading and Command center. Each
folder has its Thunderbird icon and a count: unread for Inbox, Spam and
labels (blue), every draft for Drafts (grey); none at 0, 999+ above 999.
The row under the d-pad is a filled blue pill; the open folder is washed
pale blue. It opens on the open folder.

- Up/Down move, skipping headings; at the first or last row they stay.
- OK or Right opens that folder (the list starts at its top) or runs
  Command center; choosing the open folder just closes the pane.
- Left or Back closes it. Other keys do nothing.
- Counts are fetched when the pane opens if over a minute old, and after
  any change; a failed fetch keeps the old counts silently.

### A conversation

Bar: back arrow and the folder name. Then the subject (16sp Medium), and
under it "★ Starred" (gold) and the user's labels as pills. Then each
message: monogram, sender ("Draft" after it in red for a draft), date, and
"to me, Jane +2 · 3:04 PM" (the time only when not today). Read messages
before the last are folded to their header and first words; unread ones,
drafts and the last are open. An open message shows its text with web
addresses blue and underlined, "Quoted text hidden" where a reply's quote
of the one before was cut, "(no text)" when empty, and its files as cards
(name, size, type) under "2 attachments, 1.2 MB". Text over 20,000
characters is cut with "…" on screen. It opens scrolled to the first
unread message, or the last.

A conversation not opened before shows a spinner until it has all
loaded; if it fails, "This conversation didn't load". Opening an unread
one marks it read.

- Up/Down scroll by about 60dp.
- Left/Right open the conversation before/after it in the list. At the
  first: toast "This is the first in Inbox". At the last: "This is the last
  in Inbox", or "Last one loaded" when the list has more to load.
- OK or Menu opens the menu. Back returns to the list, pick unchanged.
- Keys: 1 reply, 2 reply all (only offered when the message had more than
  one recipient), 3 forward; 4 to 9 and 0 as on the list; * open link…;
  # save attachment…. Other printing keys do nothing.
- Menu: Stop (while busy), Reply, Reply all, Forward, Edit draft (if it
  has one), the conversation actions, Labels…, Save attachment… (if any
  files), Open link…, Expand all messages / Fold read messages (more than
  one message), Show / Hide quoted text, Write, Back to <folder>.
- Reply, forward and Open link act on the message at the top of the
  screen (or the last in sight once scrolled to the end); on a draft, the
  latest message.
- Open link lists that message's links (cut to 80 characters): a web link
  opens in the browser ("Nothing on this phone can open that." if none
  can); a mail address starts a new message to it. No links: "No links in
  this message."
- Save attachment: one file saves at once; several give a "Save to SD
  card" list. None: "No attachments here." (see Behaviour).

### Conversation actions

Archive (in Inbox) or Move to inbox; Delete or Restore (in Trash); Mark
read or Mark unread; Star or Unstar; Report spam, or Not spam (in Spam;
neither in Trash). Each shows at once on the phone with a toast (Archived,
Moved to the trash, Restored, Marked read/unread, Starred, Unstarred,
Reported as spam, Moved to the inbox), then goes to Gmail in order in the
background. One that takes the conversation out of the folder (archive in
Inbox, delete, unstar in Starred) removes it from the list, keeping the
pick on the row after it, and closes the conversation if open; so does
Mark unread. The Inbox count follows read/unread at once.

Labels…: a checklist of the user's labels, ticked as the conversation has
them; OK applies the difference ("Labels changed"), Cancel nothing. With
no labels of the user's: "There are no labels of your own."

### Writing

Bar: a close icon, "New message", "Reply", "Forward" or "Draft", and a blue
send icon. Lines: From (name, then address in grey; hidden until the
account is known), To, Cc (only when it has addresses or Menu → Add Cc),
Subject, "N attachments, size" when a forward carries files, then the
message (hint "Message"). Each address finished with a comma is drawn as a
pill, red if it isn't an address.

Focus starts in To if empty, else Subject if empty, else the message (at
its start for a reply or forward, at the end for a draft). A reply is
addressed as Gmail does and its quote, or a forward's copy of the original
with its files, is added under the text when sent, not shown.

- OK in To moves to Cc (if shown) or Subject; in Cc to Subject; in
  Subject to the message; in the message, a new line.
- Up/Down move between fields; Left/Right move the cursor.
- Back: if nothing was changed, returns where writing began (the list or
  the conversation). Otherwise a menu: Save draft, Discard, Keep writing.
- Menu: Send, Add recipient…, Add Cc (until shown), Save draft, Discard.
  No keypad shortcuts here: the keys type.
- Send with nobody in To/Cc: status "Who is it to? Fill in To."; a bad
  address: "Not an address: …". Otherwise "Sending…", then toast "Sent"
  and back where writing began (a conversation reloads with the reply in
  it; the list reloads). A failure leaves the message as it was with the
  reason in the status line.
- Save draft: "Saving the draft…", toast "Saved to Drafts", back. A draft
  opened again is saved in place, not duplicated.
- Discard: a new message goes at once ("Discarded"). A saved draft asks
  "Delete this draft from Gmail too?": Delete ("Draft deleted") or Keep it.
- Add recipient… lists (up to 100) people known from mail whose name or
  address matches what is typed after the last comma, else everyone known
  (with "Nobody matches "…""), titled "To" or "Copy to" for whichever field
  has the cursor; picking one adds it as a pill. Nobody known: "No
  addresses yet".

Suggestions: typing in To or Cc shows up to four people under the field:
known people matching what follows the last comma (a word's start before
its middle), then, after a 300 ms pause and from two letters, the user's
organization and Gmail's other contacts. Down/Up move through them (Up
from the first returns to the field), OK takes the picked one, or the first
when what is typed isn't yet a whole address, replacing what was typed
with a pill and a comma; Back hides them. Leaving the field hides them.

### Dialogs

- Search mail (*): a one-line field, prefilled with the open search;
  Search opens the results as a folder named “query” (Gmail syntax, e.g.
  `from:jane has:attachment`); empty or Cancel does nothing. Back from it
  returns to Inbox.
- Command center: address ("https://…") and token (dots when one is
  saved; left empty, the saved one is kept). Save with either missing:
  "Both the address and the token are needed". Saved: "Saved: <host>" and
  the list reloads.
- Open email file…: asks for storage the first time ("Storage not allowed"
  if refused). Lists `.eml` files from Documents and Download on the SD
  card and the phone, newest first, without ".eml". None: "No email files
  in Documents or Download". The one picked opens as a new message with
  its To, Cc, Bcc, Subject and text, sent from the user's own address.

## Behaviour

- **Login.** A login baked into the build is adopted once per build; else
  the one typed in is kept (SharedPreferences `mail`). The Gmail login is
  fetched from the command center when first needed and held in memory
  only. Errors are phrased for the user, e.g. "Gmail isn't set up on the
  command center. Run `delta gmail login` on a computer.", "The Gmail login
  has expired or was revoked…".
- **Network.** Every action is online; there is no offline copy. A failed
  load leaves what was shown and says why in the status line. A failed
  change says "Couldn't do that: …" and reloads the list (labels: "Couldn't
  change the labels: …"). A conversation once opened is kept in memory
  while the app runs. Starting new work abandons what was running; Menu →
  Stop or Back on the list stops it.
- **Kept across restarts:** the login, the message being written (until
  sent, saved or discarded), the people known from mail (up to 300; those
  written to first), and the SD card grant. Nothing else: the list is
  fetched afresh.
- **Attachments** are fetched whole (about 6 MB at most: "That is too big
  for the phone to fetch.") and saved only to the SD card. No card: "No SD
  card: put one in to save files". The first save asks Android to let Mail
  use the card's Download folder; allowed, files go to SD
  card/Download (a taken name gets " (1)"), toast "Saved to SD
  card/Download/<name>". Refused, they go to SD
  card/Android/data/com.mail.xp3/files/Download (a taken name gets " (2)").
  A different card asks again.
- **Timed:** list refetched after 2 minutes away; folder counts at most
  once a minute; people search after a 300 ms pause.
- **Permissions:** Internet; storage read for email files.

## Look

Thunderbird for Android, per "Mail" in DESIGN.md: light warm-grey bar and
status bar (`#F1EDEC`), accent `#004F9B`, folder pane on `#FCF8F8`,
Thunderbird's Material Symbols icons, monograms in a 15% accent circle.
Gold star, red for drafts and bad addresses.

## Open questions

- Discarding a saved draft and answering "Keep it" leaves writing and
  drops any edits made since it was last saved, without asking. Should
  "Keep it" save those edits, or return to writing?
- An email file's Bcc is sent but never shown while writing. Should Bcc be
  visible (and editable) there?
- A failed label change says so but, unlike other changes, doesn't reload,
  so the phone keeps showing labels Gmail doesn't have. Intended?
- A kept unfinished message opens before the account is known, so its
  From line is hidden until something loads. Fine as is?
