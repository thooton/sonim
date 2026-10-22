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

import com.xp3.common.ui.BaseActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private Place place;
    private List<Place> recent = new ArrayList<>();
    /** A made-up forecast to show, for screenshots and before the service is wired: -e sample rain. */
    private String sample;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("weather", MODE_PRIVATE);
        sample = getIntent().getStringExtra("sample");
        place = Place.fromJsonString(prefs.getString("place", null));
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
        root.addView(locationLayer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        view.requestFocus();
        if (place == null && sample == null) {
            openLocation();
        } else {
            load(false);
        }
    }

    // ------------------------------------------------------------------
    // The forecast.
    // ------------------------------------------------------------------

    /** Shows the forecast for `place`; `fresh` shows the loading bar first. */
    private void load(boolean fresh) {
        if (fresh) {
            view.loading = true;
            view.invalidate();
            main.postDelayed(() -> {
                view.loading = false;
                show();
            }, 900);
        } else {
            show();
        }
    }

    private void show() {
        long now = System.currentTimeMillis();
        int offset = java.util.TimeZone.getDefault().getOffset(now) / 1000;
        Forecast f = Sample.of(sample != null ? sample : "clear-day", now, offset);
        if (place != null && sample == null) {
            f = new Forecast(place.name, f.utcOffset, f.fetched, f.now, f.hours, f.days);
        }
        view.forecast = f;
        view.invalidate();
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
        statusBar(view.theme.bg, !view.theme.dark);
        field.requestFocus();
        search("");
    }

    private void closeLocation() {
        locationLayer.setVisibility(View.GONE);
        view.requestFocus();
        view.forceStatusBar();
        if (view.forecast == null && place == null) finish();
    }

    private void search(String query) {
        List<LocationView.Row> rows = locationView.rows;
        rows.clear();
        locationView.selected = -1;
        locationView.message = null;
        String q = query.trim().toLowerCase(Locale.US);
        if (q.isEmpty()) {
            rows.add(new LocationView.Row("Current location", place != null ? place.name : "Where the phone is", true, null));
            for (Place p : recent) rows.add(new LocationView.Row(p.name, "Recent", false, p));
        } else {
            for (Place p : CITIES) {
                if (p.name.toLowerCase(Locale.US).startsWith(q) || p.detail.toLowerCase(Locale.US).startsWith(q)) {
                    rows.add(new LocationView.Row(p.name, p.detail, false, p));
                }
            }
            if (rows.isEmpty()) locationView.message = "No places found";
        }
        locationView.invalidate();
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
        Place p = row.place;
        if (p == null) p = CITIES.get(0); // Where the phone is: until there's a GPS fix to ask for.
        place = p;
        recent = Place.remember(recent, p, 5);
        prefs.edit().putString("place", Place.toJsonString(p)).putString("recent", Place.write(recent)).apply();
        sample = null;
        locationLayer.setVisibility(View.GONE);
        view.requestFocus();
        view.scrollToTop();
        view.offset = 0;
        view.forceStatusBar();
        load(true);
    }

    /** Somewhere to search until the weather service's own search is wired. */
    private static final List<Place> CITIES = new ArrayList<>();
    static {
        CITIES.add(new Place("Miami, FL", "Florida, United States", 25.77, -80.19));
        CITIES.add(new Place("Portland, OR", "Oregon, United States", 45.52, -122.68));
        CITIES.add(new Place("Portland, ME", "Maine, United States", 43.66, -70.26));
        CITIES.add(new Place("Portland", "Victoria, Australia", -38.34, 141.6));
        CITIES.add(new Place("Seattle, WA", "Washington, United States", 47.61, -122.33));
        CITIES.add(new Place("Jackson, WY", "Wyoming, United States", 43.48, -110.76));
        CITIES.add(new Place("New York, NY", "New York, United States", 40.71, -74.01));
        CITIES.add(new Place("San Francisco, CA", "California, United States", 37.77, -122.42));
    }
}
