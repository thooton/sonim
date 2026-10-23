# HBSA, for the Sonim XP3

The club's events, read from its Instagram, @hbsaasu, for someone without
Instagram. One list, top to bottom: a heading for each day an event falls
on ("TODAY", "SATURDAY, OCT 31"), and under it each event's start time and
name, with its post's picture. Past days are greyed above; the list opens on
the first event that hasn't happened yet. Posts that name no date come last,
under "Other posts".

Opening one shows the post's picture, when the event is, and the whole
caption with its dates and times marked. Nothing else from Instagram is
shown: no likes, comments or posting dates.

The posts are read the way `instagram_scraper.py` reads them: the public
profile page, fetched from the phone as a desktop browser would ask for it,
with the 12 latest posts in its embedded JSON. Nothing is logged in to.

| Key | |
| --- | --- |
| Up / Down | List: move between events. Post: scroll |
| OK | List: open the event's post. Post: the next picture of a carousel |
| Left / Right | Post: the event before / after |
| Menu (left soft key) | Refresh, Open in browser |
| Back | Back to the list, then out |
