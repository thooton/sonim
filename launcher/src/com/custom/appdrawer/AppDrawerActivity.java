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
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
 * menu for adding any launchable app or removing the selected one; the resulting
 * list is kept in this app's private preferences.
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);

        root = new FrameLayout(this);
        root.setBackgroundColor(getResources().getColor(R.color.drawer_background));

        grid = new GridView(this);
        grid.setBackgroundColor(getResources().getColor(R.color.drawer_background));
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
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(adapter.getEntry(position));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showProgramMenu(position);
                return true;
            }
        });

        TextView empty = new TextView(this);
        empty.setText(R.string.drawer_empty);
        empty.setTextColor(getResources().getColor(R.color.app_label));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.app_label_size));
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
        focusFirstApp();
    }

    @Override
    protected void onPause() {
        resumed = false;
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
        focusFirstApp();
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
        if (isValidPosition(menuPosition)) {
            menu.setHeaderTitle(adapter.getEntry(menuPosition).label);
        } else {
            menu.setHeaderTitle(R.string.app_name);
        }
        menu.add(Menu.NONE, MENU_ADD, Menu.NONE, R.string.menu_add_program);
        if (isValidPosition(menuPosition)) {
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

        final List<Candidate> candidates = new ArrayList<>(resolved.size());
        for (ResolveInfo info : resolved) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null
                    || getPackageName().equals(activity.packageName)
                    || indexOfTarget(activity.packageName, activity.name) >= 0) {
                continue;
            }
            CharSequence label = info.loadLabel(pm);
            if (TextUtils.isEmpty(label)) {
                label = activity.packageName;
            }
            candidates.add(new Candidate(activity.packageName, activity.name, label.toString()));
        }

        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.no_programs_to_add, Toast.LENGTH_SHORT).show();
            return;
        }

        final Collator collator = Collator.getInstance();
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return collator.compare(a.label, b.label);
            }
        });

        CharSequence[] labels = new CharSequence[candidates.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = candidates.get(i).label;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_add_program)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Candidate chosen = candidates.get(which);
                        addTarget(new Target(chosen.packageName, chosen.className, chosen.label));
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
        PackageManager pm = getPackageManager();
        List<Entry> entries = new ArrayList<>(targets.size());
        for (Target target : targets) {
            ComponentName component = new ComponentName(target.packageName, target.className);
            Drawable icon = pm.getDefaultActivityIcon();
            CharSequence label = target.fallbackLabel;
            Intent launchIntent = null;

            try {
                Presentation cached = presentations.get(component);
                if (cached == null) {
                    ActivityInfo info = pm.getActivityInfo(component, 0);
                    cached = new Presentation(info.loadIcon(pm), info.loadLabel(pm));
                    presentations.put(component, cached);
                }
                if (cached.icon != null) {
                    icon = cached.icon;
                }
                if (!TextUtils.isEmpty(cached.label)) {
                    label = cached.label;
                }
                launchIntent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Keep the slot visible so installation problems are obvious.
                // A package-level launch intent also tolerates a renamed entry activity.
                launchIntent = pm.getLaunchIntentForPackage(target.packageName);
            }

            entries.add(new Entry(label, icon, launchIntent));
        }
        adapter.replace(entries);
    }

    private void launch(Entry entry) {
        if (entry.intent == null) {
            Toast.makeText(this,
                    getString(R.string.app_unavailable, entry.label),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            startActivity(entry.intent);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(this,
                    getString(R.string.app_unavailable, entry.label),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void focusFirstApp() {
        selectPosition(0);
    }

    private void selectPosition(final int position) {
        grid.post(new Runnable() {
            @Override
            public void run() {
                grid.requestFocus();
                if (position >= 0) {
                    grid.setSelection(position);
                }
            }
        });
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()));
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

    private static final class Candidate {
        final String packageName;
        final String className;
        final String label;

        Candidate(String packageName, String className, String label) {
            this.packageName = packageName;
            this.className = className;
            this.label = label;
        }
    }

    private static final class Presentation {
        final Drawable icon;
        final CharSequence label;

        Presentation(Drawable icon, CharSequence label) {
            this.icon = icon;
            this.label = label;
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

        void replace(List<Entry> replacement) {
            entries.clear();
            entries.addAll(replacement);
            notifyDataSetChanged();
        }

        Entry getEntry(int position) {
            return entries.get(position);
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
                LinearLayout item = new LinearLayout(AppDrawerActivity.this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER_HORIZONTAL);
                item.setBackgroundColor(Color.TRANSPARENT);
                item.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        getResources().getDimensionPixelSize(R.dimen.app_item_height)));

                ImageView icon = new ImageView(AppDrawerActivity.this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                        getResources().getDimensionPixelSize(R.dimen.app_icon_size),
                        getResources().getDimensionPixelSize(R.dimen.app_icon_size));
                iconParams.topMargin = getResources().getDimensionPixelSize(
                        R.dimen.app_icon_top_margin);
                item.addView(icon, iconParams);

                TextView label = new TextView(AppDrawerActivity.this);
                label.setTextColor(getResources().getColor(R.color.app_label));
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimension(R.dimen.app_label_size));
                label.setGravity(Gravity.CENTER_HORIZONTAL);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                labelParams.topMargin = getResources().getDimensionPixelSize(
                        R.dimen.app_label_top_margin);
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
            holder.icon.setAlpha(entry.intent == null ? 0.45f : 1.0f);
            holder.label.setAlpha(entry.intent == null ? 0.45f : 1.0f);
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
