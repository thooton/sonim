# AGENTS.md

A dependency-free reader of one Instagram account, @hbsaasu (a club the
user belongs to), on the Sonim XP3. A sibling of `../newyorker` and the
rest, under the same constraints: 240x320 mdpi, d-pad driven, no AndroidX or
third-party libraries, `:core` plain Java `--release 8` running on API 27.

Only the picture and the caption of each post matter: the user reads it for
event dates and times. Do not add likes, comments, followers or the like.

## How it reads Instagram

`Instagram.fetch` is a port of the user's `instagram_scraper.py`:

- GET `https://www.instagram.com/hbsaasu/` with the script's desktop Chrome
  headers (`Instagram.HEADERS`; without the `Sec-Fetch-*` and client hints
  the page carries no posts). Every `<script type="application/json">` that
  contains `polaris_ordered_timeline_connection` is parsed and searched
  depth first for that key; its `edges[].node` are the posts (at most 12
  logged out, pinned ones first, so they are sorted by date).
- A node's time comes from its `pk` (a snowflake: `pk >> 23` plus
  `Instagram.EPOCH_MS`), else `taken_at`; its pictures from `display_uri`
  and each `carousel_media` item's `display_uri` or first
  `image_versions2` candidate.
- If the page has no posts in it or is refused, the web profile API the site
  itself calls (`/api/v1/users/web_profile_info/`, with the web app's
  `X-IG-App-ID`) is tried before reporting the page's failure.
- A redirect (to the login wall), 401 or 403 means "asked for a login", 429
  "limiting requests". Instagram does this to datacentre addresses: a cloud
  session can't fetch the profile at all, so check changes against the
  tests rather than the live site. The phone, on a home or carrier network,
  gets the page.

Picture addresses are signed and expire, so `Pictures` keeps each picture on
disk (in the cache dir, at most 480px wide) by shortcode and carousel index,
and prunes the files of posts that have dropped out of the latest 12.

## Events

`Dates.find` finds the dates and times in a caption ("Friday, Oct 31 at
6pm", "10/31", "the 2nd of November", "tonight", "tomorrow", "7-9pm",
"noon", "18:30", and "7:30" with no am or pm, taken as evening for 1:00 to
9:59). A date without a year takes the post's year, or the next if that
would put it more than 45 days before the post; a weekday on its own is the
first such day on or after the post.

`Dates.events` turns those into the post's events, one per day: a written-out
date beats a lone weekday in the same caption, a date more than 120 days
after the post or 60 before it is dropped, and each date takes the first
time after it and before the next date (else one earlier on its line). An
event is named by the caption's first sentence, or, when the post announces
several, by its own line with the date taken out ("Diwali Night - Nov 8,
6pm" is "Diwali Night").

`Agenda.build` is the list: events by day then time (untimed last), a
heading before each day (always weekday and date: the user wants no "Today" or "Tomorrow"), then "Other posts" for posts with no date; `start`
is the first event today or later, where the list opens.

## Layout

- `android/core`: `Post`, `Instagram`, `Dates`, `Agenda`. Tests in
  `src/test` use pages written for the tests in Instagram's shape; run them
  with `./gradlew :hbsa:core:selfTest`.
- `android/app`: `MainActivity` (the agenda; a thin bar along the top while
  fetching), `PostActivity` (an event's post; Left and Right walk the
  agenda), `Club` (the posts on disk in `files/posts.json`, when they were
  fetched, and the agenda on screen), `Pictures`.
- `mockups/index.html`: the first three designs considered. The app has
  moved on from them (see Look).

## Look

No headers on any screen: the list and the post start at the top. Violet
and magenta: the status bar `#3A0D4A`; `#9C1FA6` for the day headings, the
event under the d-pad and when an event is; `#F8D8F2` behind the dates
marked in a caption. Past events are greyed (`#A79DAB`). The event under the
d-pad is shaded edge to edge (`#F3E2F7`) with a 3dp violet bar down its left
side (`res/drawable/row.xml`), not the family's rounded outline. Posting
dates are deliberately not shown anywhere: the user found them confusing
beside event dates. The icon is a tear-off calendar page on violet with a
day circled.
