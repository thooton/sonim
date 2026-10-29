package com.places.xp3;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.places.xp3.net.Anchor;
import com.places.xp3.net.Link;
import com.places.xp3.net.Place;
import com.places.xp3.net.Places;
import com.xp3.common.Json;
import com.xp3.common.Login;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;
import com.xp3.common.ui.Logins;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * One screen, in Google Maps' dark look, made for a d-pad: what to find in
 * the header, filter chips under it (where to search near, open now, top
 * rated), and the places found as a compact list, several to a screen, each
 * with its rating, kind, whether it is open and how far. Before a search,
 * the recent ones.
 *
 * The d-pad moves between the header, the chips and the results. Up and
 * Down go from one to the next (Down past the last result loads more), Left
 * and Right go along the chips, and OK does what is picked: searches,
 * toggles a filter, or opens the place on its own page ({@link PlacePage}),
 * where OK lists what can be done with it. Typing anywhere goes back to the
 * header.
 */
public final class MainActivity extends BaseActivity {

    private static final String PREFS = "places";
    private static final int RECENT_KEPT = 12;

    /** Where the d-pad is on the search screen. */
    private enum Zone { QUERY, NEAR, FILTERS, LIST }

    /** The filter chips, in order. */
    private static final int FILTER_NEAR = 0;
    private static final int FILTER_OPEN = 1;
    private static final int FILTER_RATED = 2;

    /** Work for the background thread: returns what to do with its result on the main thread. */
    private interface Work {
        Runnable call() throws Exception;
    }

    private EditText query;
    private EditText near;
    private ImageView lead;
    private ImageView clear;
    private ProgressBar progress;
    private HorizontalScrollView filtersScroll;
    private LinearLayout filters;
    private ListView list;
    private TextView message;
    private View searchScreen;
    private View placeScreen;
    private ScrollView placeScroll;

    private Look look;
    private PlacePage page;
    private Photos photos;
    private final ResultAdapter adapter = new ResultAdapter();
    private final ExecutorService worker = Ui.worker("places");

    private Login login = Login.blank();
    private Places places;
    /** Where the last search was made near, with Google's point for it. */
    private Anchor anchor;
    private boolean openNow;
    private boolean topRated;

    /** The results so far, the search they answer, and Google's token for more. */
    private final List<Place> results = new ArrayList<>();
    private String searched;
    private Anchor searchedNear;
    private boolean searchedOpen;
    private boolean searchedRated;
    private String nextPage;
    /** Places whose details were fetched, by id, so going back and forth costs nothing. */
    private final Map<String, Place> details = new HashMap<>();
    private final List<String> recent = new ArrayList<>();

    private Zone zone = Zone.QUERY;
    private int filter;
    /** The picked row: a result, or a recent search before there are results. */
    private int picked;
    /** Whether a place's sheet is up. */
    private boolean showing;

    private Future<?> job;
    private int generation;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        look = new Look(this);
        photos = new Photos(() -> places());
        query = findViewById(R.id.query);
        near = findViewById(R.id.near);
        lead = findViewById(R.id.lead);
        clear = findViewById(R.id.clear);
        progress = findViewById(R.id.progress);
        filtersScroll = findViewById(R.id.filters_scroll);
        filters = findViewById(R.id.filters);
        list = findViewById(R.id.results);
        message = findViewById(R.id.message);
        searchScreen = findViewById(R.id.search);
        placeScreen = findViewById(R.id.place);
        placeScroll = findViewById(R.id.place_scroll);
        page = new PlacePage(look, photos,
            findViewById(R.id.place_head), placeScroll, findViewById(R.id.place_body));

