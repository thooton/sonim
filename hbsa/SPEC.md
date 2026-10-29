# Hbsa

## Purpose

HBSA tells the user when the club's events are. It reads one public
Instagram account, @hbsaasu, and turns its latest posts into an agenda: each
event's day, start time and name, with its post's picture and caption. The
user has no Instagram; they open the app on the XP3 flip phone, one-handed,
with the d-pad and soft keys, to see what is coming up and when.

It must stay small, quick and single-purpose: one account, read logged out,
the list up at once from what was fetched last time. It must never become an
Instagram client: nothing is logged in to, nothing is posted, and nothing
but each post's picture(s) and caption is shown.

## Not wanted

- Likes, comments, follower counts, or anything social from Instagram.
- Posting dates, anywhere (the user found them confusing beside event dates).
- "Today", "Tomorrow" or "Yesterday" in day headings: always weekday and date.
- Headers or a title bar on any screen; an app name on screen.
- The family's rounded focus outline for list rows (Hbsa uses its own shaded
  row with a side bar, see Look).
- The 1.0 design: posts newest first with a gold "next event" card on top.
- Logging in to Instagram, or other accounts.
- Text that explains how to use the app (DESIGN.md rule 7).

## Screens

### Agenda (launch screen)

What is on it, top to bottom, with no header:

- For each day an event falls on, earliest first, a heading in capitals:
  "SATURDAY, OCT 31". A day in another year than the current one adds the
  year: "SATURDAY, JAN 2, 2027". Headings of past days are grey; others
  violet.
- Under each heading, one row per event that day: start time on the left
  ("6 PM", "6:30 PM", "12 PM"), the event's name (at most 2 lines, ellipsised),
  and a 36dp square thumbnail of the post's first picture on the right.
  Timed events come before untimed ones; an untimed event shows a grey "—"
  in place of the time.
- Events on days before today are greyed (text and a faded thumbnail).
  Events today are not greyed, even if their time has passed.
- If any post names no date: a heading "OTHER POSTS" (never greyed), then one
  row per such post with no time column, its caption on up to 2 lines
  (whitespace collapsed), or "No caption" if it has none.
- One post can make several events (one per day it names); each is its own
  row under its own day.

Where it opens: on the first event today or later, with its day's heading
visible just above it. If no event is today or later, it opens on the latest
past event. If there are no events at all, on the first of Other posts.

Keys:

- Up / Down: move between rows. Headings are skipped, but moving Up onto a
  day's first row keeps that day's heading in view above it. Up on the first row
  and Down on the last row stay put. The row under the d-pad is shaded.
- OK: opens that row's Post screen.
- Menu or left soft key: a popup with "Refresh" and "Open in browser".
  Refresh fetches again now. Open in browser opens
  https://www.instagram.com/hbsaasu/ in the browser; with no browser the
  toast "No browser on this phone".
- Back: leaves the app.

Loading and empty states:

- First launch (nothing on disk) while fetching: a small violet spinner in
  the middle of a blank page.
- Posts already on disk: the agenda shows at once; a thin indeterminate
  violet bar runs along the very top while fresh posts are fetched behind it.
  When they arrive the list is rebuilt and the d-pad stays on the same event
  (same post and day) if it is still there, else goes to where the list
  opens.
- Fetch failed with posts on disk: the old agenda stays and a toast gives the
  reason (see Behaviour).
- Fetch failed with nothing on disk: centred "Couldn't reach Instagram" with
  the reason under it, and a "Try again" button that has the focus. OK on it
  fetches again (spinner, then agenda or this screen again).
- Fetch succeeded but the account has no posts: "No posts yet" with the
  "Try again" button.
- Menu works on these screens too (Refresh, Open in browser).

### Post

Opened from an agenda row. No header.

- The post's picture, full width (scaled to fit). Until it arrives, a grey
  square holds its room so the text doesn't jump. A carousel shows a small
  "1/3" counter in the top right corner of the picture; a single picture
  shows none.
- For an event, the line "Saturday, Oct 31 · 6 PM" in violet (weekday, date,
  time; no time if the event has none; muted grey `#736A77` if the day has passed).
  A post from Other posts has no such line.
- The whole caption, with every date and time it mentions marked by a pink
  background (`#F8D8F2`) in medium weight. An empty caption shows nothing.

Keys:

- Up / Down: scroll the page by about a third of the screen; at the top or
  bottom they do nothing.
- OK: the next picture of a carousel, wrapping from the last to the first,
  and scrolls back to the top. On a single picture, nothing.
- Right / Left: the next / previous row of the agenda (events in list order,
  then Other posts), sliding in from that side, scrolled to the top, on its
  first picture. Headings are skipped. Left on the first row and Right on the
  last row do nothing. Holding the key does not repeat.
- Menu or left soft key: a popup with "Open in browser", which opens this
  post on instagram.com.
- Back: to the agenda, with the d-pad on the row last shown here (so after
  walking with Left/Right, the list lands on where you ended up).

