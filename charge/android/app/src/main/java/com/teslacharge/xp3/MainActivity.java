package com.teslacharge.xp3;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.teslacharge.xp3.data.ApiException;
import com.teslacharge.xp3.data.ChargingRepository;
import com.teslacharge.xp3.data.FavoriteStore;
import com.teslacharge.xp3.data.Normalizer;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Tabs;
import com.xp3.common.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends BaseActivity {

    private static final int FAVORITES = 0;
    private static final int DISCOVER = 1;
    private static final int SESSION = 2;
    private static final int HISTORY = 3;
    private static final int CHARGING_IDLE = 0;
    private static final int CHARGING_STARTING = 1;
    private static final int CHARGING_STOPPING = 2;
    private static final int MAX_FAVORITE_REQUESTS = 5;
    /** How close to the last loaded session the selection has to get before the
     *  next page is asked for. Two rows is roughly one press of ahead-of-time
     *  on a screen this size, which is enough to hide the request. */
    private static final int HISTORY_PREFETCH_ROWS = 2;

    /**
     * Every request the app makes, on up to ten threads.
     *
     * Threads are created as work arrives and go away again when it stops, so
     * the ten is a ceiling and not a cost. Favorite refreshes admit at most
     * five requests at once, leaving capacity for work triggered elsewhere in
     * the app. Results land on the main thread in whatever order they arrive,
     * which is what the generation counters are for.
     */
    private final ExecutorService network = pool();

    private static ExecutorService pool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            10,
            10,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>()
        );
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private ChargingRepository repository;
    private FavoriteStore favorites;
    private FrameLayout content;
    private Tabs tabs;
    private int currentTab = FAVORITES;
    private boolean detailVisible;
    private double radiusMiles = 12;
    private String lastSearchQuery = "";
    private JSONObject searchOrigin;
    private int searchRequestGeneration;
    private boolean searching;
    private String searchError;
    // The Discover screen on show. A search outlives the views it started
    // from when the user changes tab and comes back, so its answer goes to
    // whichever Discover is drawn when it lands.
    private TextView discoverStatus;
    private SiteAdapter discoverAdapter;
    private ListView discoverList;
    private final List<JSONObject> favoriteSites = new ArrayList<>();
    private final List<JSONObject> searchResults = new ArrayList<>();
    private final List<JSONObject> historySessions = new ArrayList<>();
    private boolean favoritesRefreshing;
    private int favoritesRefreshGeneration;
    private int favoritesRefreshRemaining;
    private int favoritesRefreshFailures;
    private int favoritesRefreshNext;
    private int favoritesRefreshInFlight;
    private boolean historyLoaded;
    private int historyLoadGeneration;
    /** Newest page already in {@link #historySessions}; Tesla counts from one. */
    private int historyPage;
    private boolean historyHasMore;
    private boolean historyLoadingMore;
    /** Why the last page did not arrive, shown on the footer until the next
     *  attempt clears it. */
    private String historyMoreError;
    private HistoryAdapter historyAdapter;
    private JSONObject currentSite;
    /** The charger the open detail is for, set as soon as it is asked for, so
     *  Refresh retries one that failed to load rather than the last one that
     *  did. */
    private String siteGuid;
    /** The charger {@link #openSite} is currently waiting on. A response for
     *  any other one belongs to a screen that has since been left, and drawing
     *  it would put a detail over whatever replaced it. */
    private String pendingSiteGuid;
    private int pendingSiteGeneration;
    private JSONObject currentSession;
    private JSONObject currentHistorySession;
    /** Invalidates an explicit active-session lookup when a newer service
     *  reading, or another lookup, supersedes it. */
    private int activeSessionRequestGeneration;
    /** A paid charging mutation currently awaiting Tesla's answer. */
    private int chargingOperation = CHARGING_IDLE;
    private int chargingOperationGeneration;
    /** Why the last session reading did not arrive, shown on the session screen
     *  so numbers that have stopped advancing say so. Cleared by the next
     *  reading that does arrive. */
    private String sessionError;
    // Why the last lookup for a running session failed, while no session is
    // known. Without it the Session tab would claim there is no session when
    // the app simply never got to ask.
    private String sessionLookupError;
    /** The card Tesla bills, as shown on the start-charging dialog. Null until
     *  {@link #loadPaymentMethod} answers, and after an answer of "no card". */
    private String paymentMethod;
    private boolean paymentMethodLoading;
    private int accountRequestGeneration;
    /** What to tell when the card lands, set by whoever is waiting on it. */
    private Runnable paymentMethodArrival;

    /**
     * Readings from {@link ChargingService}, which owns the poll. This screen
     * no longer asks for the session itself: the service has to keep watching
     * with the app closed to hold its notification up, and a second poller here
     * would only be asking Tesla the same question twice.
     */
    private final ChargingService.Listener sessionUpdates =
        (session, error) -> {
            sessionError = error;
            // Deliberately does not invalidate an in-flight lookup. A poll
            // lands every few seconds, so bumping the generation here
            // discarded nearly every explicit lookup's callback, and with
            // it the watchOrRelease that starts and stops the service. Both
            // read the same session, and whichever applies second is
            // corrected by the next poll a moment later.
            if (session != null) {
                currentSession = session;
                sessionLookupError = null;
            }
            if (currentTab == SESSION && !detailVisible) renderSession();
            // The session just ended: an open charger detail is still
            // showing a disabled Start button, so redraw it to hand
            // charging back to the user.
            if (
                !sessionActive() &&
                detailVisible &&
                currentHistorySession == null &&
                currentSite != null
            ) renderSite();
        };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        repository = ChargingRepository.get(this);
        favorites = new FavoriteStore(this);
        content = findViewById(R.id.content);
        tabs = new Tabs(this, tab -> showTab(tab, true), R.id.tab_favorites, R.id.tab_discover, R.id.tab_session, R.id.tab_history);
        favoriteSites.addAll(favorites.all());
        showTab(FAVORITES, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // onResume is the app-open boundary even when Android kept this
        // Activity alive in the background, so do not rely on onCreate alone.
        refreshFavorites(true);
        // Registering also tells the service someone is watching, which is what
        // moves it off its background interval onto a live one.
        ChargingService.observe(sessionUpdates);
        // Whatever the service learned while this screen was away. It polls on
        // its own schedule, so the newest reading is usually already in hand
        // and there is no reason to open on a stale one.
        JSONObject latest = ChargingService.latest();
        if (latest != null) {
            activeSessionRequestGeneration++;
            currentSession = latest;
            sessionLookupError = null;
            sessionError = ChargingService.latestError();
            if (currentTab == SESSION && !detailVisible) renderSession();
        }
        if (sessionActive()) {
            // Believing a session is running is not the same as knowing it. The
            // service holds the truth while this screen is away, so with no
            // reading from it what is on hand is only what was true when the app
            // was last open. A charge that finished in the meantime would
            // otherwise restart a watch, and a notification, for a session that
            // is over.
            if (ChargingService.latest() != null) startWatchingSession();
            else resumeActiveSession();
        } else if (currentSession == null) {
            // Nothing known yet, or the last ask failed: a session started
            // from the car while this was away, or a signal that has come
            // back, only shows if every open asks again.
            resumeActiveSession();
        }
    }

    @Override
    protected void onPause() {
        ChargingService.unobserve(sessionUpdates);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ChargingService.unobserve(sessionUpdates);
        network.shutdownNow();
        super.onDestroy();
    }

    private void showTab(int tab, boolean focusContent) {
        currentTab = tab;
        detailVisible = false;
        currentHistorySession = null;
        pendingSiteGuid = null;
        pendingSiteGeneration++;
        tabs.mark(tab);
        if (tab == FAVORITES) renderFavorites(focusContent);
        else if (tab == DISCOVER) renderDiscover();
        else if (tab == SESSION) renderSession();
        else renderHistory(focusContent);
    }

    private void renderFavorites(boolean focusContent) {
        // The tab bar already reads "Favorites"; repeating it as a heading would
        // spend 34 of 284 usable pixels saying nothing new.
        LinearLayout root = vertical();
        if (favoriteSites.isEmpty()) {
            TextView empty = emptyMessage("No favorites yet");
            root.addView(empty, Ui.weighted());
            Button discover = action("Go to Discover");
            discover.setOnClickListener(v -> showTab(DISCOVER, true));
            root.addView(discover);
            if (focusContent) discover.requestFocus();
        } else if (favoritesRefreshing) {
            root.addView(spinner(), Ui.weighted());
        } else {
            if (favoritesRefreshFailures > 0) {
                root.addView(
                    warning(
                        favoritesRefreshFailures == 1
                            ? "1 favorite could not be refreshed"
                            : favoritesRefreshFailures + " favorites could not be refreshed"
                    )
                );
            }
            final SiteAdapter adapter = new SiteAdapter(this);
            adapter.replace(favoriteSites);
            ListView list = listView(adapter);
            root.addView(list, Ui.weighted());
            wireSiteList(list, adapter, null);
            if (focusContent) list.requestFocus();
        }
        Ui.fill(content, root);
    }

    /** Collects live favorite data behind the loading screen. */
    private void applyFavoriteRefresh(int generation, String id, JSONObject refreshed) {
        if (generation != favoritesRefreshGeneration || !favorites.contains(id)) return;
        for (int i = 0; i < favoriteSites.size(); i++) {
            if (id.equals(favoriteSites.get(i).optString("id"))) {
                favoriteSites.set(i, refreshed);
                break;
            }
        }
    }

    private void finishFavoriteRefresh(
        List<JSONObject> bookmarks,
        int generation,
        String id,
        boolean failed,
        boolean focusContent
    ) {
        if (generation != favoritesRefreshGeneration) return;
        favoritesRefreshInFlight--;
        // A bookmark may have been removed from its detail screen while this
        // request was in flight. Its failure no longer belongs in Favorites.
        if (failed && favorites.contains(id)) favoritesRefreshFailures++;
        favoritesRefreshRemaining--;
        if (favoritesRefreshRemaining <= 0) {
            favoritesRefreshing = false;
            if (currentTab == FAVORITES && !detailVisible) {
                renderFavorites(focusContent);
            }
        } else {
            dispatchFavoriteRefreshes(bookmarks, generation, focusContent);
        }
    }

    /**
     * Keeps the favorite fan-out below half of the shared pool. Submitting the
     * tail only as each request reports means waiting favorites do not occupy
     * worker threads that another screen can use.
     */
    private void dispatchFavoriteRefreshes(
        final List<JSONObject> bookmarks,
        final int generation,
        final boolean focusContent
    ) {
        if (generation != favoritesRefreshGeneration) return;
        while (
            favoritesRefreshInFlight < MAX_FAVORITE_REQUESTS &&
            favoritesRefreshNext < bookmarks.size()
        ) {
            final String id = bookmarks.get(favoritesRefreshNext++).optString("id");
            favoritesRefreshInFlight++;
            async(
                () -> repository.site(id),
                site -> {
                    applyFavoriteRefresh(generation, id, site);
                    finishFavoriteRefresh(bookmarks, generation, id, false, focusContent);
                },
                error -> {
                    // The stored bookmark remains visible, but contains no
                    // live availability or price that could be mistaken for
                    // a fresh reading.
                    finishFavoriteRefresh(bookmarks, generation, id, true, focusContent);
                }
            );
        }
    }

    /**
     * A favorite stored on the phone contains identity/address metadata only.
     * Fetch detail for every bookmark at app launch so open stalls and the
     * price for the site's current local hour always come from Tesla.
     */
    private void refreshFavorites(final boolean focusContent) {
        if (favoritesRefreshing || favoriteSites.isEmpty()) return;
        final List<JSONObject> bookmarks = new ArrayList<>(favorites.all());
        if (bookmarks.isEmpty()) {
            favoriteSites.clear();
            favoritesRefreshFailures = 0;
            if (currentTab == FAVORITES) renderFavorites(focusContent);
            return;
        }

        favoritesRefreshing = true;
        final int generation = ++favoritesRefreshGeneration;
        favoritesRefreshRemaining = bookmarks.size();
        favoritesRefreshFailures = 0;
        favoritesRefreshNext = 0;
        favoritesRefreshInFlight = 0;
        // Drop readings retained from the previous foreground visit before
        // announcing a refresh. The stored rows contain place metadata only,
        // so every dash now means "waiting" rather than looking like a live
        // price or stall count that silently went stale.
        for (JSONObject bookmark : bookmarks) {
            String id = bookmark.optString("id");
            for (int i = 0; i < favoriteSites.size(); i++) {
                if (id.equals(favoriteSites.get(i).optString("id"))) {
                    favoriteSites.set(i, bookmark);
                    break;
                }
            }
        }
        if (currentTab == FAVORITES && !detailVisible) renderFavorites(false);
        // Site fetches run in parallel, up to the favorites limit, and the
        // screen redraws once every bookmark has reported.
        dispatchFavoriteRefreshes(bookmarks, generation, focusContent);
    }

    private void renderDiscover() {
        renderDiscover(false);
    }

    /**
     * @param research re-run the last search rather than redrawing the results
     *                 already in hand. Stall counts and prices go stale within
     *                 minutes, so a refresh that only repainted them would be
     *                 telling the same story twice.
     */
    private void renderDiscover(boolean research) {
        LinearLayout root = vertical();
        // Searching is the whole point of this tab, so Find is the filled
        // action and lands focus by default.
        final Button find = primaryAction("Find");
        root.addView(find);
        final TextView status = small(searchStatus());
        // Ellipsized, so a cut-off message shows it was cut rather than
        // stopping at the last whole word that fits.
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(status);
        root.addView(divider());
        final SiteAdapter adapter = new SiteAdapter(this);
        adapter.replace(searchResults);
        final ListView list = listView(adapter);
        root.addView(list, Ui.weighted());
        discoverStatus = status;
        discoverAdapter = adapter;
        discoverList = list;
        find.setOnClickListener(view -> showSearchDialog());
        find.setOnKeyListener(
            (view, keyCode, event) -> {
                if (
                    keyCode != KeyEvent.KEYCODE_DPAD_DOWN ||
                    event.getAction() != KeyEvent.ACTION_DOWN
                ) return false;
                if (adapter.getCount() == 0) return false;
                list.requestFocus();
                adapter.setSelectedPosition(0);
                list.setSelection(0);
                return true;
            }
        );
        wireSiteList(list, adapter, find);
        Ui.fill(content, root);
        find.requestFocus();
        if (research && lastSearchQuery.length() > 0) search(lastSearchQuery);
    }

    private String searchStatus() {
        if (searching) return "Searching for chargers…";
        if (searchError != null) return searchError;
        return searchResults.isEmpty()
            ? "Within " + displayRadius()
            : resultSummary();
    }

    private boolean discoverVisible() {
        return currentTab == DISCOVER && !detailVisible && discoverStatus != null;
    }

    private void showSearchDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Find Chargers")
            .setPositiveButton("Find", null)
            .setNegativeButton("Cancel", null);
        final Context themed = builder.getContext();

        final EditText input = new EditText(themed);
        input.setSingleLine(true);
        input.setText(lastSearchQuery);
        // The XP3 has no select-all key: with the cursor at the start, Clear
        // had nothing to delete and new text landed in front of the old.
        input.setSelection(input.length());
        input.setHint(R.string.search_hint);
        input.setInputType(
            InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
        );
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setSelectAllOnFocus(false);

        // AlertDialog gives a setView() child no insets of its own, so a bare
        // EditText sits flush to the dialog edges and hard up under the message.
        // Frame it at the dialog's own text inset, less whatever padding the
        // field's background already carries, so the text inside the box lines
        // up with the title and message above it. The inset has to come from
        // the theme rather than the 24dp the platform uses at larger sizes:
        // this screen gets 16dp, and hardcoding 24 pushed the field's text a
        // visible step right of everything above it.
        FrameLayout frame = new FrameLayout(themed);
        int side = Math.max(0, dialogTextInset(themed) - input.getPaddingLeft());
        frame.setPadding(side, dp(6), side, dp(10));
        frame.addView(
            input,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        final AlertDialog dialog = builder.setView(frame).create();
        dialog.setOnShowListener(
            ignored -> {
                final Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                submit.setOnClickListener(
                    view -> {
                        String query = input.getText().toString().trim();
                        if (query.length() == 0) {
                            input.setError("Enter a ZIP code or address.");
                            return;
                        }
                        lastSearchQuery = query;
                        dismissKeyboard();
                        dialog.dismiss();
                        search(query);
                    }
                );
                input.setOnEditorActionListener((v, actionId, event) -> {
                    if (
                        actionId == EditorInfo.IME_ACTION_SEARCH ||
                        (event != null &&
                            event.getKeyCode() == KeyEvent.KEYCODE_ENTER)
                    ) {
                        submit.performClick();
                        return true;
                    }
                    return false;
                });
                input.requestFocus();
            }
        );
        dialog.show();
    }

    private void search(final String query) {
        if (query.trim().length() == 0) {
            toast("Enter a ZIP code or address.");
            return;
        }
        final int generation = ++searchRequestGeneration;
        searching = true;
        searchError = null;
        if (discoverVisible()) discoverStatus.setText(searchStatus());
        async(
            () -> repository.search(query, radiusMiles),
            result -> {
                if (generation != searchRequestGeneration) return;
                searching = false;
                searchOrigin = result.optJSONObject("origin");
                searchResults.clear();
                JSONArray sites = result.optJSONArray("sites");
                if (sites != null) for (
                    int i = 0;
                    i < sites.length();
                    i++
                ) if (sites.optJSONObject(i) != null) searchResults.add(sites.optJSONObject(i));
                if (searchResults.isEmpty()) searchError = "No chargers found";
                if (!discoverVisible()) return;
                final SiteAdapter adapter = discoverAdapter;
                final ListView list = discoverList;
                adapter.replace(searchResults);
                discoverStatus.setText(searchStatus());
                if (!searchResults.isEmpty()) {
                    // A hardware-key-only device needs a concrete current row,
                    // not merely a scroll position. Move focus off Find and
                    // select the first result immediately.
                    list.requestFocus();
                    adapter.setSelectedPosition(0);
                    list.setSelection(0);
                }
            },
            error -> {
                if (generation != searchRequestGeneration) return;
                searching = false;
                searchError = error.getMessage();
                if (discoverVisible()) discoverStatus.setText(searchStatus());
            }
        );
    }

    private void wireSiteList(ListView list, final SiteAdapter adapter, final View aboveList) {
        wireList(list, adapter, aboveList, position -> openSite(adapter.getItem(position)), position -> {});
    }

    private void openSite(final JSONObject listItem) {
        openSite(listItem.optString("id"));
    }

    private void openSite(final String locationGuid) {
        detailVisible = true;
        currentHistorySession = null;
        currentSite = null;
        siteGuid = locationGuid;
        pendingSiteGuid = locationGuid;
        final int generation = ++pendingSiteGeneration;
        // A bare spinner reads better than a placeholder title that is about to
        // be replaced by the real Supercharger name.
        Ui.fill(content, spinner());
        async(
            () -> repository.site(locationGuid),
            site -> {
                // Backed out, or opened something else, while this was in
                // flight: the screen it was asked for is gone, and drawing
                // it now would cover whatever took its place with a detail
                // that Back no longer knows how to leave.
                if (
                    generation != pendingSiteGeneration ||
                    !locationGuid.equals(pendingSiteGuid)
                ) return;
                pendingSiteGuid = null;
                currentSite = site;
                renderSite();
            },
            error -> {
                if (
                    generation != pendingSiteGeneration ||
                    !locationGuid.equals(pendingSiteGuid)
                ) return;
                pendingSiteGuid = null;
                showErrorDialog(error);
            }
        );
    }

    private void renderSite() {
        if (currentSite == null) return;
        ScrollView scroll = new ScrollView(this);
        final LinearLayout root = vertical();
        // Same order as the list rows: street address as the title, city line
        // beneath it. Tesla's site name is a restatement of both.
        String street = Normalizer.addressStreet(currentSite);
        root.addView(
            heading(street.length() > 0 ? street : currentSite.optString("name", "Charger"))
        );
        root.addView(body(Normalizer.addressLocality(currentSite)));
        root.addView(divider());
        if (chargingOperation == CHARGING_STARTING) {
            root.addView(status("Starting charging…", color(R.color.warning)));
        } else if (chargingOperation == CHARGING_STOPPING) {
            root.addView(status("Stopping current session…", color(R.color.warning)));
        }

        // Facts become label/value rows instead of run-on sentences: on a
        // 240px screen a scannable right-hand column beats prose every time.
        JSONObject schedule = currentSite.optJSONObject("schedule");
        JSONObject rate = currentSite.optJSONObject("rate");
        String price = "Unavailable";
        if (schedule != null && !schedule.isNull("currentPrice")) price =
            SiteAdapter.pricePerUnit(
                schedule.optDouble("currentPrice"),
                schedule.optString("currency", "USD"),
                schedule.optString("uom", "kWh")
            );
        else if (rate != null && !rate.isNull("price")) price =
            SiteAdapter.pricePerUnit(
                rate.optDouble("price"),
                rate.optString("currency", "USD"),
                rate.optString("uom", "kWh")
            );
        root.addView(metric("Price", price));
        root.addView(
            metric(
                "Stalls",
                currentSite.isNull("availableStalls")
                    ? currentSite.optInt("totalStalls") + " total"
                    : currentSite.optInt("availableStalls") +
                          " of " +
                          currentSite.optInt("totalStalls") +
                          " open"
            )
        );
        if (currentSite.optInt("maxPowerKw") > 0) root.addView(
            metric("Max power", currentSite.optInt("maxPowerKw") + " kW")
        );

        if (!currentSite.isNull("outage")) root.addView(warning(currentSite.optString("outage")));
        if (!currentSite.isNull("criticalInfo")) root.addView(
            warning(currentSite.optString("criticalInfo"))
        );
        if (currentSite.optBoolean("payToPark")) root.addView(
            warning("Paid parking may apply. " + currentSite.optString("payToParkInstructions"))
        );
        if (!currentSite.isNull("accessInstruction")) {
            root.addView(section("Access"));
            root.addView(small(currentSite.optString("accessInstruction")));
        }

        addPricingSchedule(root, schedule);

        JSONArray extras = currentSite.optJSONArray("extraRates");
        if (extras != null && extras.length() > 0) {
            root.addView(section("Other fees"));
            for (int i = 0; i < extras.length(); i++) {
                JSONObject fee = extras.optJSONObject(i);
                root.addView(
                    metric(
                        fee.optString("label"),
                        fee.optString("uom").length() > 0
                            ? SiteAdapter.pricePerUnit(
                                  fee.optDouble("price"),
                                  fee.optString("currency", "USD"),
                                  fee.optString("uom")
                              )
                            : SiteAdapter.money(
                                  fee.optDouble("price"),
                                  fee.optString("currency", "USD")
                              )
                    )
                );
            }
        }
        JSONArray amenities = currentSite.optJSONArray("amenities");
        if (amenities != null && amenities.length() > 0) {
            root.addView(section("Amenities"));
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < amenities.length(); i++) {
                if (names.length() > 0) names.append(" · ");
                names.append(amenities.optString(i));
            }
            root.addView(small(names.toString()));
        }

        Button favorite = action(
            favorites.contains(currentSite.optString("id"))
                ? "Remove favorite"
                : "Add favorite"
        );
        favorite.setOnClickListener(
            view -> {
                String id = currentSite.optString("id");
                favorites.toggle(currentSite);
                for (int i = favoriteSites.size() - 1; i >= 0; i--) {
                    if (id.equals(favoriteSites.get(i).optString("id"))) favoriteSites.remove(i);
                }
                if (favorites.contains(id)) favoriteSites.add(currentSite);
                favoritesRefreshFailures = 0;
                renderSite();
                toast(favorites.contains(id) ? "Added to Favorites" : "Removed from Favorites");
            }
        );
        root.addView(favorite);
        JSONArray chargers = currentSite.optJSONArray("chargers");
        // A second session cannot be started while one is running, so the
        // button says why it is dead rather than letting the press through to a
        // stall picker that would fail at the Tesla end.
        boolean busy = sessionActive() || chargingOperation != CHARGING_IDLE;
        boolean canStart =
            !busy &&
            chargers != null &&
            currentSite.optInt("availableChargers") > 0 &&
            !currentSite.isNull("pricebookId");
        Button start = primaryAction(
            chargingOperation == CHARGING_STARTING
                ? "Starting…"
                : chargingOperation == CHARGING_STOPPING
                  ? "Stopping current session…"
                : busy
                ? "Session already in progress"
                : canStart
                  ? "Start charging"
                  : "No available stalls"
        );
        start.setEnabled(canStart);
        start.setOnClickListener(view -> chooseStall());
        root.addView(start);
        scroll.addView(root);
        Ui.fill(content, scroll);
        // Open at the name, not at the buttons. Focusing a button inside a
        // ScrollView scrolls it into view, which lands you at the bottom of the
        // screen having never seen the price. Park focus on the scroller so the
        // first press walks down through the content instead.
        scroll.setFocusableInTouchMode(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.requestFocus();
    }

    private void addPricingSchedule(LinearLayout root, JSONObject schedule) {
        if (schedule == null) return;
        JSONArray hours = schedule.optJSONArray("hours");
        if (hours == null || hours.length() == 0) return;

        String currency = schedule.optString("currency", "USD");
        String unit = SiteAdapter.unit(schedule.optString("uom", "kwh"));
        // Each entry is {hour range, price} so the two halves can be laid out in
        // the same label/value columns as the rest of the screen.
        List<String[]> bands = new ArrayList<>();
        int start = 0;
        while (start < hours.length()) {
            JSONObject first = hours.optJSONObject(start);
            if (first == null || first.isNull("price")) {
                start++;
                continue;
            }
            double price = first.optDouble("price");
            int end = start + 1;
            while (end < hours.length()) {
                JSONObject next = hours.optJSONObject(end);
                if (
                    next == null ||
                    next.isNull("price") ||
                    Double.compare(next.optDouble("price"), price) != 0
                ) break;
                end++;
            }
            bands.add(new String[] {
                hourRange(start, end),
                SiteAdapter.money(price, currency) + "/" + unit,
            });
            start = end;
        }
        if (bands.isEmpty()) return;

        root.addView(section("Pricing (site local time)"));
        for (String[] band : bands) root.addView(metric(band[0], band[1]));
    }

    private static String hourRange(int startHour, int endHour) {
        int inclusiveEndMinutes = endHour * 60 - 1;
        return String.format(
            Locale.US,
            "%02d:00–%02d:%02d",
            startHour,
            inclusiveEndMinutes / 60,
            inclusiveEndMinutes % 60
        );
    }

    private void chooseStall() {
        // Backstop for the disabled Start button: the active session may have
        // arrived from a poll between the render and the press.
        if (chargingOperation != CHARGING_IDLE) {
            toast("A charging request is already in progress.");
            return;
        }
        if (sessionActive()) {
            renderSite();
            toast("A charging session is already in progress.");
            return;
        }
        final JSONArray chargers = currentSite.optJSONArray("chargers");
        final List<JSONObject> available = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (int i = 0; chargers != null && i < chargers.length(); i++) {
            JSONObject charger = chargers.optJSONObject(i);
            if (charger != null && charger.optBoolean("available")) {
                available.add(charger);
                labels.add(
                    charger.optString("label") +
                        (charger.optInt("powerKw") > 0
                            ? "  ·  " + charger.optInt("powerKw") + " kW"
                            : "")
                );
            }
        }
        // The card is wanted by the confirm dialog one press from here, which is
        // not long enough to fetch it in. Asking now usually means it is already
        // in hand when that dialog opens.
        loadPaymentMethod(null);
        new AlertDialog.Builder(this).setTitle("Choose your stall").setItems(
                labels.toArray(new String[0]),
                (dialog, which) -> confirmStart(available.get(which))
            ).setNegativeButton("Cancel", null).show();
    }

    private void confirmStart(final JSONObject charger) {
        if (chargingOperation != CHARGING_IDLE) {
            toast("A charging request is already in progress.");
            return;
        }
        // The title says what is about to happen and the button says it again,
        // so the message says neither: it names the stall being committed to and
        // the card that will pay for it, which is what is worth a second look.
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Start charging session?")
            .setMessage(confirmMessage(charger)).setPositiveButton(
                "Start",
                (d, which) -> {
                    startSession(charger.optString("id"));
                }
            ).setNegativeButton("Cancel", null).show();
        // A card that arrives while this is on screen still belongs on it: the
        // press it is meant to inform has not happened yet.
        if (paymentMethod == null) loadPaymentMethod(
            () -> {
                if (dialog.isShowing()) dialog.setMessage(confirmMessage(charger));
            }
        );
    }

    private String confirmMessage(JSONObject charger) {
        StringBuilder message = new StringBuilder("» Stall ").append(charger.optString("label"));
        if (paymentMethod != null) message.append("\n» Billed to ").append(paymentMethod);

        JSONObject hold = currentSite.optJSONObject("holdAmount");
        if (hold != null) {
            String amount = SiteAdapter.money(
                hold.optDouble("price"),
                hold.optString("currency", "USD")
            );
            message.append("\n» ").append(amount).append(" pre-authorization requirement");
        }
        return message.toString();
    }

    /**
     * Reads the card on file once per launch and keeps it: it is the same card
     * for every session, and a dialog cannot wait on a request.
     *
     * @param onLoaded run on the main thread if a card is found, whichever call
     *                 started the request that found it.
     */
    private void loadPaymentMethod(Runnable onLoaded) {
        paymentMethodArrival = onLoaded;
        if (paymentMethod != null || paymentMethodLoading) return;
        paymentMethodLoading = true;
        async(
            () -> repository.paymentMethod(),
            label -> {
                paymentMethodLoading = false;
                paymentMethod = label;
                Runnable arrival = paymentMethodArrival;
                paymentMethodArrival = null;
                if (label != null && arrival != null) arrival.run();
            },
            error -> {
                // Not knowing the card is no reason to interrupt: charging
                // starts the same way, and the line simply stays off.
                paymentMethodLoading = false;
                paymentMethodArrival = null;
            }
        );
    }

    private void startSession(final String chargerId) {
        if (chargingOperation != CHARGING_IDLE) {
            toast("A charging request is already in progress.");
            return;
        }
        final String siteId = currentSite == null
            ? ""
            : currentSite.optString("id");
        if (siteId.length() == 0 || chargerId.length() == 0) {
            showErrorDialog(new ApiException("The selected stall is no longer available.", 400));
            return;
        }
        chargingOperation = CHARGING_STARTING;
        final int generation = ++chargingOperationGeneration;
        if (detailVisible && currentHistorySession == null) renderSite();
        async(
            () -> repository.start(siteId, chargerId),
            session -> {
                if (generation != chargingOperationGeneration) return;
                chargingOperation = CHARGING_IDLE;
                currentSession = session;
                sessionError = null;
                sessionLookupError = null;
                showTab(SESSION, true);
                watchOrRelease();
            },
            error -> {
                if (generation != chargingOperationGeneration) return;
                chargingOperation = CHARGING_IDLE;
                if (
                    detailVisible &&
                    currentHistorySession == null &&
                    currentSite != null &&
                    siteId.equals(currentSite.optString("id"))
                ) renderSite();
                showErrorDialog(error);
            }
        );
    }

    private void renderSession() {
        if (currentSession == null) {
            // No ScrollView here: the empty state wants the weighted centring
            // that a scrolling parent's unbounded height would collapse.
            LinearLayout empty = vertical();
            empty.addView(
                emptyMessage(
                    sessionLookupError == null
                        ? "No active charging session"
                        : "Could not check for a charging session.\n\n" + sessionLookupError
                ),
                Ui.weighted()
            );
            Button refresh = action("Check again");
            refresh.setOnClickListener(v -> resumeActiveSession());
            empty.addView(refresh);
            Ui.fill(content, empty);
            refresh.requestFocus();
            return;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = vertical();

        // Status banner: the one thing worth reading from arm's length.
        LinearLayout banner = vertical();
        banner.setBackgroundColor(color(R.color.row));
        banner.setPadding(dp(10), dp(0), dp(10), dp(8));
        // Green only when it really is charging; a green "Stopping" would lie.
        String phase = currentSession.optString("phase");
        // Once Tesla has acted on the stop, the session's own state is the
        // truth and the request that asked for it has nothing left to say. It
        // is still in flight until its second round trip returns, so treating
        // "asked to stop" as the headline outranked "Unplug to finish" for the
        // whole of the wait, which is precisely when that instruction is the
        // only thing on the screen worth reading.
        boolean sessionReflectsStop =
            "stopping".equals(phase) ||
            currentSession.optBoolean("awaitingUnplug") ||
            !currentSession.optBoolean("active");
        boolean stopPending =
            chargingOperation == CHARGING_STOPPING && !sessionReflectsStop;
        int statusColor = stopPending
            ? color(R.color.warning)
            : "charging".equals(phase)
            ? color(R.color.success)
            : currentSession.optBoolean("awaitingUnplug")
              ? color(R.color.warning)
              : color(R.color.text);
        banner.addView(
            status(
                stopPending
                    ? "Stopping…"
                    : currentSession.optString("statusText", "Session"),
                statusColor
            )
        );

        // Same address-first order as the lists. The city line and the stall
        // share a row so the banner stays three lines tall.
        String street = Normalizer.addressStreet(currentSession);
        if (street.length() == 0) street = currentSession.optString("siteName", "");
        if (street.length() > 0) banner.addView(centeredText(street, 13, color(R.color.text)));
        String locality = Normalizer.addressLocality(currentSession);
        String stall = currentSession.isNull("stallLabel")
            ? ""
            : "Stall " + currentSession.optString("stallLabel");
        String beneath =
            locality.length() > 0 && stall.length() > 0
                ? locality + " · " + stall
                : locality + stall;
        if (beneath.length() > 0) banner.addView(
            centeredText(beneath, 12, color(R.color.text_muted))
        );
        root.addView(banner);
        root.addView(divider());
        // Said before the numbers, because it is about them: a reading that did
        // not arrive leaves the last one on screen, and power and cost that have
        // quietly stopped advancing look exactly like a charge that has stalled.
        if (sessionError != null) {
            root.addView(warning("Lost contact with Tesla"));
        }
        if (
            "charging".equals(phase) ||
            "stopping".equals(phase) ||
            currentSession.optBoolean("hasEnergyReading")
        ) {
            root.addView(
                metric(
                    "Power",
                    String.format(Locale.US, "%.1f kW", currentSession.optDouble("powerKw"))
                )
            );
            root.addView(
                metric(
                    "Energy",
                    String.format(Locale.US, "%.2f kWh", currentSession.optDouble("energyKwh"))
                )
            );
            root.addView(
                metric(
                    "Cost",
                    SiteAdapter.money(
                        currentSession.optDouble("cost"),
                        currentSession.optString("currency", "USD")
                    )
                )
            );
            // Charge level and time remaining share a row. They answer one
            // question, and separating them is what pushed Stop charging off
            // the bottom of a 320px screen.
            boolean hasEta =
                !currentSession.isNull("timeToBulkSecs") &&
                currentSession.optDouble("powerKw") > 0;
            String eta = hasEta
                ? HistoryAdapter.duration(currentSession.optLong("timeToBulkSecs")) + " to 80%"
                : "";
            if (!currentSession.isNull("stateOfCharge")) {
                String level = currentSession.optInt("stateOfCharge") + "%";
                root.addView(metric("Battery", hasEta ? level + " · " + eta : level));
            } else if (hasEta) {
                root.addView(metric("Battery", eta));
            }
        } else {
            JSONObject rate = currentSession.optJSONObject("rate");
            if (rate != null && !rate.isNull("currentPrice")) root.addView(
                metric(
                    "Rate",
                    SiteAdapter.pricePerUnit(
                        rate.optDouble("currentPrice"),
                        rate.optString("currency", "USD"),
                        rate.optString("uom", "kWh")
                    )
                )
            );
        }
        // Tesla only itemizes congestion once the completed-session summary is
        // ready. Keep a zero-height slot beside the other figures so an actual
        // congestion charge can appear without adding duplicate energy or an
        // unconditional total row.
        if (currentSession.optBoolean("summaryReady")) {
            LinearLayout congestionFees = vertical();
            root.addView(congestionFees);
            loadCongestionFees(congestionFees);
        }
        if (currentSession.optBoolean("awaitingUnplug")) root.addView(
            warning("Charging has stopped")
        );
        // Same rule as the banner: a session that has already moved past the
        // stop offers nothing to press, so it keeps no button. Waiting for an
        // unplug is not an action anyone can take here.
        if (currentSession.optBoolean("cancellable") || stopPending) {
            Button stop = primaryAction(
                stopPending
                    ? "Stopping…"
                    : currentSession.optBoolean("charging")
                    ? "Stop charging"
                    : "Cancel session"
            );
            stop.setEnabled(chargingOperation == CHARGING_IDLE);
            stop.setOnClickListener(v -> confirmStop());
            root.addView(stop);
        }
        scroll.addView(root);
        Ui.fill(content, scroll);
    }

    private void renderHistory(boolean focusContent) {
        if (!historyLoaded) {
            loadHistory();
            return;
        }

        LinearLayout root = vertical();
        if (historySessions.isEmpty()) {
            root.addView(
                emptyMessage("No charging sessions yet"),
                Ui.weighted()
            );
            Ui.fill(content, root);
            return;
        }
        final HistoryAdapter adapter = new HistoryAdapter(this);
        adapter.replace(historySessions);
        historyAdapter = adapter;
        updateHistoryFooter();
        ListView list = listView(adapter);
        root.addView(list, Ui.weighted());
        wireHistoryList(list, adapter);
        Ui.fill(content, root);
        if (focusContent) list.requestFocus();
    }

    private void loadHistory() {
        Ui.fill(content, spinner());
        final int generation = ++historyLoadGeneration;
        async(
            () -> repository.history(1),
            page -> {
                if (generation != historyLoadGeneration) return;
                historySessions.clear();
                historySessions.addAll(page.sessions);
                historyPage = 1;
                historyHasMore = page.hasMore;
                historyLoadingMore = false;
                historyMoreError = null;
                historyLoaded = true;
                if (currentTab == HISTORY) renderHistory(true);
            },
            error -> {
                if (generation != historyLoadGeneration) return;
                if (currentTab != HISTORY) return;
                LinearLayout root = vertical();
                root.addView(
                    emptyMessage("Could not load your charging history.\n\n" + error.getMessage()),
                    Ui.weighted()
                );
                Button retry = action("Try again");
                retry.setOnClickListener(v -> loadHistory());
                root.addView(retry);
                Ui.fill(content, root);
                retry.requestFocus();
            }
        );
    }

    /**
     * The next page of history, appended under what is already on screen.
     * Paging is never allowed to disturb the list it is growing: the rows and
     * the selection stay exactly where they were, and a page that does not
     * arrive turns the footer into a retry rather than replacing the sessions
     * already loaded with an error screen.
     */
    private void loadMoreHistory() {
        if (!historyLoaded || historyLoadingMore || !historyHasMore) return;
        historyLoadingMore = true;
        historyMoreError = null;
        updateHistoryFooter();
        final int next = historyPage + 1;
        async(
            () -> repository.history(next),
            page -> {
                historyLoadingMore = false;
                historyPage = next;
                historyHasMore = page.hasMore;
                historySessions.addAll(page.sessions);
                // Appended rather than re-rendered, for the same reason the
                // address pass redraws in place: rebuilding the list would
                // drop the selection out from under whoever is on it.
                if (historyAdapter != null) historyAdapter.append(page.sessions);
                updateHistoryFooter();
            },
            error -> {
                historyLoadingMore = false;
                historyMoreError = error.getMessage();
                updateHistoryFooter();
            }
        );
    }

    /** The footer reports what paging is doing, and disappears entirely once
     *  the history has been read to its end. */
    private void updateHistoryFooter() {
        if (historyAdapter == null) return;
        if (historyLoadingMore) historyAdapter.setFooter("Loading more…", true);
        else if (historyMoreError != null) historyAdapter.setFooter(
            historyMoreError,
            false
        );
        else if (historyHasMore) historyAdapter.setFooter("Load older sessions", false);
        else historyAdapter.setFooter(null, false);
    }

    /**
     * Asks for the next page once the row being looked at is near the end of
     * the loaded ones, so the page is usually already in flight by the time the
     * footer comes into view and the list simply grows.
     */
    private void maybeLoadMoreHistory(int position) {
        if (position >= historySessions.size() - HISTORY_PREFETCH_ROWS) {
            loadMoreHistory();
        }
    }

    /** OK on the footer, which is the same request whether it is the first
     *  attempt at a page or a retry of one that failed. */
    private void openHistoryFooter() {
        historyMoreError = null;
        loadMoreHistory();
    }

    private void wireHistoryList(ListView list, final HistoryAdapter adapter) {
        wireList(list, adapter, null, position -> {
            if (adapter.isFooter(position)) openHistoryFooter();
            else openHistorySession(adapter.getItem(position));
        }, this::maybeLoadMoreHistory);
        // Touch scrolling reaching the end asks for the next page too: the key
        // handler is the only path on a keypad, but it is not the only way
        // this list moves.
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {}

            @Override
            public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                if (visibleCount > 0) maybeLoadMoreHistory(firstVisible + visibleCount - 1);
            }
        });
    }

    /**
     * A list of rows that paint their own highlight. Focus lands on the first
     * row; up and down move one row at a time (see moveListSelection), and up
     * from the top row goes to `aboveList` when there is one; OK opens the
     * row. `moved` hears every new selection.
     *
     * Sonim firmware variants report the physical OK key as either
     * DPAD_CENTER or ENTER. Both are handled explicitly: a focusable child row
     * can otherwise consume OK while the ListView still scrolls normally.
     */
    private void wireList(
        final ListView list, final RowAdapter adapter, final View aboveList,
        final Ui.Selected open, final Ui.Selected moved
    ) {
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnItemClickListener((parent, view, position, id) -> open.at(position));
        list.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus && adapter.getCount() > 0
                && adapter.getSelectedPosition() == AdapterView.INVALID_POSITION) {
                adapter.setSelectedPosition(0);
                list.setSelection(0);
            }
        });
        Ui.onSelect(list, position -> {
            adapter.setSelectedPosition(position);
            moved.at(position);
        });
        list.setOnKeyListener((view, keyCode, event) -> {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && down && aboveList != null
                && adapter.getSelectedPosition() <= 0) {
                aboveList.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (down && adapter.getCount() > 0) {
                    moveListSelection(list, adapter, keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    moved.at(adapter.getSelectedPosition());
                }
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_UP || !Ui.isOk(keyCode)) return false;
            int position = adapter.getSelectedPosition();
            if (position < 0 || position >= adapter.getCount()) return false;
            open.at(position);
            return true;
        });
    }

    private void moveListSelection(ListView list, RowAdapter adapter, int direction) {
        int current = adapter.getSelectedPosition();
        int target =
            current == AdapterView.INVALID_POSITION
                ? 0
                : Math.max(0, Math.min(adapter.getCount() - 1, current + direction));
        if (target == current) return;

        int firstVisible = list.getFirstVisiblePosition();
        View targetRow = list.getChildAt(target - firstVisible);
        boolean targetFullyVisible =
            targetRow != null &&
            targetRow.getTop() >= list.getPaddingTop() &&
            targetRow.getBottom() <= list.getHeight() - list.getPaddingBottom();

        // Move the highlight through rows already on screen without moving the
        // viewport. At an edge, keep the highlight in that slot and move the
        // rows underneath it one entry at a time.
        int anchorTop;
        if (targetFullyVisible) {
            anchorTop = targetRow.getTop();
        } else {
            View currentRow = list.getChildAt(current - firstVisible);
            anchorTop =
                currentRow == null ? list.getPaddingTop() : currentRow.getTop();
        }

        adapter.setSelectedPosition(target);
        list.setSelectionFromTop(target, anchorTop);
    }

    /**
     * Opens the receipt for one past session. Everything on it came down with
     * the history page except the charger behind it, which is looked up here
     * rather than while the list was loading: the list shows neither the stall
     * label nor the open button, so a page had no business paying for them.
     *
     * The lookup is once per site per run, so only the first session opened at
     * a given charger waits. It waits behind a spinner rather than drawing a
     * receipt that grows a row a second later.
     */
    private void openHistorySession(final JSONObject session) {
        detailVisible = true;
        currentHistorySession = session;
        pendingSiteGuid = null;
        pendingSiteGeneration++;
        if (repository.knowsHistorySite(session)) {
            repository.historySite(session);
            renderHistorySession();
            return;
        }
        Ui.fill(content, spinner());
        async(
            () -> {
                repository.historySite(session);
                return session;
            },
            resolved -> {
                // Backed out while it was in flight: whatever is on screen
                // now is not this session's receipt.
                if (currentHistorySession == resolved) renderHistorySession();
            },
            error -> {
                // The receipt is complete without the charger behind it, so
                // a failed lookup still gets a screen - just one with no
                // stall label and no open button.
                if (currentHistorySession == session) renderHistorySession();
            }
        );
    }

    /**
     * The receipt for one past session, on the same screen shape a charger
     * detail uses. Everything the list row had to compress, including both
     * ends of the session, the city, and the fee-by-fee arithmetic, is spelled
     * out here.
     */
    private void renderHistorySession() {
        final JSONObject session = currentHistorySession;
        if (session == null) return;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = vertical();

        String street = Normalizer.addressStreet(session);
        String locality = Normalizer.addressLocality(session);
        String label = session.optString("siteName", "Charging session");
        root.addView(heading(street.length() > 0 ? street : label));
        // Under a resolved street: the city, or failing that the name the
        // session was filed under. Under an unresolved one: nothing, since the
        // heading is already that name.
        String secondary =
            locality.length() > 0 ? locality : street.length() > 0 ? label : "";
        if (secondary.length() > 0) root.addView(body(secondary));
        root.addView(divider());

        String currency = session.optString("currency", "USD");
        if (!session.isNull("startedAt")) root.addView(
            metric("Started", HistoryAdapter.at(session.optLong("startedAt")))
        );
        if (!session.isNull("endedAt")) root.addView(
            metric("Ended", HistoryAdapter.at(session.optLong("endedAt")))
        );
        if (!session.isNull("durationSecs")) root.addView(
            metric("Charged for", HistoryAdapter.duration(session.optLong("durationSecs")))
        );
        // Only worth a row once it is long enough to be a decision someone
        // made rather than the seconds it takes to put a cable away.
        if (!session.isNull("idleSecs") && session.optLong("idleSecs") >= 300) {
            root.addView(
                metric("Plugged in after", HistoryAdapter.duration(session.optLong("idleSecs")))
            );
        }
        root.addView(
            metric(
                "Energy",
                String.format(Locale.US, "%.2f kWh", session.optDouble("energyKwh", 0))
            )
        );
        if (!session.isNull("pricePerKwh")) {
            root.addView(
                metric(
                    "Cost per kWh",
                    SiteAdapter.money(session.optDouble("pricePerKwh"), currency) + "/kWh"
                )
            );
        }

        addSessionFees(root, session, currency);

        root.addView(section("Total"));
        root.addView(
            metric(
                session.optBoolean("paid") ? "Total paid" : "Payment pending",
                SiteAdapter.money(session.optDouble("total", 0), currency)
            )
        );

        // Only offered once the place behind the label is actually known: this
        // is the same charger's live screen, prices, stalls and all.
        final String locationGuid = session.optString("locationGUID", "");
        if (locationGuid.length() > 0) {
            Button charger = action("Open this charger");
            charger.setOnClickListener(view -> openSite(locationGuid));
            root.addView(charger);
        }
        scroll.addView(root);
        Ui.fill(content, scroll);
        scroll.setFocusableInTouchMode(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.requestFocus();
    }

    /** Itemized totals for everything billed during the session. */
    private void addSessionFees(LinearLayout root, JSONObject session, String currency) {
        JSONArray fees = session.optJSONArray("fees");
        boolean titled = false;
        for (int i = 0; fees != null && i < fees.length(); i++) {
            JSONObject fee = fees.optJSONObject(i);
            if (fee == null) continue;
            double total = fee.optDouble("total", 0);
            if (total == 0 && !fee.optBoolean("waived")) continue;
            if (!titled) {
                root.addView(section("Charges"));
                titled = true;
            }
            root.addView(
                metric(
                    fee.optString("label"),
                    fee.optBoolean("waived") && total == 0
                        ? "Waived"
                        : SiteAdapter.money(total, fee.optString("currency", currency))
                )
            );
        }
    }

    private void loadCongestionFees(final LinearLayout root) {
        final String id = currentSession.optString("authorizationSessionId");
        async(
            () -> repository.sessionFees(id),
            summary -> {
                if (summary == null) return;
                JSONArray fees = summary.optJSONArray("fees");
                boolean hasCongestionFee = false;
                for (int i = 0; fees != null && i < fees.length(); i++) {
                    JSONObject fee = fees.optJSONObject(i);
                    if (fee == null || !"CONGESTION".equals(fee.optString("type"))) continue;
                    double total = fee.optDouble("total", 0);
                    if (total == 0 && !fee.optBoolean("waived")) continue;
                    hasCongestionFee = true;
                    root.addView(
                        metric(
                            "Congestion fee",
                            fee.optBoolean("waived") && total == 0
                                ? "Waived"
                                : SiteAdapter.money(
                                      total,
                                      fee.optString(
                                          "currency",
                                          summary.optString("currency", "USD")
                                      )
                                  )
                        )
                    );
                }
                if (hasCongestionFee) root.addView(
                    metric(
                        "Total",
                        SiteAdapter.money(
                            summary.optDouble("total"),
                            summary.optString("currency", "USD")
                        )
                    )
                );
            },
            error -> {
                // Congestion is optional supporting detail. The running
                // total above remains useful if its breakdown is late or
                // temporarily unavailable.
            }
        );
    }

    private void confirmStop() {
        if (chargingOperation != CHARGING_IDLE) {
            toast("A charging request is already in progress.");
            return;
        }
        final boolean charging = currentSession.optBoolean("charging");
        new AlertDialog.Builder(this).setTitle(charging ? "Stop charging?" : "Cancel session?")
            .setPositiveButton(
                charging ? "Stop" : "Cancel session",
                (dialog, which) -> stopSession()
            ).setNegativeButton("Keep session", null).show();
    }

    private void stopSession() {
        if (chargingOperation != CHARGING_IDLE) {
            toast("A charging request is already in progress.");
            return;
        }
        final String id = currentSession.optString("authorizationSessionId");
        if (id.length() == 0) {
            showErrorDialog(new ApiException("The active session could not be identified.", 400));
            return;
        }
        chargingOperation = CHARGING_STOPPING;
        final int generation = ++chargingOperationGeneration;
        if (currentTab == SESSION && !detailVisible) renderSession();
        async(
            () -> repository.stop(id),
            session -> {
                if (generation != chargingOperationGeneration) return;
                chargingOperation = CHARGING_IDLE;
                currentSession = session;
                sessionError = null;
                if (currentTab == SESSION && !detailVisible) renderSession();
                watchOrRelease();
            },
            error -> {
                if (generation != chargingOperationGeneration) return;
                chargingOperation = CHARGING_IDLE;
                if (currentTab == SESSION && !detailVisible) renderSession();
                showErrorDialog(error);
            }
        );
    }

    private void resumeActiveSession() {
        final int generation = ++activeSessionRequestGeneration;
        async(
            () -> repository.activeSession(),
            session -> {
                if (generation != activeSessionRequestGeneration) return;
                currentSession = session;
                sessionError = null;
                sessionLookupError = null;
                if (currentTab == SESSION) renderSession();
                else if (
                    detailVisible &&
                    currentHistorySession == null &&
                    currentSite != null
                ) renderSite();
                watchOrRelease();
            },
            error -> {
                if (generation != activeSessionRequestGeneration) return;
                if (currentSession == null) {
                    sessionLookupError = error.getMessage();
                    if (currentTab == SESSION && !detailVisible) renderSession();
                } else if (currentTab == SESSION) showErrorDialog(error);
            }
        );
    }

    /** True while a charging session is running, so nothing else offers to
     *  start a second one. */
    private boolean sessionActive() {
        return currentSession != null && currentSession.optBoolean("active");
    }

    /**
     * Hands the running session to {@link ChargingService}, which polls it and
     * holds the notification up for as long as it lasts. Safe to repeat: the
     * service ignores a start for the session it is already on.
     */
    private void startWatchingSession() {
        if (!sessionActive()) return;
        ChargingService.start(this, currentSession.optString("authorizationSessionId"));
    }

    /**
     * Starts or ends the watch after a reading that settles the question.
     *
     * Only for answers straight from Tesla. An absent session is the reason the
     * notification comes down, so "no session" has to mean the network said so,
     * not merely that this screen has not heard yet: a resume that took the
     * second reading for the first would tear down the notification for a charge
     * still running.
     */
    private void watchOrRelease() {
        if (sessionActive()) startWatchingSession();
        else ChargingService.stop(this);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // First press only. Auto-repeat from a held d-pad would cycle the tabs
        // as fast as the key fires, re-rendering each one and, on History,
        // starting a fresh page load every time round.
        int next = tabs.step(event, getCurrentFocus(), currentTab);
        if (next >= 0) {
            showTab(next, true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMenu();
            return true;
        }
        if (
            (keyCode == KeyEvent.KEYCODE_CLEAR ||
                keyCode == KeyEvent.KEYCODE_DEL) &&
            detailVisible &&
            !(getCurrentFocus() instanceof EditText)
        ) {
            showTab(currentTab, true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (detailVisible) showTab(currentTab, true);
        else super.onBackPressed();
    }

    private void showMenu() {
        final String[] items = {
            "Refresh this page",
            "Search radius: " + displayRadius(),
            "Account info",
        };
        new AlertDialog.Builder(this).setTitle("Menu").setItems(
                items,
                (dialog, which) -> {
                    if (which == 0) refreshCurrentScreen();
                    else if (which == 1) chooseRadius();
                    else showAccount();
                }
            ).show();
    }

    /** Reloads whatever is on screen, in place. */
    private void refreshCurrentScreen() {
        // A detail screen is refreshed on its own terms: dropping back to the
        // tab to reload it would throw away the screen being looked at.
        if (detailVisible && currentHistorySession != null) {
            openHistorySession(currentHistorySession);
            return;
        }
        if (detailVisible && siteGuid != null) {
            openSite(siteGuid);
            return;
        }
        if (currentTab == SESSION) resumeActiveSession();
        else if (currentTab == FAVORITES) refreshFavorites(true);
        else if (currentTab == HISTORY) {
            historyLoaded = false;
            renderHistory(true);
        } else renderDiscover(true);
    }

    /**
     * The card Tesla will bill, anything past sessions still owe, and the car
     * the network has on file, over whatever screen the menu was opened from
     * and never in place of it. This is the app talking about itself rather
     * than about a charger, and the tabs it floats over are where the app is.
     *
     * Re-read on every open rather than cached, because the reason to open it
     * is to find out what is true now: a card that has since expired or a
     * balance that has since been settled is exactly what a stale copy hides.
     */
    private void showAccount() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Account info")
            .setMessage("").setPositiveButton("OK", null);
        final Context themed = builder.getContext();
        final FrameLayout loading = new FrameLayout(themed);
        loading.setPadding(0, dp(4), 0, dp(14));
        ProgressBar progress = new ProgressBar(themed);
        FrameLayout.LayoutParams centred = new FrameLayout.LayoutParams(dp(32), dp(32));
        centred.gravity = Gravity.CENTER;
        loading.addView(progress, centred);

        final AlertDialog dialog = builder.setView(loading).create();
        dialog.show();

        // AlertDialog gives its message and custom-view panels equal weight on
        // older Android versions. Keep exactly one in the layout at a time so
        // the spinner remains, without either state claiming the spare height.
        View custom = dialog.findViewById(android.R.id.custom);
        final View loadingPanel = (View) custom.getParent();
        View message = dialog.findViewById(android.R.id.message);
        final View messagePanel = (View) message.getParent().getParent();
        messagePanel.setVisibility(View.GONE);
        final int generation = ++accountRequestGeneration;
        async(
            () -> repository.account(),
            value -> {
                if (generation != accountRequestGeneration) return;
                // The start-charging dialog wants the same card and cannot
                // wait on a request, so it takes this copy for free.
                JSONObject payment = value.optJSONObject("payment");
                if (payment != null && !payment.isNull("label")) {
                    paymentMethod = payment.optString("label");
                }
                if (!dialog.isShowing()) return;
                dialog.setMessage(accountMessage(value));
                loadingPanel.setVisibility(View.GONE);
                messagePanel.setVisibility(View.VISIBLE);
            },
            error -> {
                if (generation != accountRequestGeneration) return;
                if (!dialog.isShowing()) return;
                dialog.setMessage("Your account could not be loaded.\n\n" + error.getMessage());
                loadingPanel.setVisibility(View.GONE);
                messagePanel.setVisibility(View.VISIBLE);
            }
        );
    }

    /**
     * The account as a few short blocks. A dialog is read straight down rather
     * than scanned across label and value columns, so each fact is a line and
     * anything that would only have restated a neighbouring line is left out,
     * such as the card's instrument type and the channels it is accepted in.
     */
    private String accountMessage(JSONObject account) {
        StringBuilder text = new StringBuilder("-- Payment --\n» ");

        JSONObject payment = account.optJSONObject("payment");
        if (payment == null) {
            text.append(
                account.isNull("paymentError")
                    ? "No card on file"
                    : account.optString("paymentError")
            );
        } else {
            String brand = payment.isNull("brand")
                ? null
                : payment.optString("brand");
            String lastFour = payment.isNull("lastFour")
                ? null
                : payment.optString("lastFour");
            if (lastFour == null) text.append(brand == null ? "Card on file" : brand);
            else text.append(brand == null ? "Card" : brand).append(" ending in ").append(lastFour);
            if (!payment.isNull("expiryMonth")) {
                text.append(
                    String.format(
                        Locale.US,
                        payment.optBoolean("expired")
                            ? "\n» Expired %02d/%d"
                            : "\n» Expires %02d/%d",
                        payment.optInt("expiryMonth"),
                        payment.optInt("expiryYear")
                    )
                );
            }
        }

        JSONObject balances = account.optJSONObject("balances");
        text.append("\n\n-- Balance --\n» ");
        if (balances != null) {
            JSONArray entries = balances.optJSONArray("entries");
            if (entries == null || entries.length() == 0) {
                // Said as an amount so it lines up with the owed lines below
                // it. An empty list names no currency of its own, so the zero
                // is priced in the account's.
                text.append(
                    SiteAdapter.money(0, account.optString("currency", "USD"))
                ).append(" owed");
            }
            for (int i = 0; entries != null && i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                if (i > 0) text.append("\n» ");
                int sessions = entry.optInt("sessions");
                text.append(
                    SiteAdapter.money(entry.optDouble("amount"), entry.optString("currency", "USD"))
                ).append(" owed across ").append(sessions)
                    .append(sessions == 1 ? " session" : " sessions");
            }
        } else text.append(
            account.isNull("balancesError")
                ? "Balance unavailable"
                : account.optString("balancesError")
        );

        JSONObject vehicle = account.optJSONObject("vehicle");
        text.append("\n\n-- Vehicle --\n» ");
        if (vehicle != null) {
            boolean hasDetail = false;
            if (!vehicle.isNull("name")) {
                text.append(vehicle.optString("name"));
                hasDetail = true;
            }
            if (!vehicle.isNull("plate")) {
                if (hasDetail) text.append("\n» ");
                text.append("Plate ").append(vehicle.optString("plate"));
                hasDetail = true;
            }
            if (!vehicle.isNull("range")) {
                if (hasDetail) text.append("\n» ");
                text.append(vehicle.optString("range")).append(" range");
                hasDetail = true;
            }
            if (hasDetail) text.append("\n» ");
            // Decides which stalls a search offers at all, so it is said out
            // loud rather than left inside the filter that reads it.
            text.append(
                vehicle.optBoolean("hasNacsAdapter")
                    ? "NACS adapter registered"
                    : "No NACS adapter registered"
            );
        } else text.append(
            account.isNull("vehicleError")
                ? "No vehicle registered in the Tesla app"
                : account.optString("vehicleError")
        );

        if (!account.isNull("signInExpiresAt")) {
            text.append("\n\n-- Sign-in --\n» ").append(
                signInExpiry(account.optLong("signInExpiresAt"))
            );
        }

        return text.toString();
    }

    /**
     * How long the credentials baked into this build have left. Phrased as a
     * countdown rather than a date because the only thing to do about it is
     * rebuild, and what decides whether that is worth doing now is how many
     * days are left, not which day it lands on.
     */
    private static String signInExpiry(long expiresAt) {
        long remaining = expiresAt - System.currentTimeMillis();
        if (remaining <= 0) return "Expired";
        long days = remaining / 86_400_000L;
        if (days == 0) return "Expires today";
        if (days == 1) return "Expires tomorrow";
        if (days <= 14) return "Expires in " + days + " days";
        return "Valid for " + days + " more days";
    }

    private void chooseRadius() {
        final double[] values = { 5, 12, 30, 60 };
        String[] labels = { "5 miles", "12 miles", "30 miles", "60 miles" };
        int current = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == radiusMiles) current = i;
        new AlertDialog.Builder(this).setTitle("Search radius").setSingleChoiceItems(
                labels,
                current,
                (dialog, which) -> {
                    dialog.dismiss();
                    radiusMiles = values[which];
                    // Only the Discover list shows the radius; redrawing it
                    // over a charger detail would strand that detail.
                    if (discoverVisible()) renderDiscover();
                }
            ).show();
    }

    private String displayRadius() {
        return (int) radiusMiles + " miles";
    }

    /** Echoes back what you typed. The geocoder's own label expands "85027"
     *  into five lines of county and country that push the list off screen. */
    private String resultSummary() {
        String where =
            lastSearchQuery.length() > 0
                ? lastSearchQuery
                : searchOrigin == null
                  ? "your search"
                  : searchOrigin.optString("label", "your search");
        return searchResults.size() +
            (searchResults.size() == 1 ? " charger near " : " chargers near ") + where;
    }

    private <T> void async(
        final Callable<T> task,
        final Success<T> success,
        final Failure failure
    ) {
        network.submit(
            () -> {
                try {
                    final T result = task.call();
                    main.post(
                        () -> {
                            if (!isFinishing()) success.accept(result);
                        }
                    );
                } catch (final Exception error) {
                    main.post(
                        () -> {
                            if (!isFinishing()) failure.accept(error);
                        }
                    );
                }
            }
        );
    }

    private void showErrorDialog(Exception error) {
        new AlertDialog.Builder(this).setTitle("Could not complete request")
            .setMessage(error.getMessage()).setPositiveButton("OK", null).show();
    }

    /** A centred spinner filling the content area while a request is in flight. */
    private View spinner() {
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(color(R.color.page));
        ProgressBar progress = new ProgressBar(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(40), dp(40));
        params.gravity = Gravity.CENTER;
        host.addView(progress, params);
        return host;
    }

    private ListView listView(ListAdapter adapter) {
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        // Rows paint their own focus state edge to edge. A transparent
        // selector stops the platform highlight from drawing a second,
        // differently shaped indicator underneath them.
        list.setSelector(new ColorDrawable(0x00000000));
        list.setDivider(new ColorDrawable(color(R.color.divider)));
        list.setDividerHeight(dp(1));
        list.setBackgroundColor(color(R.color.page));
        list.setCacheColorHint(0x00000000);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        list.setItemsCanFocus(false);
        return list;
    }

    private void dismissKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    /** The single most useful action on a screen: filled, bold, unmistakable. */
    private Button primaryAction(String text) {
        return button(text, true);
    }

    /** Everything else: outlined, and focused with the same rule as list rows. */
    private Button action(String text) {
        return button(text, false);
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_secondary);
        button.setTextColor(
            primary
                ? getResources().getColorStateList(R.color.btn_primary_text)
                : ColorStateList.valueOf(color(R.color.text))
        );
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(42)
        );
        params.setMargins(dp(8), dp(5), dp(8), dp(3));
        button.setLayoutParams(params);
        return button;
    }

    /** Title of a detail screen, sitting on the row surface like a first row. */
    private TextView heading(String text) {
        TextView view = text(text, 16, color(R.color.text));
        view.setTypeface(null, Typeface.BOLD);
        view.setBackgroundColor(color(R.color.row));
        view.setPadding(dp(10), dp(9), dp(10), dp(3));
        return view;
    }

    /** Centred guidance for a screen that has nothing in it yet. */
    private TextView emptyMessage(String message) {
        TextView view = text(message, 13, color(R.color.text_muted));
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        return view;
    }

    /** Quiet all-caps label grouping the rows beneath it. Capitals carry the
     *  hierarchy here, so this does not also need to be bold. */
    private TextView section(String text) {
        TextView view = text(text.toUpperCase(Locale.US), 11, color(R.color.text_muted));
        view.setPadding(dp(10), dp(10), dp(10), dp(4));
        return view;
    }

    private TextView body(String text) {
        TextView view = text(text, 13, color(R.color.text));
        view.setBackgroundColor(color(R.color.row));
        view.setPadding(dp(10), dp(3), dp(10), dp(9));
        return view;
    }

    private TextView small(String text) {
        TextView view = text(text, 12, color(R.color.text_muted));
        view.setPadding(dp(10), dp(4), dp(10), dp(4));
        return view;
    }

    private TextView warning(String text) {
        TextView view = text(text, 12, color(R.color.warning));
        view.setPadding(dp(10), dp(6), dp(10), dp(6));
        return view;
    }

    private TextView status(String text, int tint) {
        TextView view = text(text, 18, tint);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(null, Typeface.BOLD);
        view.setPadding(dp(6), dp(9), dp(6), dp(2));
        return view;
    }

    /** Label left, value right: the shape every number in the app is read in. */
    private View metric(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(color(R.color.row));
        row.setMinimumHeight(dp(30));
        row.setPadding(dp(10), dp(5), dp(10), dp(5));
        TextView name = text(label, 13, color(R.color.text_muted));
        name.setSingleLine(true);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // Label and value are separated by colour, not weight: a screen of bold
        // numbers has no emphasis left to give.
        TextView amount = text(value, 13, color(R.color.text));
        amount.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        right.leftMargin = dp(8);
        row.addView(amount, right);
        return row;
    }

    private TextView centeredText(String value, int sp, int color) {
        TextView view = text(value, sp, color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(3), 0, 0);
        return view;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(color(R.color.divider));
        line.setLayoutParams(
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        );
        return line;
    }

    /** The inset a dialog puts its title and message text at, read from the
     *  dialog's own theme so a view added with setView() can line up with
     *  them. Falls back to the platform's usual 24dp if the theme is silent. */
    private int dialogTextInset(Context themed) {
        TypedValue value = new TypedValue();
        boolean found = themed.getTheme().resolveAttribute(
                android.R.attr.dialogPreferredPadding,
                value,
                true
            );
        if (!found || value.type != TypedValue.TYPE_DIMENSION) return dp(24);
        return TypedValue.complexToDimensionPixelSize(
            value.data,
            getResources().getDisplayMetrics()
        );
    }

    private interface Success<T> {
        void accept(T value);
    }

    private interface Failure {
        void accept(Exception error);
    }

}
