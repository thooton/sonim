# Mail, for the Sonim XP3

Gmail on the flip phone: read, reply, forward, write, archive, delete,
star, label, search, drafts and attachments. It is the phone end of
`delta gmail` in [thooton/delta](https://github.com/thooton/delta): the
Gmail login is the one `delta gmail login` keeps on the command center, and
the app signs in with the same delta token.

Three screens:

- **The list**: a folder's conversations, newest first. Each shows who is
  in it (and how many messages), when, the subject (★ when starred) and the
  start of the latest message; unread ones are in bold. The bar shows the
  folder, the inbox's unread count, and which conversation is picked.
- **A conversation**: every message in it, oldest first, opening at the
  first unread one. A reply's quote of the message before it is hidden
  (Menu → Show quoted text). Opening one marks it read.
- **Writing**: To, Cc (Menu → Add Cc), the subject and the message. A
  reply's quote and a forward's copy of the original (with its attachments)
  are added under what you write when it is sent. Menu → Add recipient
  picks someone you have written to or heard from, matching what is typed
  after the last comma. What you are writing is kept if the app is closed.

| Key | List | Conversation | Writing |
| --- | --- | --- | --- |
| OK | Open (a draft opens to edit) | Menu | Next field; a new line in the message |
| Up / Down | Pick (past the last loads more) | Scroll | Move between fields |
| Left / Right | Previous or next folder | Previous or next message | Move the cursor |
| Back | Back to the inbox, then leave | Back to the list | Save draft, discard or keep writing |
| Menu | Write, archive and the rest, search, folders, refresh | Reply, reply all, forward, archive, delete, mark unread, star, spam, labels, save attachment, open link | Send, add recipient, add Cc, save draft, discard |

Folders are Gmail's own (Inbox, Starred, Sent, Drafts, All mail, Spam,
Trash) and your labels. Search takes Gmail's search syntax
(`from:jane has:attachment`). Delete moves to the trash, which Restore
undoes; nothing is deleted for good. Attachments are saved to the phone's
Download folder; links in a message open in the browser, and an email
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