## Behaviour

Data and where it lives:

- Posts come from the public profile page https://www.instagram.com/hbsaasu/,
  fetched as a desktop Chrome browser would (logged out, at most the 12
  latest posts); if that page has no posts or is refused, Instagram's web
  profile API is tried before giving up. Details in AGENTS.md.
- The last fetched posts are kept in the app's files (`files/posts.json`) and
  shown at once on the next launch, offline included. The time of the last
  successful fetch is kept too.
- Pictures are fetched in the background, kept at most 480px wide in the
  app's cache, keyed by post and carousel place (Instagram's links expire), so
  a picture seen once still shows offline. Pictures of posts no longer among
  the latest are deleted after each successful fetch. A picture that can't be
  fetched leaves its grey placeholder; nothing else complains.
- Nothing is written to the SD card. The only permission is Internet.

When it fetches:

- On launch, and when the app comes back to the foreground more than 15
  minutes after the last successful fetch. After a failed fetch, not again
  for 15 minutes (so going back and forth between the list and a post
  doesn't retry and repeat the toast). Never two fetches at once.
- From Menu > Refresh and "Try again", at any time.

Errors (the toast, or the line under "Couldn't reach Instagram"):

- 429: "Instagram is limiting requests. Try again in a while."
- Redirect, 401 or 403: "Instagram asked for a login. This usually passes
  after a while."
- 404: "@hbsaasu wasn't found."
- Other non-2xx: "Instagram answered <code>."
- Page without posts: "Instagram sent a page without the posts."
- No network: the connection's own message; an error with none shows
  "Something went wrong."
- Note for testers: Instagram refuses datacentre addresses, so in a cloud
  emulator a login or limiting error is expected and is not a bug by itself.

Events (from captions; full rules in AGENTS.md "Events"):

- Recognised: "Friday, Oct 31 at 6pm", "Oct 31", "31 October", "10/31",
  "the 2nd of November", "today", "tonight", "tomorrow", a weekday, "7-9pm",
  "noon", "6:30pm", "6.30pm", "18:30", and "7:30" with no am/pm (read as evening for 1:00-9:59).
- A date without a year takes the post's year, or the next if that would put
  it more than 45 days before the post. A lone weekday is the first such day
  on or after the post, and counts only when the caption has no full date.
- Dates more than 120 days after the post or 60 before it are ignored.
- One event per day per post. Each takes the first time after its date and
  before the next date, else one earlier on its line.
- Name: the caption's first sentence, with "today", "tonight" and "tomorrow"
  (and "Today!", "tomorrow night" and the like) taken out, since they soon say
  the wrong day; a dot after an abbreviation ("Nov.", "St.", "p.m." mid
  sentence) doesn't end the sentence. When a post names several days, each
  event's own line with the date and time taken out ("Diwali Night - Nov 8,
  6pm" is "Diwali Night").
- A slash date with no year followed by a word like "price", "off" or "of"
  ("1/2 price", "3/4 of the seats") is a fraction, not a date.
- Dates joined only by "&", "and" or a comma share the time that follows
  ("Oct 31 & Nov 1 at 8pm"), and the joiner is left out of each name.
- An event's own line that is nothing but its date ("Date: Nov 4") takes the
  nearest line above it with words in it ("Garba Night"). A caption line with
  no words (only emoji) is never the name.
- Days and "past" use the phone's time zone; an event is past once its day
  has ended.

Orientation is portrait only; the app runs without touch.

## Look

Beyond DESIGN.md and AGENTS.md "Look":

- No headers on either screen; content starts at the top under a dark violet
  (`#3A0D4A`) status bar.
- Day headings: 11sp, Roboto Medium, capitals, letter-spaced, violet
  `#9C1FA6` (grey `#A79DAB` when past), 10dp in.
- Row under the d-pad: shaded edge to edge `#F3E2F7` with a 3dp violet bar
  down its left side (`res/drawable/row.xml`). Rows are at least 48dp tall.
- Past events and their headings `#A79DAB`; their thumbnails at about half
  opacity.
- The icon is a tear-off calendar page on violet with a day circled.

## Open questions

1. Events earlier today stay ungreyed and the list opens on the first event
   of today even after it has ended. Should "past" be by end of the event's
   time (e.g. greyed a few hours after it starts) rather than end of day?
2. The agenda is only rebuilt after a fetch. If the app is left open past
   midnight and brought back within 15 minutes of the last fetch, yesterday
   is not greyed and the list doesn't move on (the code's comment in
   `MainActivity.onResume` says it should). Should coming back always rebuild
   the list against the current day?
3. On the Post screen a past event's date line is `text_muted` `#736A77`, while
   the agenda uses the past grey `#A79DAB`. Intended to differ?
4. With no network, the error line is Java's raw message (e.g. "Unable to
   resolve host ..."). Should it read something plainer like "No connection"?
5. The right soft key does nothing on either screen. Is that intended?
