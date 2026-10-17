package com.places.xp3;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import com.places.xp3.net.Anchor;
import com.places.xp3.net.Place;
import com.places.xp3.net.Places;
import com.xp3.common.Login;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * One screen: where to search near, what to find, and the places found.
 *
 * Near is kept between searches (and the point Google found for it, so it is
 * looked up once). OK in Near moves to Find; OK in Find searches, and the
 * results come up with the first one picked. Up and Down move through them
 * (Down past the last loads more), OK shows everything about the picked
 * place, and in there Left and Right go to the previous or next one, Call
 * dials it, and Back goes back to the list.
 */
public final class MainActivity extends BaseActivity {

    private static final String PREFS = "places";

    /** Work for the background thread: returns what to do with its result on the main thread. */
    private interface Work {
        Runnable call() throws Exception;
    }

    private EditText near;
    private EditText query;
    private View form;
    private TextView statusView;
    private ListView resultList;
    private ScrollView detail;
    private LinearLayout detailBody;
    private final ResultAdapter adapter = new ResultAdapter();
    private final PlacePage page = new PlacePage(this);
    private final ExecutorService worker = Ui.worker("places");

    private Login login = Login.blank();
    private Places places;
    /** Where the last search was made near, with Google's point for it. */
    private Anchor anchor;

    /** The results so far, the search they answer, and Google's token for more. */
    private final List<Place> results = new ArrayList<Place>();
    private String searched;
    private Anchor searchedNear;
    private String nextPage;
    /** Places whose details were fetched, by id, so going back and forth costs nothing. */
    private final Map<String, Place> details = new HashMap<String, Place>();

    /** Whether the keys are moving through the results rather than typing. */
    private boolean picking;
    private int picked;
    /** Whether the picked place's details fill the screen. */
    private boolean showing;

    private Future<?> job;
    private int generation;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        near = (EditText) findViewById(R.id.near);
        query = (EditText) findViewById(R.id.query);
        form = findViewById(R.id.form);
        statusView = (TextView) findViewById(R.id.status);
        resultList = (ListView) findViewById(R.id.results);
        detail = (ScrollView) findViewById(R.id.detail);
        detailBody = (LinearLayout) findViewById(R.id.detail_body);
        resultList.setAdapter(adapter);
        Family.list(resultList);
        // Results parted by a band of the page behind a place, as Google Maps parts them.
        resultList.setDivider(new ColorDrawable(color(R.color.grouped_page)));
        resultList.setDividerHeight(dp(6));

        SharedPreferences prefs = prefs();
        login = loadLogin(prefs);
        anchor = Anchor.parse(prefs.getString("anchor", ""));
        near.setText(prefs.getString("near", ""));
        near.setSelection(near.length());

