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

## Dates

`Dates.find` finds the dates and times in a caption ("Friday, Oct 31 at
6pm", "10/31", "the 2nd of November", "7-9pm", "noon"). A date without a
year takes the post's year, or the next if that would put it more than 45
days before the post; a weekday on its own is the first such day on or after
the post. `Dates.next` picks the soonest one not yet passed across all
posts (a written-out date beats a lone weekday in the same caption, and
nothing more than 120 days after its post counts), with the time nearest it.

## Layout

- `android/core`: `Post`, `Instagram`, `Dates`. Tests in `src/test` use
  pages written for the tests in Instagram's shape; run them with
  `./gradlew :hbsa:core:selfTest`.
- `android/app`: `MainActivity` (the list and the gold card), `PostActivity`
  (a post), `Club` (the posts on disk in `files/posts.json`, when they were
  fetched, and which have been opened: on the first fetch every post counts
  as seen, and later ones are new until opened), `Pictures`.
- `mockups/index.html`: the three designs considered; B (Agenda) is the one
  built.

## Look

The family look (see `../DESIGN.md`) in ASU maroon and gold: maroon
`#5A1229` header and status bar, `#8C1D40` for focus and what is new, gold
`#FFC627` only for dates (the next-event card and the marks in a caption).
The icon is a tear-off calendar page, gold on maroon, with a day circled.
