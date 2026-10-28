# Weather

## Purpose

The forecast for one place, drawn as CARROT Weather draws it, on the Sonim
XP3 (240x320, portrait). The user glances at it one-handed with the d-pad,
OK, Back, the soft keys, * and #; never touch. Opening it shows the last
forecast at once and a fresh one a moment later.

It must stay one page you read at a glance: the sky with the place and the
temperature, the next 48 hours, the next ten days. It is free to run
(Open-Meteo and BigDataCloud, no keys, no account) and fetches only when
opened, brought back, or asked. It must never become a weather service
client with an account, a place list or dashboard of many cities, a
notifier, a widget, or anything that fetches in the background. The look is
CARROT's colours with the app's own drawings; none of CARROT's artwork is
copied, since the repository is public.

## Not wanted

- CARROT's snarky text: it would not fit.
- Cards or margins around the sky; cards under it (the forecasts sit
  straight on the page). See DESIGN.md "Weather".
- CARROT's own artwork, icons or scenery copied in.
- Easing or animation on scrolling: each Up or Down lands at once (1.0.2).
- Scrolling past the last day: Down stops with the last day at the foot of
  the screen (1.0.1).
- An app-name header, or text telling the user which keys to press.

## Screens

One activity: the **Forecast** page, a **Menu** sheet over it (with an
**Hourly data** sheet), and the **Location** search over it.

### Forecast

Top to bottom, scrolling as one page:

- **The sky** (148dp, edge to edge) in CARROT's card colour for the current
  condition (DESIGN.md lists them), with the condition's effect drawn over
  it (clouds, rain, snow, stars, wind streaks, fog) and hills with a
  lighthouse along its foot. On it, white: the place's name centred at the
  top (cut with "…" past 220dp); the condition's icon and the temperature
  ("54°", large, light), centred together; "Feels 51°" under the
  temperature.
- **HOURLY FORECAST**, in small grey spaced capitals, with a blue chip at
  the right naming the data shown: "Temp", "Feels like", "Precip chance",
  "Precip" (US) or "Precip · mm", "Wind · mph" or "Wind · km/h", "Humidity",
  "UV index", "Cloud cover", "Dew point", "Pressure · inHg" or
  "Pressure · hPa", "Visibility".
- Six hour columns: the time ("10" with a small "AM"/"PM"; "14" in 24-hour
  time, which follows the phone's setting), the hour's icon (night icons
  after dark), and the data:
  - Temperature, Feels like, Dew point, Pressure: a smooth line through the
    hours, coloured by CARROT's temperature scale (or a fixed colour for
    pressure), a dot at each hour and its value above it. The line runs off
    both edges toward the hidden neighbouring hours.
  - Precip chance, Precip amount, Humidity, UV index, Cloud cover,
    Visibility: a bar on a grey track, its value above. Full bars are 100%,
    0.30", UV 11, 10 mi; anything over is a full bar, zero is a 2dp stub.
  - Wind: the speed, with a green arrow under it pointing where the wind
    blows to.
- A hairline, then **DAILY FORECAST**, then up to ten rows: "TODAY", then
  "WED", "THU"…; the day's icon (always the daytime one); its chance of
  rain or snow in blue only when it is 20% or more; the low (grey), a bar
  from low to high on a track scaled to the ten days' range and shaded by
  the temperature scale; the high.

Values: temperatures are whole degrees with "°" and no letter; precip
amount is ".04\"" (US) or "1.2" mm; pressure "30.01" inHg or "1016" hPa;
visibility "6.2mi"/"10mi" or "9.9km"/"16km"; percentages whole.

Keys:

| Key | Does |
| --- | --- |
| Menu (left soft key) | Opens the Menu (on release) |
| Down | Scrolls the page 38dp (two day rows). At the bottom (last day at the foot of the screen) it does nothing |
| Up | Scrolls 38dp back. At the top it does nothing |
| Right | Shows the next six hours. At the last six of the 48 it does nothing |
| Left | Shows the previous six hours. At the first six it does nothing |
| # / * | Next / previous hourly data, wrapping from Visibility to Temperature and back. Kept for next time |
| OK | Refreshes (on release; a held OK refreshes once) |
| Back (right soft key) | Leaves the app |

While a forecast loads, a thin bar runs along the very top (white on the
sky, blue when scrolled past it on the light theme, blue on the empty
page). A second refresh while one is running is ignored.

The status bar takes the sky's colour; once the page is scrolled past the
sky it takes the page's colour (dark icons on the light theme).

**No forecast yet** (first fetch running or failed, nothing cached): the sky
in the clear-day blue with the day scenery, "Getting the forecast" on it;
after a failure "No forecast" on it and the reason wrapped under it in grey.
Up, Down, Left, Right do nothing; OK retries.

### Menu

A sheet up from the foot over the dimmed page, titled with the place's name
(or "Weather" with no forecast). Rows, each with a blue icon, the value
greyed on the right:

1. Location
2. Hourly data — the current chip's words ("Temp")
3. Units — "°F, mph" or "°C, km/h"
4. Theme — "Dark" or "Light"
5. Refresh

It opens on the first row. The row under the d-pad is filled CARROT blue
with white text. Up on the first row and Down on the last stay put. OK
closes the sheet and acts: **Location** opens Location; **Hourly data**
opens the Hourly data sheet; **Units** and **Theme** switch at once to the
other choice (and are kept); **Refresh** refreshes. Back, Menu and the soft
keys close it without acting. No other key does anything while it is open.

### Hourly data