        near.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                toFind();
                return true;
            }
            return false;
        });
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search();
                return true;
            }
            return false;
        });
        if (near.length() == 0) near.requestFocus();
        else query.requestFocus();
        say(login.ready() ? "" : "No command center");
        refresh();
    }

    @Override
    protected void onPause() {
        saveNear();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (perhaps typed in from the menu) is kept.
     */
    private Login loadLogin(SharedPreferences prefs) {
        String revision = Bundled.REVISION;
        if (revision.length() > 0 && !revision.equals(prefs.getString("bundled", ""))) {
            Login bundled;
            try {
                bundled = Login.of(Bundled.SERVER, Bundled.TOKEN);
            } catch (IllegalArgumentException e) {
                bundled = Login.blank();
            }
            if (bundled.ready()) {
                prefs.edit().putString("login", bundled.toJson()).putString("bundled", revision).apply();
                return bundled;
            }
        }
        return Login.parse(prefs.getString("login", ""));
    }

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

    private void toFind() {
        saveNear();
        query.requestFocus();
        query.setSelection(query.length());
    }

    // ---- Background work ----

    /** Runs `work` off the main thread, in place of anything already running. */
    private void start(String doing, final Work work) {
        stop();
        final int mine = ++generation;
        say(doing);
        job = worker.submit(() -> {
            Runnable done;
            try {
                done = work.call();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                final String message = e.getMessage() != null ? e.getMessage() : e.toString();
                done = () -> say(message);
            }
            final Runnable then = done;
            runOnUiThread(() -> {
                if (mine != generation) return;
                job = null;
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
    }

    // ---- Searching ----

    private void search() {
        if (busy()) return;
        final String what = query.getText().toString().trim();
        if (what.length() == 0) {
            setPicking(true);
            return;
        }
        if (!login.ready()) {
            askLogin();
            return;
        }
        final String where = near.getText().toString().trim();
        saveNear();
        final Anchor known = where.length() > 0 && anchor != null && anchor.isFor(where) ? anchor : null;
        final Places client = places();
        start(where.length() > 0 && known == null ? "Finding " + where + "…" : "Searching…", () -> {
            final Anchor at = where.length() == 0 ? null : known != null ? known : client.locate(where);
            final Places.Page page = client.search(what, at, null);
            return () -> {
                if (at != null && at != known) {
                    anchor = at;
                    prefs().edit().putString("anchor", at.toJson()).apply();
                }
                results.clear();
                results.addAll(page.places);
                searched = page.query;
                searchedNear = page.near;
                nextPage = page.next;
                picked = 0;
                showing = false;
                picking = !results.isEmpty();
                say(found());
                refresh();
            };
        });
    }

    /** The next page of the same search, after the ones already shown. */
    private void more() {
        if (busy() || nextPage == null) return;
        final String after = nextPage;
        final String what = searched;
        final Anchor at = searchedNear;
        final Places client = places();
        start("Loading more…", () -> {
            final Places.Page page = client.search(what, at, after);
            return () -> {
                boolean wasLast = picked == results.size() - 1;
                results.addAll(page.places);
                nextPage = page.next;
                if (wasLast && picked + 1 < results.size()) picked++;
                say(found());
                refresh();
            };
        });
    }

    /** "20+ places near Tempe, AZ, USA". */
    private String found() {
        String where = searchedNear == null ? "" : " near " + searchedNear.label;
        if (results.isEmpty()) return "Nothing found" + where + ".";
        int count = results.size();
        return count + (nextPage != null ? "+" : "") + (count == 1 && nextPage == null ? " place" : " places") + where;
    }

    // ---- Showing ----

    /** Puts the screen in step with the state: the form and list, or one place. */
    private void refresh() {
        form.setVisibility(showing ? View.GONE : View.VISIBLE);
        resultList.setVisibility(showing ? View.GONE : View.VISIBLE);
        detail.setVisibility(showing ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        if (!showing) {
            if (!near.hasFocus() && !query.hasFocus()) query.requestFocus();
            if (picking) resultList.post(() -> resultList.smoothScrollToPosition(picked));
        }
    }

    /** The line under the fields; it takes no room while there is nothing to say. */
    private void say(String text) {
        statusView.setText(text);
        statusView.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void setPicking(boolean on) {
        picking = on && !results.isEmpty();
        if (picking) picked = Math.min(picked, results.size() - 1);
        refresh();
    }

    private void movePick(int step) {
        int next = picked + step;
        if (next < 0) {
            setPicking(false);
            return;
        }
        if (next >= results.size()) {
            more();
            return;
        }
        picked = next;
        refresh();
    }

    private Place current() {
        Place listed = results.get(picked);
        Place full = details.get(listed.id());
        return full != null ? full : listed;
    }

    /** The picked place's own screen: what the search knew at once, then everything. */
    private void open() {
        if (results.isEmpty()) return;
        stop();
        showing = true;
        picking = true;
        final Place listed = results.get(picked);
        final String id = listed.id();
        boolean known = details.containsKey(id);
        render(current(), !known);
        say("");
        refresh();
        if (known || id.length() == 0) return;
        final Places client = places();
        start("Loading details…", () -> {
            final Place full = client.details(id);
            return () -> {
                details.put(id, full);
                say("");
                if (showing && results.get(picked) == listed) render(full, false);
            };
        });
    }

    private void close() {
        stop();
        showing = false;
        say(found());
        refresh();
    }

    /** Left and Right, on a place's screen: the one before or after it in the results. */
    private void step(int by) {
        int next = picked + by;
        if (next < 0 || next >= results.size()) return;
        picked = next;
        open();
    }

    /** The picked place on its own screen, laid out by {@link PlacePage}, scrolled to the top. */
    private void render(Place place, boolean partial) {
        page.fill(detailBody, place, partial, System.currentTimeMillis(), searchedNear);
        detail.scrollTo(0, 0);
    }

    private static void span(SpannableStringBuilder out, String text, Object... spans) {
        int start = out.length();
        out.append(text);
        for (Object span : spans) out.setSpan(span, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
        if (key == KeyEvent.KEYCODE_BACK && busy()) {
            if (!down) {
                stop();
                say("Stopped.");
            }
            return true;
        }
        if (picking) return pickingKey(event, key, down, first);
        // On the press: T9 eats the first release.
        if (Ui.isOk(key)) {
            if (first) {
                if (near.hasFocus()) toFind();
                else search();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (first) {
                if (near.hasFocus()) toFind();
                else setPicking(true);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (first && query.hasFocus()) {
                near.requestFocus();
                near.setSelection(near.length());
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean pickingKey(KeyEvent event, int key, boolean down, boolean first) {
        if (Ui.isOk(key)) {
            if (first) open();
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) movePick(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) setPicking(false);
                return true;
            default:
                // Anything else is typing: back to the box with it.
                if (down && event.isPrintingKey()) setPicking(false);
                return super.dispatchKeyEvent(event);
        }
    }

    private boolean placeKey(KeyEvent event, int key, boolean down, boolean first) {
        if (Ui.isOk(key)) {
            if (first) menu();
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) detail.smoothScrollBy(0, (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * dp(60));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (first) step(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_CALL:
                if (first) dial(current().phone());
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) close();
                return true;
            default:
                // The fields are hidden: typing would land in one unseen.
                return event.isPrintingKey() || super.dispatchKeyEvent(event);
        }
    }

    // ---- Menu ----

    private void menu() {
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        if (busy()) {
            labels.add("Stop");
            actions.add(() -> {
                stop();
                say(showing ? "" : "Stopped.");
            });
        }
        if (showing) {
            final Place place = current();
            if (place.phone().length() > 0) {
                labels.add("Call " + place.phone());
                actions.add(() -> dial(place.phone()));
            }
            if (place.website().length() > 0) {
                labels.add("Open website");
                actions.add(() -> view(place.website()));
            }
            if (picked + 1 < results.size()) {
                labels.add("Next place");
                actions.add(() -> step(1));
            }
            if (picked > 0) {
                labels.add("Previous place");
                actions.add(() -> step(-1));
            }
            labels.add("Back to results");
            actions.add(() -> close());
        } else if (!busy()) {
            if (query.getText().toString().trim().length() > 0) {
                labels.add("Search");
                actions.add(() -> search());
            }
            if (nextPage != null) {
                labels.add("More results");
                actions.add(() -> more());
            }
            labels.add("Change where");
            actions.add(() -> {
                setPicking(false);
                near.requestFocus();
                near.selectAll();
            });
            if (!results.isEmpty()) {
                labels.add("Clear results");
                actions.add(() -> {
                    results.clear();
                    nextPage = null;
                    picking = false;
                    say("");
                    refresh();
                });
            }
        }
        labels.add("Command center…");
        actions.add(() -> askLogin());
        new AlertDialog.Builder(this)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
            .show();
    }

    /** The phone's own dialer, with the number filled in: nothing is dialled until Call is pressed there. */
    private void dial(String phone) {
        if (phone.length() == 0) {
            toast(showing && busy() ? "Still loading the details." : "No phone number listed.");
            return;
        }
        launch(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null));
    }

    private void view(String address) {
        launch(Intent.ACTION_VIEW, Uri.parse(address));
    }

    private void launch(String action, Uri uri) {
        try {
            startActivity(new Intent(action, uri));
        } catch (ActivityNotFoundException e) {
            toast("Nothing on this phone can open that.");
        }
    }

    /** The fallback for a build without a bundled login. */
    private void askLogin() {
        LinearLayout box = vertical();
        int pad = dp(12);
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
        new AlertDialog.Builder(this)
            .setTitle("Command center")
            .setView(box)
            .setPositiveButton("Save", (dialog, which) -> {
                String typed = token.getText().toString();
                if (typed.trim().length() == 0 && login.ready()) typed = login.token();
                try {
                    Login next = Login.of(server.getText().toString(), typed);
                    if (!next.ready()) throw new IllegalArgumentException("Both the address and the token are needed");
                    login = next;
                    places = null;
                    prefs().edit().putString("login", next.toJson()).apply();
                    say("Saved: " + next.host());
                } catch (IllegalArgumentException e) {
                    say(e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * A result, laid out as Google Maps lays out one in its list: the name;
     * the rating with a gold star and the count, the price range, a
     * wheelchair in the accent when the entrance takes one, and the kind of
     * place; the full address, since that is what tells one branch from
     * another; then "Open" in green or "Closed" in red, when that changes,
     * and how far. A line with nothing to say takes no room, and results are
     * parted by a band of the grouped page, as Maps parts them.
     */
    private final class ResultAdapter extends BaseAdapter {

        /** Shared by every row: the star after a rating, and the wheelchair. */
        private Drawable star;
        private Drawable wheelchair;

        @Override
        public int getCount() {
            return results.size();
        }

        @Override
        public Object getItem(int position) {
            return results.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                int padH = getResources().getDimensionPixelSize(R.dimen.pad_h);
                row = vertical();
                row.setBackgroundResource(com.xp3.common.ui.R.drawable.family_pick);
                row.setPadding(padH, dp(8), padH, dp(9));
                row.addView(line(R.dimen.text_name, R.color.text, true, 2));
                row.addView(line(R.dimen.text_meta, R.color.text_muted, false, 1));
                row.addView(line(R.dimen.text_meta, R.color.text_muted, false, 3));
                row.addView(line(R.dimen.text_meta, R.color.text_muted, false, 2));
            }
            Place place = results.get(position);
            ((TextView) row.getChildAt(0)).setText(place.name());
            show((TextView) row.getChildAt(1), facts(place));
            show((TextView) row.getChildAt(2), place.address());
            show((TextView) row.getChildAt(3), status(place, System.currentTimeMillis()));
            row.setActivated(picking && position == picked);
            return row;
        }

        /** "4.8 ★ (112) · $10-20 · ♿ · Cafe": the rating in the text colour, the rest muted. */
        private CharSequence facts(Place place) {
            SpannableStringBuilder out = new SpannableStringBuilder();
            if (place.ratingCount() > 0) {
                if (star == null) star = new Stars(1, 1, dp(10), 0, color(R.color.star), color(R.color.star_empty));
                span(out, String.format(Locale.US, "%.1f", place.rating()), new ForegroundColorSpan(color(R.color.text)));
                out.append(' ');
                span(out, " ", new ImageSpan(star, ImageSpan.ALIGN_BASELINE));
                out.append(String.format(Locale.US, " (%,d)", place.ratingCount()));
            }
            String price = place.priceShort();
            if (price.length() > 0) dot(out).append(price);
            if (place.wheelchairEntrance()) {
                if (wheelchair == null) {
                    wheelchair = getDrawable(R.drawable.ic_accessible).mutate();
                    wheelchair.setTint(color(R.color.focus_rule));
                    wheelchair.setBounds(0, 0, dp(12), dp(12));
                }
                span(dot(out), " ", new ImageSpan(wheelchair, ImageSpan.ALIGN_BASELINE));
            }
            if (place.type().length() > 0) dot(out).append(place.type());
            return out;
        }

        /** "Closed · Opens 7:00 AM · 3.4 mi": only the open-or-closed word in colour, and Medium. */
        private CharSequence status(Place place, long now) {
            SpannableStringBuilder out = new SpannableStringBuilder();
            String[] parts = place.statusParts(now);
            if (!parts[0].equals("Hours unknown")) {
                Boolean open = place.openNow();
                int tone = open == null ? R.color.text_muted : open ? R.color.open : R.color.closed;
                span(out, parts[0], new ForegroundColorSpan(color(tone)), new TypefaceSpan("sans-serif-medium"));
                if (parts[1].length() > 0) dot(out).append(parts[1]);
            }
            String distance = place.distanceFrom(searchedNear);
            if (distance.length() > 0) dot(out).append(distance);
            return out;
        }

        /** " · " between the parts of a line, none before the first. */
        private SpannableStringBuilder dot(SpannableStringBuilder out) {
            return out.length() == 0 ? out : out.append(" \u00b7 ");
        }

        private TextView line(int size, int textColor, boolean bold, int lines) {
            TextView view = new TextView(MainActivity.this);
            view.setTextColor(color(textColor));
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(size));
            if (bold) view.setTypeface(Family.MEDIUM);
            view.setMaxLines(lines);
            view.setIncludeFontPadding(false);
            view.setLineSpacing(dp(1), 1f);
            view.setPadding(0, bold ? 0 : dp(4), 0, 0);
            view.setEllipsize(TextUtils.TruncateAt.END);
            return view;
        }

        /** A line that takes no room when there is nothing to put on it. */
        private void show(TextView view, CharSequence text) {
            view.setText(text);
            view.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }
}
