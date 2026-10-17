# AGENTS.md

A small, dependency-free Android app for the Sonim XP3 that reads and sends
the user's Gmail. A sibling of `../places`, `../source`, `../delta` and the
rest, under the same constraints: 240x320 mdpi, d-pad driven, no AndroidX or
third-party libraries, `:core` plain Java `--release 8` running on API 27.
Keep it simple: a mail client a flip phone can drive, not Gmail's every
feature.

## How it works

Mail works the way `delta gmail` does (`cmd/delta/gmail*.go`,
`cmd/delta-cc/gmail.go` in thooton/delta). The command center keeps the
login: `GET /gmail-login` with the delta token answers `{"client_id",
"client_secret", "refresh_token", "email"}` (404 while `delta gmail login`
hasn't been run). `Gmail` fetches it when first needed and keeps it in
memory only, never on disk, trades the refresh token at
`oauth2.googleapis.com/token` for an hour-long access token (renewed a
minute early, and once more if Gmail answers 401), and calls
`gmail.googleapis.com` itself, so the command center never sees any mail.
The scope is `gmail.modify`: nothing can be deleted for good, only trashed
(a draft can be deleted).

- **Lists** are `threads.list` (`Folder`: a query such as `in:inbox`, or a
  label id; Spam and Trash add `includeSpamTrash`), 20 at a time with
  Gmail's `pageToken`, then each thread's metadata (From, To, Cc, Subject,
  Date), five at a time as the CLI does. The inbox also asks
  `labels/INBOX` for its unread count. The user's labels and send-as
  addresses (`Me`, for "me" in the list and the From of a reply) are
  fetched with the first list.
- **A conversation** is `threads/{id}?format=full`, kept in memory by id.
  `Message.Content` walks the MIME tree as the CLI does: the first
  text/plain and text/html, every file (a filename, `attachment`
  disposition, or a Content-ID on a non-text part). The text shown is the
  plain part, or the HTML as text (`Html`) when the plain part is a stub.
  `Mime.unquoted` hides a reply's quote of the one before.
- **Changes** (`Change`: archive, delete, mark read or unread, star, spam
  and their opposites) are thread modifies, or `trash`/`untrash`. They are
  applied to the phone's copies first, so an archived conversation leaves
  the inbox at once (`Folder.holds`), and sent in order on their own
  thread; a failure says so and reloads.
- **Writing** is `Outgoing`: a new message, a reply (addressed as Gmail and
  the CLI do: Reply-To or sender, the user's own message to its recipients
  again, reply-all copying the rest minus the user), a forward (the CLI's
  "Forwarded message" block, the original's HTML beside it, its
  attachments carried over), or a draft opened again. What is typed is
  `text`; the quote or forwarded copy is `tail`, added when sent. `raw`
  writes RFC 5322, base64 bodies, ASCII throughout. Sending and saving go
  through the multipart upload endpoints with the thread id (a reply
  joins its conversation: In-Reply-To, References and the thread id). A
  draft is saved in place once it has an id and sent with `drafts/send`.
  The message being written is kept in SharedPreferences (`mail`:
  `compose`) while the app is stopped.
- **People** (`mail`: `people`) are the addresses seen in listed mail and
  those written to (first), for Menu → Add recipient.
- **Attachments** are fetched whole (the shared `Http` caps a response at
  8 MB, so about 6 MB of file) and written to the shared Download folder,
  asking for storage access on first use.

If the CLI's addressing, quoting or forwarding changes, change `Outgoing`
here and keep the test in step.

- `android/core`: `net/` over the shared `Http`, `Json` and `Login`
  (`../common/core`): `Gmail` (the login and every request), `Folder`,
  `Conversation`, `Message`, `Attachment`, `Change`, `Me`, `Outgoing`,
  `People`, `Address` (parsing and writing address lists), `Mime`
  (encodings, charsets, quoting) and `Html` (mail HTML as text, and its
  links). Tests in `src/test` run via `:core:selfTest`, against a loopback
  server standing in for the command center, Google's token endpoint and
  Gmail.
- `android/app`: `MainActivity` (the one screen: the folder pane, the
  list, a conversation, writing, the menus and their keypad shortcuts, the
  login dialog), `Sheet` (a screen's actions: the menu, with each one's key,
  and the lookup by key), `Pills` (address pills in To and Cc, and the
  reader's label tags) and `Avatar` (monogram letters). Its look is
  Thunderbird for Android's (see "Mail" in `../DESIGN.md`): keep it so, and
  take new icons from thunderbird-android's
  `core/ui/legacy/designsystem/src/main/res/drawable` with the same
  attribution comment.
- The folder pane's counts are `Gmail.counts` (`labels/{id}` for each
  folder's `Folder.countedLabel`: unread, or every draft), fetched beside
  the list at most once a minute. The list's paperclip is
  `Message.hasFiles`: a named part, or a multipart/mixed body, which is all
  a list's metadata shows.

## Credentials

`app/build.gradle` bakes the login into a generated `Bundled.java` under
`build/`, from the same places as Source and Places (`deltaCenter` in
`../gradle/bundled.gradle`). A bundled login is adopted once per revision.
Never print, commit or attach the config files, the generated source, the
Gmail login or the APK.