The same kind of sheet, titled "Hourly data", listing the eleven kinds in
order: Temperature, Feels like, Precip chance, Precip amount, Wind,
Humidity, UV index, Cloud cover, Dew point, Pressure, Visibility. The
current one has a tick and the highlight when it opens. Rows that don't fit
scroll with the highlight, and a short bar at the foot shows there are more
below. OK picks one and closes it; Back closes it with nothing changed.

### Location

A full page in the theme's colours: a rounded field at the top with a
magnifier and the hint "City or ZIP code", outlined in blue while it has
the d-pad; rows under it, each a pin, a name and a grey detail line, parted
by hairlines.

- **Empty field**: "Current location" (blue pin; under it the place's name
  if the app is following the phone, otherwise "Where the phone is"), then
  up to five recent places, newest first, each with "Recent" under it.
- **Typing** (T9, words capitalised): "Searching" while it waits; the search
  goes out after a 450ms pause in typing, and an answer to older text is
  dropped. Up to ten places, the likeliest first: "Portland, OR" over
  "Oregon, United States" (US states abbreviated in the name; elsewhere the
  name over region and country). "No places found" when none match; the
  error's text when the search fails.

Keys:

| Key | Does |
| --- | --- |
| Down | From the field to the first row, then row by row; stays on the last row |
| Up | Row by row; from the first row back to the field; stays in the field |
| OK in the field | Highlights the first row (with no rows, nothing) |
| OK on a row | Chooses it |
| A typed key on a row | Goes back to the field and types there |
| Back | In the field with text: deletes a character, as the phone's fields do. Otherwise closes Location, back to the forecast unchanged. If there is no place yet (first run), it leaves the app |
| Menu (left soft key) | Nothing |

Long names and details are cut with "…". Rows scroll to keep the highlight
in view.

Choosing a place returns to the forecast, scrolled to the top and at the
first six hours, on "Getting the forecast" until it arrives; it becomes
the first recent place (a place already in the list moves to the top; the
list stays at five).

**Current location**: asks for the location permission the first time.
Denied: "Weather may not use the phone's location" in place of the rows.
Granted: "Finding where the phone is", using the phone's last fix if under
30 minutes old, otherwise one new fix from network or GPS. After 45 seconds
with no fix it uses an older fix if there is one, else says "Couldn't find
where the phone is". The fix is named by BigDataCloud ("Portland, OR"); if
that fails the name is the coordinates ("45.52, -122.68"). The app then
follows the phone (see Behaviour). Current location is never added to the
recent places.

## Behaviour

- **First run**: no place, so Location opens at once.
- **Kept** (app preferences, survive restarts): the place, whether it
  follows the phone, the five recent places, the hourly data shown, units
  (default °F, mph), theme (default dark). The last forecast Open-Meteo sent
  is kept in the app's files (`forecast.json`) with when it was fetched.
- **Opening**: shows the kept forecast at once (if readable), then fetches
  a new one. **Coming back** to the app fetches only if the shown forecast
  is more than 30 minutes old.
- **Network**: Open-Meteo forecast (current, 48 hours, 10 days, US units,
  the place's own time zone; 30s timeout), Open-Meteo geocoding for search
  (needs at least two characters), BigDataCloud for naming the phone's
  position. HTTPS only, no keys, no login.
- **Errors**: with a forecast on screen, a failed refresh leaves it there
  and shows the reason in a toast. With none, the empty page says "No
  forecast" and the reason. An unreadable answer reads "The forecast came
  back unreadable."
- **Following the phone**: each refresh looks at the phone's last known
  fix; if it is more than about 2 km (0.02°) from the place, the place is
  renamed and the forecast is for the new position.
- **Hours and days** shown are from the current hour and today on, judged
  when drawn, so a page left open drops past hours.
- **Conditions**: WMO codes map to CARROT's twelve (clear, partly cloudy
  and cloudy, each by day or night; rain, thunderstorm, snow, sleet, fog,
  wind). A clear or cloudy hour with wind 25 mph or more is windy.
- **Permissions**: Internet; location, asked only when Current location is
  chosen. No storage.
- **Screenshots**: `adb shell am start -n com.weather.xp3/.MainActivity -e
  sample rain` (or any of `Sample.NAMES`) shows a made-up forecast without
  the network; OK there shows the loading bar for 0.9s and redraws it.
  Choosing a place leaves sample mode.

## Look

- Colours are CARROT's (DESIGN.md "Weather"); the page under the sky is
  black (dark) or white (light), text white or black, secondary grey
  `#8E8E93`. The sheet is `#1C1C1E` (dark) or white, its selected row and
  every accent CARROT's blue `#0B84FE`.
- `weather/mockups/index.html` is the design and is drawn by the same layout
  code; a screen that differs from its mockup is a finding.
- Small text (under 11sp) keeps visible gaps between words.
- Theme does not change the sky; it changes the page, sheets and Location.

## Open questions

1. The kept forecast isn't tied to its place. If a newly chosen place's
   first fetch fails, the next launch shows the old place's forecast under
   the new place's name. Presumably the cache should be dropped (or kept per
   place) when the place changes.
2. The README says opening refreshes only when the forecast is over half
   an hour old; the code always refreshes on opening (and only checks age
   when coming back). Which is wanted?
3. Following the phone only reads the last known fix on refresh and never
   asks for a new one, so it moves only when another app has updated the
   fix. Should a refresh ask for a fresh fix?
4. Typing one character shows "No places found" (search needs two). Should
   it stay quiet until a second character?
5. Error text is the raw service message ("Open-Meteo HTTP 429: rate or
   usage limit reached…", or Java's own wording when offline). Should the
   app say something plainer, such as "No connection"?
