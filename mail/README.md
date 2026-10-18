# Mail, for the Sonim XP3

Gmail on the flip phone: read, reply, forward, write, archive, delete,
star, label, search, drafts and attachments. It is the phone end of
`delta gmail` in [thooton/delta](https://github.com/thooton/delta): the
Gmail login is the one `delta gmail login` keeps on the command center, and
the app signs in with the same delta token.

It looks and works like Thunderbird for Android, shrunk to the flip
phone's 240x320 screen and its keys. Three screens, and the folder pane:

- **The folder pane** (Left on the list): the account, then Gmail's
  folders (Inbox, Starred, Sent, Drafts, All mail, Spam, Trash), each with
  Thunderbird's icon and Gmail's count (unread, or how many drafts), then
  your labels, then the command center.
- **The list**: a folder's conversations, newest first. Each shows the
  sender's monogram, who is in it and when, the subject with a paperclip
  when there are files and the number of messages, and the start of the
  latest message. Unread ones are white with a blue dot and Medium type;
  read ones are grey. The bar shows the folder, the account and its unread
  count.
- **A conversation**: the subject, a star and your labels as tags, then
  every message under its own header (monogram, sender, date, to whom),
  opening at the first unread one. Read messages before the last are folded
  to a line (Menu → Expand all messages). A reply's quote of the message
  before it is hidden (Menu → Show quoted text). Links are blue, and files
  are listed under their message. Opening one marks it read.
- **Writing**: From, To, Cc (Menu → Add Cc), the subject and the message.
  Each finished address in To and Cc (ending in a comma) is drawn as a
  pill, red if it isn't an address. A reply's quote and a forward's copy of
  the original (with its attachments) are added under what you write when
  it is sent. Menu → Add recipient picks someone you have written to or
  heard from, matching what is typed after the last comma. What you are
  writing is kept if the app is closed.

| Key | List | Conversation | Writing |
| --- | --- | --- | --- |
| OK | Open (a draft opens to edit) | Menu | Next field; a new line in the message |
| Up / Down | Pick (past the last loads more) | Scroll | Move between fields |
| Left | Folder pane | Previous conversation | Move the cursor |
| Right | Open | Next conversation | Move the cursor |
| Back | Back to the inbox, then leave | Back to the list | Save draft, discard or keep writing |
| Menu | Everything, with its key | Everything, with its key | Send, add recipient, add Cc, save draft, discard |

In the folder pane Up and Down pick, OK or Right opens, and Left or Back
closes it.

The keypad's shortcuts, on the list and in a conversation (the menu shows
each beside its item):

| Key | Does |
| --- | --- |
| 1, 2, 3 | Reply, reply all, forward (in a conversation) |
| 4 | Archive (or move back to the inbox) |
| 5 | Star or unstar |
| 6 | Mark read or unread |
| 7 | Report spam (or not spam) |
| 8 | Labels |
| 9 | Delete (or restore from the trash) |
| 0 | Write a new message |
| * | Search (list); open a link (conversation) |
| # | Refresh (list); save an attachment (conversation) |

Folders are Gmail's own (Inbox, Starred, Sent, Drafts, All mail, Spam,
Trash) and your labels. Search takes Gmail's search syntax
(`from:jane has:attachment`). Delete moves to the trash, which Restore
undoes; nothing is deleted for good. Attachments are saved to the Download
folder on the SD card, never to the phone (the first save asks Android to
let Mail write there; say Allow); links in a message open in the browser, and an email
address starts a message to it.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :mail:core:selfTest :mail:app:assembleRelease
```

The command center login is baked in at build time, as in Source and
Places: `-Pdelta.center=<file>`, else `delta-center.json` beside `android/`,
else the CLI's `~/.config/delta/config.json`, else the `DELTA_SERVER` and
`DELTA_TOKEN` environment variables of an agent's sandbox. A build without
one keeps an installed copy's login; Menu → Command center sets one by hand.
The APK contains a live token, so treat it like a password; it is
gitignored.

Gmail itself has to be set up once on the command center with
`delta gmail login` (see the delta README's Gmail section).
