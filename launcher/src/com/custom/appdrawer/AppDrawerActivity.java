package com.custom.appdrawer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately tiny app drawer for the sideloaded XP3 applications.
 *
 * It is an ordinary LAUNCHER activity, not a HOME activity. That keeps all of
 * the stock launcher, telephony, Wi-Fi, and quick-settings responsibilities out
 * of this process while still allowing Sonim's programmable key UI to launch it.
 *
 * The drawer starts with {@link #DEFAULT_TARGETS}. The Menu key opens a context
 * menu for adding any launchable app, moving the selected one, or removing it;
 * the resulting list is kept in this app's private preferences.
 */
public final class AppDrawerActivity extends Activity {
    private static final DefaultTarget[] DEFAULT_TARGETS = {
            new DefaultTarget("com.todo.xp3", "com.todo.xp3.MainActivity", R.string.todo_name),
            new DefaultTarget("com.teslacharge.xp3", "com.teslacharge.xp3.MainActivity", R.string.charge_name),
            new DefaultTarget("com.flashcards.xp3", "com.flashcards.xp3.MainActivity", R.string.flashcards_name),
            new DefaultTarget("com.list.xp3", "com.list.xp3.MainActivity", R.string.list_name),
            new DefaultTarget("com.dictionary.xp3", "com.dictionary.xp3.MainActivity", R.string.dictionary_name)
    };

    private static final String PREFS_NAME = "drawer";
    private static final String PREF_TARGETS = "targets";
    private static final String KEY_PACKAGE = "package";
    private static final String KEY_CLASS = "class";
    private static final String KEY_LABEL = "label";

    private static final int MENU_ADD = Menu.FIRST;
    private static final int MENU_REMOVE = Menu.FIRST + 1;
    private static final int MENU_MOVE = Menu.FIRST + 2;

    private final List<Target> targets = new ArrayList<>();

    /**
     * Icons and labels already read from other apps' APKs, by component. Loading
     * them is the slowest part of showing the drawer, so it is done once per
     * process rather than on every resume; {@link #packageReceiver} empties the
     * cache whenever an app is installed, updated, or removed.
     */
    private final Map<ComponentName, Presentation> presentations = new HashMap<>();

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            presentations.clear();
            if (resumed) {
                refreshApps();
            }
        }
    };

    private boolean resumed;

    private FrameLayout root;
    private GridView grid;
    private AppAdapter adapter;
    private int menuPosition = AdapterView.INVALID_POSITION;

    /**
     * While an app is being moved, the targets as they were before it was picked
     * up (restored if the move is cancelled); null otherwise. The moving app is
     * always the grid's selected item.
     */
    private List<Target> targetsBeforeMove;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.drawer_background));

        grid = new GridView(this);
        grid.setBackgroundColor(getColor(R.color.drawer_background));
        grid.setNumColumns(3);
        grid.setColumnWidth(dp(80));
        grid.setHorizontalSpacing(0);
        grid.setVerticalSpacing(0);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setGravity(Gravity.TOP);
        grid.setPadding(0, 0, 0, 0);
        grid.setClipToPadding(false);
        grid.setSelector(R.drawable.grid_selector);
        grid.setDrawSelectorOnTop(false);
        grid.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        // No lambdas here: build.sh's javac compiles against android.jar alone,
        // which has no LambdaMetafactory.
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (isMoving()) {
                    endMove(true);
                } else {
                    launch(adapter.getItem(position));
                }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (!isMoving()) {
                    showProgramMenu(position);
                }
                return true;
            }
        });

        TextView empty = labelText();
        empty.setText(R.string.drawer_empty);
        empty.setGravity(Gravity.CENTER);
        int emptyPadding = getResources().getDimensionPixelSize(R.dimen.empty_hint_padding);
        empty.setPadding(emptyPadding, emptyPadding, emptyPadding, emptyPadding);

        root.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        grid.setEmptyView(empty);

        // The menu is anchored on the container rather than the grid so that
        // AbsListView never substitutes its own item position for ours.
        registerForContextMenu(root);

        setContentView(root);
        loadTargets();

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        registerReceiver(packageReceiver, packageFilter);
        // onResume, which always follows, fills the grid and focuses the first app.
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshApps();
        selectPosition(0);
    }

    @Override
    protected void onPause() {
        resumed = false;
        // Leaving mid-move keeps the app where it was carried to.
        if (isMoving()) {
            endMove(true);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        selectPosition(0);
    }

    /**
     * While an app is being moved the D-pad carries it instead of moving the
     * selection; the center key or Menu puts it down and Back puts it back.
     * Keys are taken here, before the grid sees them.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isMoving()) {
            return super.dispatchKeyEvent(event);
        }
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) moveSelectedBy(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveSelectedBy(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) moveSelectedBy(-grid.getNumColumns());
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveSelectedBy(grid.getNumColumns());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                // Drop on release so the key's up event can't also launch or open a menu.
                if (!down && event.getRepeatCount() == 0) endMove(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) endMove(false);
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.isTracking() && !event.isCanceled()) {
                showProgramMenu(grid.getSelectedItemPosition());
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, view, menuInfo);
        boolean onApp = isValidPosition(menuPosition);
        if (onApp) {
            menu.setHeaderTitle(adapter.getItem(menuPosition).label);
        } else {
            menu.setHeaderTitle(R.string.app_name);
        }
        menu.add(Menu.NONE, MENU_ADD, Menu.NONE, R.string.menu_add_program);
        if (onApp && targets.size() > 1) {
            menu.add(Menu.NONE, MENU_MOVE, Menu.NONE, R.string.menu_move_program);
        }
        if (onApp) {
            menu.add(Menu.NONE, MENU_REMOVE, Menu.NONE, R.string.menu_remove_program);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ADD:
                showAddProgramDialog();
                return true;
            case MENU_REMOVE:
                removeTarget(menuPosition);
                return true;
            case MENU_MOVE:
                startMove(menuPosition);
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private void showProgramMenu(int position) {
        menuPosition = position;
        openContextMenu(root);
    }

    private boolean isValidPosition(int position) {
        return position >= 0 && position < targets.size() && position < adapter.getCount();
    }

    private void showAddProgramDialog() {
        final PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(query, 0);

        final List<Target> candidates = new ArrayList<>(resolved.size());
        for (ResolveInfo info : resolved) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null
                    || getPackageName().equals(activity.packageName)
                    || indexOfTarget(activity.packageName, activity.name) >= 0
                    || adapter.launches(new ComponentName(activity.packageName, activity.name))) {
                continue;
            }
            CharSequence label = info.loadLabel(pm);
            if (TextUtils.isEmpty(label)) {
                label = activity.packageName;
            }
            candidates.add(new Target(activity.packageName, activity.name, label.toString()));
        }

        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.no_programs_to_add, Toast.LENGTH_SHORT).show();
            return;
        }

        final Collator collator = Collator.getInstance();
        Collections.sort(candidates, new Comparator<Target>() {
            @Override
            public int compare(Target a, Target b) {
                return collator.compare(a.fallbackLabel, b.fallbackLabel);
            }
        });

        CharSequence[] labels = new CharSequence[candidates.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = candidates.get(i).fallbackLabel;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_add_program)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        addTarget(candidates.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addTarget(Target target) {
        if (indexOfTarget(target.packageName, target.className) >= 0) {
            return;
        }
        targets.add(target);
        saveTargets();
        refreshApps();
        selectPosition(targets.size() - 1);
    }

    private void removeTarget(int position) {
        if (!isValidPosition(position)) {
            return;
        }
        targets.remove(position);
        saveTargets();
        refreshApps();
        selectPosition(Math.min(position, targets.size() - 1));
    }

    private boolean isMoving() {
        return targetsBeforeMove != null;
    }

    /** Lifts the app at {@code position} so the D-pad can carry it. */
    private void startMove(int position) {
        if (!isValidPosition(position)) {
            return;
        }
        targetsBeforeMove = new ArrayList<>(targets);
        adapter.setMoving(position);
        // The moving card has rounded corners; the square selection outline
        // would show behind them.
        grid.setSelector(android.R.color.transparent);
        selectPosition(position);
    }

    /**
     * Carries the moving app {@code delta} slots along the grid, the apps in
     * between shifting over to make room. Past either end it stops there.
     */
    private void moveSelectedBy(int delta) {
        int from = adapter.movingPosition;
        if (!isValidPosition(from)) {
            return;
        }
        int to = Math.max(0, Math.min(targets.size() - 1, from + delta));
        if (to == from) {
            return;
        }
        targets.add(to, targets.remove(from));
        adapter.move(from, to);
        showSelection(to);
    }

    /** Puts the moving app down, keeping its new place or restoring the old order. */
    private void endMove(boolean keep) {
        int position = adapter.movingPosition;
        if (!keep) {
            Target moved = isValidPosition(position) ? targets.get(position) : null;
            targets.clear();
            targets.addAll(targetsBeforeMove);
            position = moved == null ? 0 : targets.indexOf(moved);
        }
        targetsBeforeMove = null;
        adapter.setMoving(AdapterView.INVALID_POSITION);
        grid.setSelector(R.drawable.grid_selector);
        if (keep) {
            saveTargets();
        }
        refreshApps();
        selectPosition(position);
    }

    private int indexOfTarget(String packageName, String className) {
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            if (target.packageName.equals(packageName) && target.className.equals(className)) {
                return i;
            }
        }
        return -1;
    }

    private void loadTargets() {
        targets.clear();
        String stored = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_TARGETS, null);
        if (stored != null) {
            try {
                JSONArray array = new JSONArray(stored);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.getJSONObject(i);
                    targets.add(new Target(
                            object.getString(KEY_PACKAGE),
                            object.getString(KEY_CLASS),
                            object.optString(KEY_LABEL, object.getString(KEY_PACKAGE))));
                }
                return;
            } catch (JSONException ignored) {
                // Fall back to the built-in list rather than presenting a broken drawer.
                targets.clear();
            }
        }

        for (DefaultTarget target : DEFAULT_TARGETS) {
            targets.add(new Target(target.packageName, target.className,
                    getString(target.fallbackLabel)));
        }
    }

    private void saveTargets() {
        JSONArray array = new JSONArray();
        try {
            for (Target target : targets) {
                array.put(new JSONObject()
                        .put(KEY_PACKAGE, target.packageName)
                        .put(KEY_CLASS, target.className)
                        .put(KEY_LABEL, target.fallbackLabel));
            }
        } catch (JSONException exception) {
            // Only thrown for non-finite numbers, which are never stored here.
            throw new IllegalStateException(exception);
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_TARGETS, array.toString())
                .apply();
    }

    private void refreshApps() {
        if (isMoving()) {
            // A package change mid-move would rebuild the grid under the moving
            // app; the drop refreshes it anyway.
            return;
        }
        PackageManager pm = getPackageManager();
        List<Entry> entries = new ArrayList<>(targets.size());
        for (Target target : targets) {
            ComponentName component = new ComponentName(target.packageName, target.className);
            Presentation cached = presentations.get(component);
            if (cached == null) {
                cached = loadPresentation(pm, component);
                if (cached != null) {
                    presentations.put(component, cached);
                }
            }
            if (cached == null) {
                // Keep the slot visible, dimmed, so installation problems are obvious.
                entries.add(new Entry(target.fallbackLabel, pm.getDefaultActivityIcon(), null));
                continue;
            }
            entries.add(new Entry(
                    TextUtils.isEmpty(cached.label) ? target.fallbackLabel : cached.label,
                    cached.icon != null ? cached.icon : pm.getDefaultActivityIcon(),
                    new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(cached.component)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        }
        adapter.replace(entries);
    }

    /**
     * Reads the icon and label of the activity a target starts: its own, or, if
     * that activity was renamed, the package's main activity, which the entry then
     * stands for. Null when neither is installed.
     */
    private static Presentation loadPresentation(PackageManager pm, ComponentName component) {
        ActivityInfo info;
        try {
            info = pm.getActivityInfo(component, 0);
        } catch (PackageManager.NameNotFoundException missing) {
            Intent launchIntent = pm.getLaunchIntentForPackage(component.getPackageName());
            if (launchIntent == null || launchIntent.getComponent() == null) {
                return null;
            }
            component = launchIntent.getComponent();
            try {
                info = pm.getActivityInfo(component, 0);
            } catch (PackageManager.NameNotFoundException ignored) {
                return null;
            }
        }
        return new Presentation(info.loadIcon(pm), info.loadLabel(pm), component);
    }

    private void launch(Entry entry) {
        try {
            if (entry.intent != null) {
                startActivity(entry.intent);
                return;
            }
        } catch (ActivityNotFoundException | SecurityException exception) {
            // Said the same way as an app that isn't there.
        }
        Toast.makeText(this, getString(R.string.app_unavailable, entry.label), Toast.LENGTH_SHORT).show();
    }

    private void selectPosition(final int position) {
        grid.post(new Runnable() {
            @Override
            public void run() {
                grid.requestFocus();
                if (position >= 0) {
                    showSelection(position);
                }
            }
        });
    }

    /**
     * Selects {@code position}, scrolling the grid only as far as it takes to
     * show that row. GridView.setSelection would instead put the row at the top
     * whenever the grid isn't in touch mode, as on the XP3.
     */
    private void showSelection(int position) {
        View first = grid.getChildAt(0);
        int columns = grid.getNumColumns();
        if (first == null || columns <= 0) {
            grid.setSelection(position);
            return;
        }
        int rowHeight = first.getHeight();
        int firstRow = grid.getFirstVisiblePosition() / columns;
        int top = first.getTop() + (position / columns - firstRow) * rowHeight;
        int lowest = grid.getHeight() - grid.getPaddingTop() - grid.getPaddingBottom() - rowHeight;
        grid.setSelectionFromTop(position, Math.max(0, Math.min(lowest, top - grid.getPaddingTop())));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** A TextView in the app labels' colour and size. */
    private TextView labelText() {
        TextView view = new TextView(this);
        view.setTextColor(getColor(R.color.app_label));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.app_label_size));
        return view;
    }

    private static final class DefaultTarget {
        final String packageName;
        final String className;
        final int fallbackLabel;

        DefaultTarget(String packageName, String className, int fallbackLabel) {
            this.packageName = packageName;
            this.className = className;
            this.fallbackLabel = fallbackLabel;
        }
    }

    private static final class Target {
        final String packageName;
        final String className;
        final String fallbackLabel;

        Target(String packageName, String className, String fallbackLabel) {
            this.packageName = packageName;
            this.className = className;
            this.fallbackLabel = fallbackLabel;
        }
    }


    private static final class Presentation {
        final Drawable icon;
        final CharSequence label;
        /** The activity the entry starts. */
        final ComponentName component;

        Presentation(Drawable icon, CharSequence label, ComponentName component) {
            this.icon = icon;
            this.label = label;
            this.component = component;
        }
    }

    private static final class Entry {
        final CharSequence label;
        final Drawable icon;
        final Intent intent;

        Entry(CharSequence label, Drawable icon, Intent intent) {
            this.label = label;
            this.icon = icon;
            this.intent = intent;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<Entry> entries = new ArrayList<>();
        int movingPosition = AdapterView.INVALID_POSITION;

        void setMoving(int position) {
            movingPosition = position;
            notifyDataSetChanged();
        }

        void move(int from, int to) {
            entries.add(to, entries.remove(from));
            movingPosition = to;
            notifyDataSetChanged();
        }

        void replace(List<Entry> replacement) {
            entries.clear();
            entries.addAll(replacement);
            notifyDataSetChanged();
        }


        /** Whether some entry already starts {@code component}. */
        boolean launches(ComponentName component) {
            for (Entry entry : entries) {
                if (entry.intent != null && component.equals(entry.intent.getComponent())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Entry getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                Resources res = getResources();
                LinearLayout item = new LinearLayout(AppDrawerActivity.this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                item.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        res.getDimensionPixelSize(R.dimen.app_item_height)));

                ImageView icon = new ImageView(AppDrawerActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int iconSize = res.getDimensionPixelSize(R.dimen.app_icon_size);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
                iconParams.topMargin = res.getDimensionPixelSize(R.dimen.app_icon_top_margin);
                item.addView(icon, iconParams);

                TextView label = labelText();
                label.setGravity(Gravity.CENTER_HORIZONTAL);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                labelParams.topMargin = res.getDimensionPixelSize(R.dimen.app_label_top_margin);
                item.addView(label, labelParams);

                holder = new Holder(icon, label);
                item.setTag(holder);
                convertView = item;
            } else {
                holder = (Holder) convertView.getTag();
            }

            Entry entry = getItem(position);
            holder.icon.setImageDrawable(entry.icon);
            holder.label.setText(entry.label);
            float alpha = entry.intent == null ? 0.45f : 1.0f;
            holder.icon.setAlpha(alpha);
            holder.label.setAlpha(alpha);
            if (position == movingPosition) {
                convertView.setBackgroundResource(R.drawable.grid_moving);
            } else {
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }
            return convertView;
        }
    }

    private static final class Holder {
        final ImageView icon;
        final TextView label;

        Holder(ImageView icon, TextView label) {
            this.icon = icon;
            this.label = label;
        }
    }
}
