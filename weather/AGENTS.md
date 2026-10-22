# AGENTS.md

A dependency-free Android app for the Sonim XP3 that shows the weather as
CARROT Weather does. A sibling of `../places` and the rest, under the same
constraints: 240x320 mdpi, d-pad driven, no AndroidX or third-party
libraries, `:core` plain Java `--release 8` running on API 27.

## The design

`mockups/index.html` is the design, and it is code: a small renderer that
draws every frame from sample forecasts. The app's drawing (`Art`,
`WeatherView`, `Sheet`, `LocationView`) is a line-for-line port of it into
Canvas calls, in the same 240-wide coordinate space, scaled to the view. A
change to the look is made in both, and the mockup page re-rendered beside
the emulator to check them against each other.

The colours are CARROT's own, read from its Android app (2.5.2): the card
behind the sky for each condition, the temperature scale, the blue for
precipitation, the scenery's and icons' palettes (`Colors`, `Condition`,
`Art.palette`). The drawings themselves are this app's, made in CARROT's
manner; none of CARROT's artwork is copied here, since this repository is
public. Its snarky text is left out: it would not fit.

`PathData` reads SVG path data (arcs included, as cubics) so the mockups'
path strings are used on the phone unchanged.

## How it works

- `android/core`: `Forecast` (now, hours, days, all in US units; `Units`
  writes them out in either), `Metric` (the hourly data kinds), `Colors`,
  `Condition` (CARROT's dressing for each condition), `Place`, `OpenMeteo`
  (the forecast, place search, and BigDataCloud's reverse geocoder for the
  phone's position), `Sample` (made-up forecasts), `PathData`. Tests in
  `src/test` run via `:core:selfTest`, against a loopback server.
- `android/app`: `MainActivity` (keys, menu, location, fetching, the cache in
  `files/forecast.json`), `WeatherView` (the page), `Sheet` (menus),
  `LocationView` (search), `Art` (icons, sky effects, scenery).

Open-Meteo is asked for US units (°F, mph, inches); pressure comes in hPa and
visibility in feet and are converted in `OpenMeteo.parse`. WMO weather codes
become CARROT's conditions in `OpenMeteo.condition`: a clear or cloudy hour
with wind of 25 mph or more is CARROT's windy sky.

`adb shell am start -n com.weather.xp3/.MainActivity -e sample rain` shows a
made-up forecast (`Sample.NAMES`) without the network, for screenshots.