        query.setTypeface(look.regular);
        near.setTypeface(look.regular);
        message.setTypeface(look.regular);
        clear.setBackground(Look.circle(color(R.color.button)));
        clear.setImageTintList(ColorStateList.valueOf(color(R.color.text)));
        list.setAdapter(adapter);
        list.setDivider(new ColorDrawable(color(R.color.hairline)));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(0));

        SharedPreferences prefs = prefs();
        login = Logins.load(prefs, Bundled.REVISION, Bundled.SERVER, Bundled.TOKEN);
        anchor = Anchor.parse(prefs.getString("anchor", ""));
        near.setText(prefs.getString("near", ""));
        openNow = prefs.getBoolean("open_now", false);
        topRated = prefs.getBoolean("top_rated", false);
        loadRecent(prefs.getString("recent", ""));

        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search();
                return true;
            }
            return false;
        });
        near.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                doneNear();
                return true;
            }
            return false;
        });
        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                header();
            }
        });
        query.requestFocus();
        if (!login.ready()) say("No command center");
        refresh();
        if (state == null) follow(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        follow(intent);
    }

    /** TEMPORARY: see LocationTest. Runs the test once location is allowed, or says it wasn't. */
    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != LocationTest.REQUEST) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) LocationTest.start(this);
        else toast("Location not allowed");
    }

    @Override
    protected void onPause() {
        // Mid-edit, the saved Near stays what it was: Back can still drop the edit.
        if (zone != Zone.NEAR) saveNear();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        photos.shutdown();
        super.onDestroy();
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (perhaps typed in from the menu) is kept.
     */

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** One client per login, so the key it fetched is reused. */
    private Places places() {
        if (places == null) places = new Places(login);
        return places;
    }

    private void saveNear() {
        prefs().edit().putString("near", near.getText().toString().trim()).apply();
    }

    // ---- Recent searches ----

    private void loadRecent(String saved) {
        recent.clear();
        try {
            for (Object item : Json.array(Json.parse(saved))) {
                if (item instanceof String) recent.add((String) item);
            }
        } catch (RuntimeException e) {
            // None yet.
        }
    }

    private void remember(String what) {
        for (int i = recent.size() - 1; i >= 0; i--) {
            if (recent.get(i).equalsIgnoreCase(what)) recent.remove(i);
        }
        recent.add(0, what);
        while (recent.size() > RECENT_KEPT) recent.remove(recent.size() - 1);
        prefs().edit().putString("recent", Json.write(new ArrayList<Object>(recent))).apply();
    }

    // ---- Background work ----

    /** Runs `work` off the main thread, in place of anything already running. */
    private void start(final Work work) {
        stop();
        final int mine = ++generation;
        progress.setVisibility(View.VISIBLE);
        job = worker.submit(() -> {
            Runnable done;
            try {
                done = work.call();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                final String problem = e.getMessage() != null ? e.getMessage() : e.toString();
                done = () -> {
                    if (showing) toast(problem);
                    else say(problem);
                };
            }
            final Runnable then = done;
            runOnUiThread(() -> {
                if (mine != generation) return;
                job = null;
                progress.setVisibility(View.GONE);
                then.run();
            });
        });
    }

    private boolean busy() {
        return job != null;
    }

    /** Abandons what is running; whatever it was doing is never shown. */
    private void stop() {
        if (job == null) return;
        job.cancel(true);
        job = null;
        generation++;
        progress.setVisibility(View.GONE);
    }

    // ---- Searching ----

    private void search() {
        search(query.getText().toString().trim());
    }

    private void search(final String what) {
        if (what.length() == 0) return;
        if (!login.ready()) {
            askLogin();
            return;
        }
        final String where = near.getText().toString().trim();
        saveNear();
        remember(what);
        final Anchor known = where.length() > 0 && anchor != null && anchor.isFor(where) ? anchor : null;
        final Places client = places();
        final boolean open = openNow;
        final boolean rated = topRated;
        say("");
        start(() -> {
            final Anchor at = where.length() == 0 ? null : known != null ? known : client.locate(where);
            final Places.Page found = client.search(what, at, null, open, rated ? 4.5 : 0);
            return () -> {
                if (at != null && at != known) {
                    anchor = at;
                    prefs().edit().putString("anchor", at.toJson()).apply();
                }
                took(found.places, found.next, found.query, found.near, open, rated);
                // A filter changed from its chip keeps the d-pad on it.
                if (zone != Zone.FILTERS) zone = results.isEmpty() ? Zone.QUERY : Zone.LIST;
                say(results.isEmpty() ? "No results for “" + what + "”" + (at == null ? "" : " near " + at.label) : "");
                list.setSelection(0);
                refresh();
            };
        });
    }

    /**
     * A Google Maps link opened from elsewhere (a text message, say), since
     * the phone's own Maps is disabled: a short link is followed to the full
     * one, which is read for a place's id, a name or a point. A place's id
     * opens that place; a name is searched for near the link's point and the
     * best match opened, with the rest of the results behind it.
     */
    private void follow(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        final String link = intent.getDataString();
        if (!login.ready()) {
            askLogin();
            return;
        }
        if (showing) close();
        final Places client = places();
        say("Opening the link…");
        start(() -> {
            String full = client.expand(link);
            final Link found = Link.parse(full);
            if (found == null) throw new java.io.IOException("Couldn't tell what that link points at.");
            final Place exact = found.placeId != null ? client.details(found.placeId) : null;
            final String what = exact != null ? exact.name() : found.query != null ? found.query : found.near.label;
            final Places.Page page = exact != null ? null : client.search(what, found.near, null);
            return () -> {
                if (exact != null) {
                    details.put(exact.id(), exact);
                    took(Collections.singletonList(exact), null, what, found.near, false, false);
                } else {
                    took(page.places, page.next, what, found.near, false, false);
                }
                query.setText(what);
                remember(what);
                say(results.isEmpty() ? "No results for “" + what + "”" : "");
                zone = results.isEmpty() ? Zone.QUERY : Zone.LIST;
                list.setSelection(0);
                refresh();
                if (!results.isEmpty()) open();
            };
        });
    }

    /** A search's first page, in place of what was shown, with what it searched for. */
    private void took(List<Place> found, String next, String what, Anchor near, boolean open, boolean rated) {
        results.clear();
        results.addAll(found);
        nextPage = next;
        searched = what;
        searchedNear = near;
        searchedOpen = open;
        searchedRated = rated;
        picked = 0;
    }

    /** The next page of the same search, after the ones already shown. */
    private void more() {
        if (busy() || nextPage == null) return;
        final String after = nextPage;
        final String what = searched;
        final Anchor at = searchedNear;
        final boolean open = searchedOpen;
        final boolean rated = searchedRated;
        final Places client = places();
        start(() -> {
            final Places.Page found = client.search(what, at, after, open, rated ? 4.5 : 0);
            return () -> {
                boolean wasLast = picked == results.size() - 1;
                results.addAll(found.places);
                nextPage = found.next;
                if (wasLast && picked + 1 < results.size()) picked++;
                refresh();
            };
        });
    }

    private void clearResults() {
        stop();
        results.clear();
        nextPage = null;
        searched = null;
        picked = 0;
        zone = Zone.QUERY;
        say("");
        refresh();
    }

    // ---- Showing ----

    /** Puts the screen in step with the state. */
    private void refresh() {
        searchScreen.setVisibility(showing ? View.GONE : View.VISIBLE);
        placeScreen.setVisibility(showing ? View.VISIBLE : View.GONE);
        header();
        drawFilters();
        adapter.notifyDataSetChanged();
        boolean empty = adapter.getCount() == 0;
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        message.setVisibility(message.length() > 0 && results.isEmpty() ? View.VISIBLE : View.GONE);
        if (!showing && zone == Zone.LIST && !empty) keepInView();
    }

    /** The header: what is typed, with a lead icon that says what it is for, and the clear button. */
    private void header() {
        boolean editingNear = zone == Zone.NEAR;
        query.setVisibility(editingNear ? View.GONE : View.VISIBLE);
        near.setVisibility(editingNear ? View.VISIBLE : View.GONE);
        EditText field = editingNear ? near : query;
        if (!field.hasFocus()) {
            field.requestFocus();
            field.setSelection(field.length());
        }
        field.setCursorVisible(zone == Zone.QUERY || zone == Zone.NEAR);
        int icon = editingNear ? R.drawable.ic_place : results.isEmpty() ? R.drawable.ic_search : R.drawable.ic_arrow_back;
        lead.setImageResource(icon);
        lead.setImageTintList(ColorStateList.valueOf(color(editingNear ? R.color.link : R.color.text_muted)));
        clear.setVisibility(field.length() > 0 || !results.isEmpty() ? View.VISIBLE : View.INVISIBLE);
    }

    /** Where to search near, open now, top rated: Maps' filter chips, the picked one ringed. */
    private void drawFilters() {
        filters.removeAllViews();
        String where = near.getText().toString().trim();
        addFilter(where.length() == 0 ? "Anywhere" : where, R.drawable.ic_place, false, FILTER_NEAR);
        addFilter("Open now", 0, openNow, FILTER_OPEN);
        addFilter("Top rated", 0, topRated, FILTER_RATED);
        if (zone == Zone.FILTERS) {
            final View chip = filters.getChildAt(filter);
            filtersScroll.post(() -> filtersScroll.smoothScrollTo(Math.max(0, chip.getLeft() - dp(24)), 0));
        } else {
            filtersScroll.post(() -> filtersScroll.smoothScrollTo(0, 0));
        }
    }

    private void addFilter(String label, int iconRes, boolean on, int index) {
        boolean focused = zone == Zone.FILTERS && filter == index;
        int ink = on ? R.color.on_chip : R.color.text;
        TextView chip = look.text(label, 12.5f, ink);
        chip.setTypeface(look.medium);
        chip.setSingleLine(true);
        chip.setMaxWidth(dp(130));
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chip.setHeight(dp(28));
        chip.setPadding(dp(on || iconRes != 0 ? 8 : 12), 0, dp(index == FILTER_NEAR ? 6 : 12), 0);
        chip.setCompoundDrawablePadding(dp(4));
        int lead = on ? R.drawable.ic_check : iconRes;
        chip.setCompoundDrawables(lead == 0 ? null : look.icon(lead, 15, on ? R.color.on_chip : R.color.link), null,
            index == FILTER_NEAR ? look.icon(R.drawable.ic_arrow_drop_down, 16, R.color.text_muted) : null, null);
        int fill = on ? color(R.color.chip) : color(R.color.page);
        chip.setBackground(look.rounded(fill, dp(8)));
        android.graphics.drawable.GradientDrawable shape = (android.graphics.drawable.GradientDrawable) chip.getBackground();
        if (focused) shape.setStroke(dp(2), color(R.color.on_chip));
        else if (!on) shape.setStroke(1, color(R.color.filter_rule));
        LinearLayout.LayoutParams at = Look.at(index == 0 ? 0 : dp(8), 0);
        filters.addView(chip, at);
    }

    /** Below the results, or in place of them: what went wrong, or that nothing was found. */
    private void say(String text) {
        message.setText(text);
        message.setVisibility(text.length() > 0 && results.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Scrolls the list so the picked row, and the picked chip in it, are in view. */
    private void keepInView() {
        list.post(() -> {
            int first = list.getFirstVisiblePosition();
            View row = list.getChildAt(picked - first);
            if (row == null || row.getTop() < 0 || row.getBottom() > list.getHeight()) list.setSelectionFromTop(picked, 0);
        });
    }

    /** Down: from the header to the chips to the list, and along the list. */
    private void down() {
        switch (zone) {
            case QUERY:
                zone = Zone.FILTERS;
                break;
            case FILTERS:
                if (adapter.getCount() == 0) return;
                zone = Zone.LIST;
                picked = Math.min(picked, adapter.getCount() - 1);
                break;
            case LIST:
                if (picked + 1 >= adapter.getCount()) {
                    if (!results.isEmpty()) more();
                    return;
                }
                picked++;
                break;
            default:
                return;
        }
        refresh();
    }

    private void up() {
        switch (zone) {
            case FILTERS:
                zone = Zone.QUERY;
                break;
            case LIST:
                if (picked == 0) zone = Zone.FILTERS;
                else picked--;
                break;
            default:
                return;
        }
        refresh();
    }

    /** Left and Right: along the chips. */
    private void across(int step) {
        if (zone != Zone.FILTERS) return;
        filter = Math.max(0, Math.min(FILTER_RATED, filter + step));
        drawFilters();
    }

    private void ok() {
        switch (zone) {
            case QUERY:
                search();
                break;
            case NEAR:
                doneNear();
                break;
            case FILTERS:
                if (filter == FILTER_NEAR) {
                    editNear();
                    return;
                }
                if (filter == FILTER_OPEN) openNow = !openNow;
                else topRated = !topRated;
                prefs().edit().putBoolean("open_now", openNow).putBoolean("top_rated", topRated).apply();
                drawFilters();
                if (searched != null) search(searched);
                break;
            case LIST:
                if (results.isEmpty()) {
                    String again = recent.get(picked);
                    query.setText(again);
                    query.setSelection(query.length());
                    search(again);
                } else {
                    open();
                }
                break;
        }
    }

    /** Near takes the header until OK or Back. */
    private void editNear() {
        zone = Zone.NEAR;
        refresh();
        near.requestFocus();
        near.setSelection(near.length());
    }

    private void doneNear() {
        saveNear();
        zone = Zone.FILTERS;
        filter = FILTER_NEAR;
        refresh();
        if (searched != null) search(searched);
    }

    // ---- A place ----

    private Place current() {
        Place listed = results.get(picked);
        Place full = details.get(listed.id());
        return full != null ? full : listed;
    }

    /** The picked place's own sheet: what the search knew at once, then everything. */
    private void open() {
        if (results.isEmpty()) return;
        stop();
        showing = true;
        final Place listed = results.get(picked);
        final String id = listed.id();
        boolean known = details.containsKey(id);
        page.show(current(), !known, System.currentTimeMillis(), searchedNear);
        refresh();
        if (known || id.length() == 0) return;
        final Places client = places();
        start(() -> {
            final Place full = client.details(id);
            return () -> {
                details.put(id, full);
                if (showing && results.get(picked) == listed) page.update(full, System.currentTimeMillis(), searchedNear);
            };
        });
    }

    private void close() {
        stop();
        showing = false;
        refresh();
    }

    // ---- Actions ----

    /**
     * What OK offers on a place, each after its icon: call it, copy its
     * address, copy its website's address.
     */
    private void actions() {
        List<Choice> choices = new ArrayList<>();
        placeChoices(choices);
        if (choices.isEmpty()) {
            toast(busy() ? "Still loading." : "Nothing to call or copy.");
            return;
        }
        choose(choices);
    }

    /** What can be done with the place showing: call it, copy its address or its website's. */
    private void placeChoices(List<Choice> choices) {
        final Place place = current();
        if (place.phone().length() > 0) choices.add(new Choice(R.drawable.ic_call, "Call", () -> dial(place.phone())));
        if (place.address().length() > 0) {
            choices.add(new Choice(R.drawable.ic_place, "Copy address", () -> copy("Address", place.localAddress())));
        }
        if (place.website().length() > 0) {
            choices.add(new Choice(R.drawable.ic_language, "Copy website URL", () -> copy("Website", place.website())));
        }
    }

    private void copy(String what, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(what, text));
        toast(what + " copied");
    }

    /** The phone's own dialer, with the number filled in: nothing is dialled until Call is pressed there. */
    private void dial(String phone) {
        if (phone.length() == 0) {
            toast("No phone number listed.");
            return;
        }
        launch(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null));
    }

    private void launch(String action, Uri uri) {
        try {
            startActivity(new Intent(action, uri));
        } catch (ActivityNotFoundException e) {
            toast("Nothing on this phone can open that.");
        }
    }

    // ---- Keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        if (key == KeyEvent.KEYCODE_MENU) {
            if (!down) menu();
            return true;
        }
        if (showing) return placeKey(event, key, down, first);
        if (key == KeyEvent.KEYCODE_BACK) {
            if (!down) back();
            return true;
        }
        // On the press: T9 eats the first release.
        if (Ui.isOk(key)) {
            if (first) ok();
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && zone != Zone.NEAR) down();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && zone != Zone.NEAR) up();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (zone == Zone.QUERY || zone == Zone.NEAR) return super.dispatchKeyEvent(event);
                if (down) across(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_CALL:
                if (first && zone == Zone.LIST && !results.isEmpty()) dial(results.get(picked).phone());
                return true;
            default:
                // Anything else is typing: back to the header with it.
                if (down && (zone == Zone.FILTERS || zone == Zone.LIST) && (event.isPrintingKey() || key == KeyEvent.KEYCODE_DEL)) {
                    zone = Zone.QUERY;
                    refresh();
                }
                return super.dispatchKeyEvent(event);
        }
    }

    /** Back: out of Near, up to the header, then clearing the results, then out. */
    private void back() {
        if (busy()) {
            stop();
            return;
        }
        switch (zone) {
            case NEAR:
                near.setText(prefs().getString("near", ""));
                zone = Zone.FILTERS;
                refresh();
                return;
            case FILTERS:
            case LIST:
                zone = Zone.QUERY;
                refresh();
                return;
            default:
                if (!results.isEmpty() || message.length() > 0) {
                    clearResults();
                    return;
                }
                finish();
        }
    }

    private boolean placeKey(KeyEvent event, int key, boolean down, boolean first) {
        if (Ui.isOk(key)) {
            if (first) actions();
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) placeScroll.smoothScrollBy(0, (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * dp(70));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_CALL:
                if (first) dial(current().phone());
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) close();
                return true;
            default:
                // The header is hidden: typing would land in it unseen.
                return event.isPrintingKey() || super.dispatchKeyEvent(event);
        }
    }

    // ---- Menu ----

    private void menu() {
        List<Choice> choices = new ArrayList<>();
        if (busy()) choices.add(new Choice(R.drawable.ic_close, "Stop", () -> stop()));
        if (showing) {
            placeChoices(choices);
            choices.add(new Choice(R.drawable.ic_arrow_back, "Back to results", () -> close()));
        } else {
            if (query.getText().toString().trim().length() > 0) {
                choices.add(new Choice(R.drawable.ic_search, "Search", () -> search()));
            }
            if (nextPage != null) choices.add(new Choice(R.drawable.ic_expand_more, "More results", () -> more()));
            choices.add(new Choice(R.drawable.ic_place, "Change where", () -> {
                filter = FILTER_NEAR;
                editNear();
            }));
            if (!results.isEmpty()) choices.add(new Choice(R.drawable.ic_close, "Clear results", () -> clearResults()));
            if (!recent.isEmpty() && results.isEmpty()) {
                choices.add(new Choice(R.drawable.ic_history, "Clear recent searches", () -> {
                    recent.clear();
                    prefs().edit().remove("recent").apply();
                    if (zone == Zone.LIST) zone = Zone.QUERY;
                    refresh();
                }));
            }
        }
        // TEMPORARY: see LocationTest.
        choices.add(new Choice(R.drawable.ic_place, "Test location", () -> LocationTest.start(this)));
        choices.add(new Choice(R.drawable.ic_cloud, "Command center\u2026", () -> askLogin()));
        choose(choices);
    }

    /** One line of a list of choices: an icon, a word, and what it does. */
    private static final class Choice {
        final int icon;
        final String label;
        final Runnable run;

        Choice(int icon, String label, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.run = run;
        }
    }

    /** A list of choices, each after a teal icon, in Maps' dark dialog. */
    private void choose(final List<Choice> choices) {
        BaseAdapter rows = new BaseAdapter() {
            @Override
            public int getCount() {
                return choices.size();
            }

            @Override
            public Object getItem(int position) {
                return choices.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                TextView row = convert != null ? (TextView) convert : look.line("", 14f, R.color.text);
                Choice choice = choices.get(position);
                row.setText(choice.label);
                row.setCompoundDrawables(look.icon(choice.icon, 20, R.color.link), null, null, null);
                row.setCompoundDrawablePadding(dp(16));
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(42));
                row.setPadding(dp(18), 0, dp(16), 0);
                return row;
            }
        };
        new AlertDialog.Builder(this)
            .setAdapter(rows, (dialog, which) -> choices.get(which).run.run())
            .show();
    }

    /** The fallback for a build without a bundled login. */
    private void askLogin() {
        LinearLayout box = vertical();
        int pad = dp(16);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText server = new EditText(this);
        server.setHint("https://…");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(login.server() == null ? "" : login.server());
        final EditText token = new EditText(this);
        token.setHint(login.ready() ? "••••••••" : "Token");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(server);
        box.addView(token);
        final AlertDialog asked = new AlertDialog.Builder(this)
            .setTitle("Command center")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .show();
        // Save closes the dialog only once the login is whole, so a slip
        // keeps what was typed for another try.
        asked.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String typed = token.getText().toString();
            if (typed.trim().length() == 0 && login.ready()) typed = login.token();
            Login next;
            try {
                next = Login.of(server.getText().toString(), typed);
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
                (fault(server) ? server : token).requestFocus();
                return;
            }
            if (!next.ready()) {
                toast("Both the address and the token are needed");
                (server.getText().toString().trim().length() == 0 ? server : token).requestFocus();
                return;
            }
            login = next;
            places = null;
            prefs().edit().putString("login", next.toJson()).apply();
            toast("Saved: " + next.host());
            if (message.length() > 0) say("");
            asked.dismiss();
        });
        // Down goes on to the next field, then to Save, as in the app's lists.
        downTo(server, token);
        downTo(token, asked.getButton(AlertDialog.BUTTON_POSITIVE));
    }

    /** Whether the typed address is what Login refuses. */
    private static boolean fault(EditText server) {
        try {
            Login.of(server.getText().toString(), null);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void downTo(View from, final View to) {
        from.setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_DOWN || to == null) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) to.requestFocus();
            return true;
        });
    }

    /**
     * The rows under the chips. With results, each is a place in a few
     * lines, as Google Maps words them: its name; the rating with a gold
     * star, the price, a wheelchair and the kind of place; its full
     * address; whether it is open and how far. No photos and no chips, so several fit a screen.
     * Before a search, the recent ones, each after a clock.
     */
    private final class ResultAdapter extends BaseAdapter {

        private static final int PLACE = 0;
        private static final int RECENT = 1;

        @Override
        public int getCount() {
            return results.isEmpty() ? recent.size() : results.size();
        }

        @Override
        public Object getItem(int position) {
            return results.isEmpty() ? recent.get(position) : results.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return results.isEmpty() ? RECENT : PLACE;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            boolean on = zone == Zone.LIST && position == picked && !showing;
            if (results.isEmpty()) return recentRow(position, convert, on);
            return placeRow(position, (LinearLayout) convert, on);
        }

        private View recentRow(int position, View convert, boolean on) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = look.horizontal();
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                row.addView(look.round(R.drawable.ic_history, 28, 16, R.color.button, R.color.text_muted));
                TextView label = look.line("", 14f, R.color.text);
                LinearLayout.LayoutParams beside = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                beside.leftMargin = dp(12);
                row.addView(label, beside);
            }
            ((TextView) row.getChildAt(1)).setText(recent.get(position));
            row.setBackgroundColor(color(on ? R.color.picked : R.color.page));
            return row;
        }

        private View placeRow(int position, LinearLayout row, boolean on) {
            Place place = results.get(position);
            if (row == null) {
                row = look.vertical();
                row.setPadding(0, dp(8), 0, dp(9));
                TextView name = look.line("", 14.5f, R.color.text);
                row.addView(name, edged(0));
                row.addView(look.line("", 12f, R.color.text_muted), edged(dp(4)));
                // The whole address, wrapped rather than cut: on a list of
                // branches it is what tells them apart.
                TextView address = look.text("", 12f, R.color.text_muted);
                address.setLineSpacing(dp(1), 1f);
                row.addView(address, edged(dp(3)));
                row.addView(look.line("", 12f, R.color.text_muted), edged(dp(3)));
            }
            ((TextView) row.getChildAt(0)).setText(place.name());
            show((TextView) row.getChildAt(1), look.facts(place, true, true));
            show((TextView) row.getChildAt(2), place.localAddress());
            show((TextView) row.getChildAt(3), look.status(place, System.currentTimeMillis(), searchedNear));
            row.setBackgroundColor(color(on ? R.color.picked : R.color.page));
            return row;
        }

        private LinearLayout.LayoutParams edged(int top) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp(12);
            params.rightMargin = dp(12);
            params.topMargin = top;
            return params;
        }

        /** A line that takes no room when there is nothing to put on it. */
        private void show(TextView view, CharSequence text) {
            view.setText(text);
            view.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }
}
