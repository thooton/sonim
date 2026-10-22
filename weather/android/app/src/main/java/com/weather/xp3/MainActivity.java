package com.weather.xp3;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import com.xp3.common.Disk;
import com.xp3.common.Http;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * The forecast for one place, and the menu and search that change it.
 *
 * Keys: Menu (left soft key) opens the menu; Up and Down scroll the page;
 * Left and Right scroll the hours; * and # step through the hourly data; OK
 * refreshes; Back closes whatever is open, or leaves.
 */
public final class MainActivity extends BaseActivity implements WeatherView.Chrome {

    private SharedPreferences prefs;
    private WeatherView view;
    private FrameLayout locationLayer;
    private LocationView locationView;
    private EditText field;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final OpenMeteo weather = new OpenMeteo(new Http());
    private final ExecutorService worker = Ui.workers("weather", 2);

    private Place place;
    /** Follow the phone: each refresh asks where it is now. */
    private boolean here;
    private List<Place> recent = new ArrayList<>();
    /** A made-up forecast to show, for screenshots: -e sample rain. */
    private String sample;
    /** Bumped by each search, so an answer to an older one is dropped. */
    private int searching;
    private boolean fetching;

    /** How old a forecast may be before coming back to the app refreshes it. */
    private static final long STALE_MS = 30 * 60 * 1000L;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("weather", MODE_PRIVATE);
        sample = getIntent().getStringExtra("sample");
        place = Place.fromJsonString(prefs.getString("place", null));
        here = prefs.getBoolean("here", false);
        recent = Place.list(prefs.getString("recent", null));

