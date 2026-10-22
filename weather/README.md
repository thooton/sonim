# Weather, for the Sonim XP3

The forecast for one place, drawn as CARROT Weather draws it, laid out for a
240x320 screen and a d-pad. At the top, edge to edge, the sky in CARROT's
colour for the weather: the place, the condition's icon, the temperature and
what it feels like, CARROT's weather over the sky (clouds, rain, snow, stars,
wind, fog) and its scenery of hills and a lighthouse along the foot. Under
it, straight on the page, the next 48 hours and the next ten days.

The hourly forecast shows one kind of data at a time, as the chips on
CARROT's iOS app choose it: Temperature, Feels like, Precip chance, Precip
amount, Wind, Humidity, UV index, Cloud cover, Dew point, Pressure and
Visibility. Temperatures are a line in CARROT's temperature colours; the
rest are bars, or speeds with arrows for wind. Each of the ten days has its
icon, its chance of rain or snow, and its low and high on a bar coloured by
CARROT's temperature scale.

| Key | |
| --- | --- |
| Menu (left soft key) | The menu: Location, Hourly data, Units (°F, mph or °C, km/h), Theme (dark or light), Refresh |
| Up, Down | Scroll the page: the sky, then the hours, then the ten days |
| Left, Right | Six hours back or on |
| *, # | The previous or next hourly data |
| OK | Refresh |
| Back (right soft key) | Close the menu or the search; otherwise leave |

**Location** searches by city or ZIP code; before anything is typed it lists
Current location (the phone's own position, followed from then on) and the
last five places chosen.

The forecast comes from [Open-Meteo](https://open-meteo.com), which is free
and needs no key; the name of where the phone is comes from BigDataCloud's
free reverse geocoder. The last forecast is kept, so the app opens on it at
once and refreshes it in the background when it is more than half an hour
old.

`weather/mockups/index.html` is the design: every screen at 240x320, drawn
by the same layout the app uses.
