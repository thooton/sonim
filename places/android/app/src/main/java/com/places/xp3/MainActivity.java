package com.places.xp3;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
    private TextView countView;
    private TextView statusView;
    private ListView resultList;
    private ScrollView detail;
    private LinearLayout detailBody;
    private final ResultAdapter adapter = new ResultAdapter();
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
        countView = (TextView) findViewById(R.id.count);
        statusView = (TextView) findViewById(R.id.status);
        resultList = (ListView) findViewById(R.id.results);
        detail = (ScrollView) findViewById(R.id.detail);
        detailBody = (LinearLayout) findViewById(R.id.detail_body);
        resultList.setAdapter(adapter);
        Family.list(resultList);

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
        say(login.ready() ? "" : "No command center: Menu → Command center.");
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
        countView.setText((picking || showing) && !results.isEmpty() ? (picked + 1) + "/" + results.size() : "");
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

    /** The sections that share the first card: how to reach the place. */
    private static final List<String> CONTACT = Arrays.asList("Address", "Phone", "Website", "Local time there");

    /**
     * The name, large, over the lines that run on from it (kind, rating,
     * price, open or closed, how far); then the facts on cards, a muted
     * label over each value. The ways to reach it share the first card, and
     * every other section (hours, about, payment) has a card of its own. A
     * place that only has what the search returned shows just its first
     * lines and address, rather than "no hours" for hours not yet fetched.
     */
    private void render(Place place, boolean partial) {
        detailBody.removeAllViews();
        long now = System.currentTimeMillis();
        LinearLayout head = vertical();
        head.setPadding(dp(10), dp(9), dp(10), dp(3));
        TextView name = detailLine(place.name(), R.dimen.text_detail_title, R.color.text);
        name.setTypeface(Family.MEDIUM);
        head.addView(name);
        detailBody.addView(head);

        LinearLayout contact = null;
        for (Place.Section section : place.details(now, searchedNear)) {
            if (partial && section.title != null && !section.title.equals("Address")) continue;
            if (section.title == null) {
                // Only open-or-closed takes a colour, as on the result's row.
                String status = place.status(now);
                Boolean open = place.openNow();
                SpannableStringBuilder lines = new SpannableStringBuilder();
                for (String line : section.text.split("\n")) {
                    if (lines.length() > 0) lines.append('\n');
                    if (open != null && line.equals(status)) {
                        span(lines, line, new ForegroundColorSpan(color(open ? R.color.open : R.color.closed)));
                    } else {
                        lines.append(line);
                    }
                }
                TextView summary = detailLine(lines, R.dimen.text_address, R.color.text_muted);
                summary.setPadding(0, dp(3), 0, 0);
                summary.setLineSpacing(dp(2), 1f);
                head.addView(summary);
                continue;
            }
            boolean reach = CONTACT.contains(section.title);
            LinearLayout card;
            if (reach && contact != null) {
                card = contact;
                View rule = new View(this);
                rule.setBackgroundColor(color(R.color.divider));
                LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                line.leftMargin = dp(10);
                card.addView(rule, line);
            } else {
                card = vertical();
                card.setBackgroundResource(com.xp3.common.ui.R.drawable.family_card);
                LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                at.setMargins(dp(6), dp(6), dp(6), 0);
                detailBody.addView(card, at);
                if (reach) contact = card;
            }
            // What Menu can act on (call it, open it) is in the accent.
            boolean actionable = section.title.equals("Phone") || section.title.equals("Website");
            LinearLayout fact = vertical();
            fact.setPadding(dp(10), dp(7), dp(10), dp(8));
            fact.addView(detailLine(section.title, R.dimen.text_detail_label, R.color.text_muted));
            TextView value = detailLine(section.text, R.dimen.text_detail, actionable ? R.color.focus_rule : R.color.text);
            value.setPadding(0, dp(2), 0, 0);
            value.setLineSpacing(dp(2), 1f);
            fact.addView(value);
            card.addView(fact);
        }
        detail.scrollTo(0, 0);
    }

    private TextView detailLine(CharSequence text, int size, int textColor) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color(textColor));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(size));
        view.setIncludeFontPadding(false);
        return view;
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
        token.setHint(login.ready() ? "Token (blank keeps it)" : "Delta token");
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
     * A result: its name, then where it is (street and town, in full-strength
     * text, since that is what tells one branch from another), then rating,
     * price and kind, then how far and whether it is open now. Name and
     * address wrap to a second line rather than lose their ends.
     */
    private final class ResultAdapter extends BaseAdapter {

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
                int padV = dp(7);
                row = vertical();
                row.setBackgroundResource(com.xp3.common.ui.R.drawable.family_pick);
                row.setPadding(padH, padV, padH, padV);
                row.addView(line(R.dimen.text_name, R.color.text, true, 2));
                row.addView(line(R.dimen.text_address, R.color.text, false, 2));
                row.addView(line(R.dimen.text_meta, R.color.text_muted, false, 1));
                row.addView(line(R.dimen.text_meta, R.color.text_muted, false, 1));
            }
            Place place = results.get(position);
            ((TextView) row.getChildAt(0)).setText(place.name());
            show((TextView) row.getChildAt(1), place.shortAddress());
            show((TextView) row.getChildAt(2), place.summary());
            // Only the open-or-closed part takes a colour; the distance before it stays muted.
            String status = place.status(System.currentTimeMillis());
            String distance = place.distanceFrom(searchedNear);
            SpannableStringBuilder last = new SpannableStringBuilder(distance.length() == 0 ? "" : distance + " · ");
            Boolean open = place.openNow();
            if (open == null) last.append(status);
            else span(last, status, new ForegroundColorSpan(color(open ? R.color.open : R.color.closed)));
            ((TextView) row.getChildAt(3)).setText(last);
            row.setActivated(picking && position == picked);
            return row;
        }

        private TextView line(int size, int textColor, boolean bold, int lines) {
            TextView view = new TextView(MainActivity.this);
            view.setTextColor(color(textColor));
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(size));
            if (bold) view.setTypeface(Family.MEDIUM);
            view.setMaxLines(lines);
            view.setIncludeFontPadding(false);
            view.setPadding(0, bold ? 0 : dp(2), 0, 0);
            view.setEllipsize(TextUtils.TruncateAt.END);
            return view;
        }

        /** A line that takes no room when there is nothing to put on it. */
        private void show(TextView view, String text) {
            view.setText(text);
            view.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }
}