        FrameLayout root = new FrameLayout(this);
        view = new WeatherView(this);
        view.chrome = this;
        view.metric = Metric.named(prefs.getString("metric", "TEMP"));
        view.units = Units.of(prefs.getBoolean("metricUnits", false));
        view.theme = new WeatherView.Theme(prefs.getBoolean("dark", true));
        view.clock24 = android.text.format.DateFormat.is24HourFormat(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        locationLayer = new FrameLayout(this);
        locationView = new LocationView(this, view);
        locationLayer.addView(locationView, new FrameLayout.LayoutParams(-1, -1));
        field = new EditText(this);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        field.setHint("City or ZIP code");
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                search(s.toString());
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(LocationView.FIELD_W - 30 - 12), dp(LocationView.FIELD_H));
        lp.leftMargin = dp(38);
        lp.topMargin = dp(LocationView.FIELD_Y);
        locationLayer.addView(field, lp);
        locationLayer.setVisibility(View.GONE);
        view.covered = false;
        root.addView(locationLayer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        view.requestFocus();
        if (sample != null) {
            showSample();
        } else if (place == null) {
            openLocation();
        } else {
            showCached();
            load(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sample == null && place != null && view.forecast != null
            && System.currentTimeMillis() - view.forecast.fetched > STALE_MS) {
            load(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------
    // The forecast.
    // ------------------------------------------------------------------

    private File cache() {
        return new File(getFilesDir(), "forecast.json");
    }

    /** The last forecast fetched, at once, while a new one comes. */
    private void showCached() {
        String body = Disk.read(cache(), 2000000);
        if (body == null) return;
        try {
            Forecast f = OpenMeteo.parse(body, place.name, prefs.getLong("fetched", 0));
            view.forecast = f;
            view.invalidate();
        } catch (IOException e) {
            // A damaged cache is no cache.
        }
    }

    private void showSample() {
        long now = System.currentTimeMillis();
        int offset = java.util.TimeZone.getDefault().getOffset(now) / 1000;
        view.forecast = Sample.of(sample, now, offset);
        view.invalidate();
    }

    /** Fetches the forecast for `place`, with the loading bar along the top. */
    private void load(boolean quietLocate) {
        if (sample != null) {
            view.loading = true;
            view.invalidate();
            main.postDelayed(() -> {
                view.loading = false;
                showSample();
            }, 900);
            return;
        }
        if (place == null || fetching) return;
        fetching = true;
        view.loading = true;
        view.error = null;
        view.invalidate();
        final Place asked = place;
        final Location fix = here && quietLocate ? lastKnown() : null;
        worker.execute(() -> {
            try {
                Place p = asked;
                if (fix != null && (Math.abs(fix.getLatitude() - p.lat) > .02 || Math.abs(fix.getLongitude() - p.lon) > .02)) {
                    p = weather.named(fix.getLatitude(), fix.getLongitude());
                }
                String body = weather.forecastBody(p);
                long now = System.currentTimeMillis();
                Forecast f = OpenMeteo.parse(body, p.name, now);
                Disk.write(cache(), body, false);
                final Place got = p;
                main.post(() -> {
                    fetching = false;
                    view.loading = false;
                    if (!got.same(place) && asked.same(place)) {
                        place = got;
                        prefs.edit().putString("place", Place.toJsonString(got)).apply();
                    }
                    if (!got.same(place)) return; // Moved on to somewhere else meanwhile.
                    prefs.edit().putLong("fetched", now).apply();
                    view.forecast = f;
                    view.invalidate();
                });
            } catch (IOException | InterruptedException e) {
                final String why = e.getMessage();
                main.post(() -> {
                    fetching = false;
                    view.loading = false;
                    if (view.forecast == null) view.error = why;
                    else toast(why);
                    view.invalidate();
                });
            }
        });
    }

    @Override
    public void statusBar(int color, boolean lightIcons) {
        Window w = getWindow();
        w.setStatusBarColor(color);
        w.setNavigationBarColor(Color.BLACK);
        View d = w.getDecorView();
        int flags = d.getSystemUiVisibility();
        flags = lightIcons ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        d.setSystemUiVisibility(flags);
    }

    // ------------------------------------------------------------------
    // Keys.
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (locationLayer.getVisibility() == View.VISIBLE) {
            if (locationKey(key, down, event)) return true;
            return super.dispatchKeyEvent(event);
        }
        if (view.sheet != null) {
            sheetKey(key, down);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SOFT_LEFT:
                if (!down) openMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) view.scrollDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) view.scrollUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) view.hoursOn(WeatherView.COLS);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) view.hoursOn(-WeatherView.COLS);
                return true;
            case KeyEvent.KEYCODE_POUND:
                if (down) setMetric(view.metric.next());
                return true;
            case KeyEvent.KEYCODE_STAR:
                if (down) setMetric(view.metric.previous());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down && event.getRepeatCount() == 0) load(true);
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void sheetKey(int key, boolean down) {
        Sheet s = view.sheet;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) s.move(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) s.move(1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down) {
                    view.sheet = null;
                    s.pick();
                }
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_SOFT_RIGHT:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SOFT_LEFT:
                if (!down) view.sheet = null;
                break;
            default:
        }
        view.invalidate();
    }

    // ------------------------------------------------------------------
    // The menu.
    // ------------------------------------------------------------------

    private void openMenu() {
        String title = view.forecast != null ? view.forecast.place : "Weather";
        view.sheet = new Sheet(title, 26)
            .add(Sheet.Glyph.PIN, "Location", null, this::openLocation)
            .add(Sheet.Glyph.CHART, "Hourly data", view.metric.chip, this::openMetrics)
            .add(Sheet.Glyph.UNITS, "Units", view.units.label(), this::toggleUnits)
            .add(Sheet.Glyph.THEME, "Theme", view.theme.dark ? "Dark" : "Light", this::toggleTheme)
            .add(Sheet.Glyph.REFRESH, "Refresh", null, () -> load(true));
        view.invalidate();
    }

    private void openMetrics() {
        Sheet s = new Sheet("Hourly data", 24);
        for (Metric m : Metric.values()) s.option(m.label, m == view.metric, () -> setMetric(m));
        view.sheet = s;
        view.invalidate();
    }

    private void setMetric(Metric m) {
        view.metric = m;
        prefs.edit().putString("metric", m.name()).apply();
        view.invalidate();
    }

    private void toggleUnits() {
        boolean metric = !view.units.metric;
        view.units = Units.of(metric);
        prefs.edit().putBoolean("metricUnits", metric).apply();
        view.invalidate();
    }

    private void toggleTheme() {
        boolean dark = !view.theme.dark;
        view.theme = new WeatherView.Theme(dark);
        prefs.edit().putBoolean("dark", dark).apply();
        view.invalidate();
    }

    // ------------------------------------------------------------------
    // Choosing a place.
    // ------------------------------------------------------------------

    private void openLocation() {
        locationView.theme = view.theme;
        field.setTextColor(view.theme.text);
        field.setHintTextColor(view.theme.muted);
        field.setText("");
        locationView.selected = -1;
        locationLayer.setVisibility(View.VISIBLE);
        view.covered = true;
        statusBar(view.theme.bg, !view.theme.dark);
        field.requestFocus();
        search("");
    }

    private void closeLocation() {
        locationLayer.setVisibility(View.GONE);
        view.covered = false;
        view.requestFocus();
        view.forceStatusBar();
        if (view.forecast == null && place == null) finish();
    }

    private void search(String query) {
        final List<LocationView.Row> rows = locationView.rows;
        final int ask = ++searching;
        locationView.selected = -1;
        locationView.message = null;
        final String q = query.trim();
        if (q.isEmpty()) {
            rows.clear();
            rows.add(new LocationView.Row("Current location", here && place != null ? place.name : "Where the phone is", true, null));
            for (Place p : recent) rows.add(new LocationView.Row(p.name, "Recent", false, p));
            locationView.invalidate();
            return;
        }
        rows.clear();
        locationView.message = "Searching";
        locationView.invalidate();
        // Typing on a keypad is slow: wait for a pause before asking.
        main.postDelayed(() -> {
            if (ask != searching) return;
            worker.execute(() -> {
                List<Place> found;
                String failed = null;
                try {
                    found = weather.search(q);
                } catch (IOException | InterruptedException e) {
                    found = new ArrayList<>();
                    failed = e.getMessage();
                }
                final List<Place> got = found;
                final String why = failed;
                main.post(() -> {
                    if (ask != searching) return;
                    rows.clear();
                    for (Place p : got) rows.add(new LocationView.Row(p.name, p.detail, false, p));
                    locationView.message = why != null ? why : got.isEmpty() ? "No places found" : null;
                    locationView.invalidate();
                });
            });
        }, 450);
    }

    private boolean locationKey(int key, boolean down, KeyEvent event) {
        LocationView lv = locationView;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && lv.selected < lv.rows.size() - 1) lv.selected++;
                lv.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && lv.selected > -1) lv.selected--;
                lv.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down && lv.selected == -1 && !lv.rows.isEmpty()) {
                    lv.selected = lv.rows.get(0).here && lv.rows.size() > 1 && field.length() > 0 ? 1 : 0;
                    lv.invalidate();
                } else if (!down && lv.selected >= 0) {
                    choose(lv.rows.get(lv.selected));
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_SOFT_RIGHT:
                // Back deletes what was typed first, as a field's Back does.
                if (field.length() > 0 && lv.selected == -1) return false;
                if (!down) closeLocation();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SOFT_LEFT:
                return true;
            default:
                if (lv.selected != -1 && event.getUnicodeChar() != 0) {
                    lv.selected = -1;
                    lv.invalidate();
                }
                return false;
        }
    }

    private void choose(LocationView.Row row) {
        if (row.place == null) {
            locate();
            return;
        }
        use(row.place, false);
    }

    private void use(Place p, boolean followPhone) {
        place = p;
        here = followPhone;
        if (!followPhone) recent = Place.remember(recent, p, 5);
        prefs.edit().putString("place", Place.toJsonString(p)).putBoolean("here", followPhone)
            .putString("recent", Place.write(recent)).apply();
        sample = null;
        searching++;
        locationLayer.setVisibility(View.GONE);
        view.covered = false;
        view.requestFocus();
        view.scrollToTop();
        view.offset = 0;
        view.forecast = null;
        view.error = null;
        view.forceStatusBar();
        fetching = false;
        load(false);
    }

    // ------------------------------------------------------------------
    // Where the phone is.
    // ------------------------------------------------------------------

    private static final int ASK_LOCATION = 1;

    private boolean mayLocate() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void locate() {
        if (!mayLocate()) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, ASK_LOCATION);
            return;
        }
        locationView.rows.clear();
        locationView.selected = -1;
        locationView.message = "Finding where the phone is";
        locationView.invalidate();
        final int ask = ++searching;
        Location fix = lastKnown();
        if (fix != null && System.currentTimeMillis() - fix.getTime() < 30 * 60 * 1000L) {
            name(fix, ask);
            return;
        }
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        final LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                lm.removeUpdates(this);
                if (ask == searching) name(l, ask);
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            for (String provider : new String[] {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                if (lm.isProviderEnabled(provider)) lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // Reported by the timeout below.
        }
        final Location stale = fix;
        main.postDelayed(() -> {
            if (ask != searching) return;
            lm.removeUpdates(listener);
            if (stale != null) {
                name(stale, ask);
            } else {
                locationView.message = "Couldn't find where the phone is";
                locationView.invalidate();
            }
        }, 45000);
    }

    @SuppressWarnings("MissingPermission")
    private Location lastKnown() {
        if (!mayLocate()) return null;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException e) {
            return null;
        }
        return best;
    }

    /** Names the place at `fix`, then shows its weather. */
    private void name(final Location fix, final int ask) {
        worker.execute(() -> {
            Place p;
            try {
                p = weather.named(fix.getLatitude(), fix.getLongitude());
            } catch (IOException | InterruptedException e) {
                p = new Place(String.format(java.util.Locale.US, "%.2f, %.2f", fix.getLatitude(), fix.getLongitude()), "", fix.getLatitude(), fix.getLongitude());
            }
            final Place got = p;
            main.post(() -> {
                if (ask == searching && locationLayer.getVisibility() == View.VISIBLE) use(got, true);
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != ASK_LOCATION) return;
        if (mayLocate()) {
            locate();
        } else {
            locationView.message = "Weather may not use the phone's location";
            locationView.rows.clear();
            locationView.invalidate();
        }
    }
}
