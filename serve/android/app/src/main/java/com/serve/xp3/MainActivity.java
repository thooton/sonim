package com.serve.xp3;

import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import com.serve.xp3.net.Addresses;
import com.serve.xp3.net.Server;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Family;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One screen: the folder being served and the address to open it at from
 * another device on the same network, always on {@link Server#PORT}.
 *
 * Keys: OK starts or stops serving (or, before a folder is chosen, chooses
 * one); Menu has Choose folder and Start/Stop. Back leaves, and serving goes
 * on under its notification until stopped there or here.
 */
public final class MainActivity extends BaseActivity implements ServeService.Listener {

    private static final int ASK_STORAGE = 1;
    private static final String PREFS = "serve";

    private TextView stateView;
    private TextView folderView;
    private TextView addressView;
    private TextView othersView;
    private TextView statusView;
    private TextView actionView;
    private File folder;
    private Runnable whenAllowed;

    static String url(String host) {
        return "http://" + host + ":" + Server.PORT;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        stateView = (TextView) findViewById(R.id.state);
        folderView = (TextView) findViewById(R.id.folder);
        addressView = (TextView) findViewById(R.id.address);
        othersView = (TextView) findViewById(R.id.others);
        statusView = (TextView) findViewById(R.id.status);
        actionView = (TextView) findViewById(R.id.action);
        // Drawn as focused: it is what OK does, whatever has the focus.
        actionView.setSelected(true);
        String saved = prefs().getString("folder", "");
        folder = saved.length() == 0 ? null : new File(saved);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ServeService.listen(this);
        changed();
    }

    @Override
    protected void onStop() {
        ServeService.listen(null);
        super.onStop();
    }

    @Override
    public void changed() {
        boolean running = ServeService.running();
        stateView.setText(running ? "Serving" : "Not serving");
        stateView.setTextColor(getColor(running ? R.color.live_text : R.color.text_muted));
        stateView.setTypeface(running ? Family.MEDIUM : Typeface.DEFAULT);
        stateView.getCompoundDrawablesRelative()[0].setLevel(running ? 1 : 0);
        cutStart(folderView, folder == null ? "None chosen" : shown(folder));
        actionView.setText(running ? "Stop serving" : folder == null ? "Choose a folder" : "Start serving");

        // The address changes with the network, so it is read afresh each time.
        List<String> addresses = Addresses.local();
        if (addresses.isEmpty()) {
            addressView.setText("No network");
            othersView.setText("");
        } else {
            addressView.setText(address(addresses.get(0)));
            StringBuilder others = new StringBuilder();
            for (int i = 1; i < addresses.size(); i++) {
                if (others.length() > 0) others.append('\n');
                others.append(url(addresses.get(i)));
            }
            othersView.setText(others);
        }
        othersView.setVisibility(othersView.length() == 0 ? View.GONE : View.VISIBLE);

        String error = ServeService.error();
        problem(error.length() > 0 && !running ? error : null);
    }

    /** "http://" small and muted, so the part that differs is what is read. */
    private CharSequence address(String host) {
        SpannableStringBuilder out = new SpannableStringBuilder(url(host));
        int scheme = "http://".length();
        out.setSpan(new ForegroundColorSpan(getColor(R.color.text_muted)), 0, scheme, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.75f), 0, scheme, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    /** Shows what went wrong at the foot of the screen; null hides it. */
    private void problem(String text) {
        statusView.setText(text);
        statusView.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    /**
     * Shows text wrapped to the view's line limit and, when it is longer,
     * cut at the start with "…" so its end (the folder itself) stays in
     * view. TextView only cuts the start of a single line; over several it
     * just clips the end. Cut again whenever the view's width changes.
     */
    private static void cutStart(final TextView view, final String text) {
        Object old = view.getTag();
        if (old instanceof View.OnLayoutChangeListener) {
            view.removeOnLayoutChangeListener((View.OnLayoutChangeListener) old);
        }
        View.OnLayoutChangeListener refit = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) fit(view, text);
        };
        view.setTag(refit);
        view.addOnLayoutChangeListener(refit);
        fit(view, text);
    }

    private static void fit(TextView view, String text) {
        int width = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
        int most = view.getMaxLines();
        if (width <= 0 || most <= 0 || lines(view, text, width) <= most) {
            view.setText(text);
            return;
        }
        // The fewest leading characters to drop for the rest to fit.
        int low = 1;
        int high = text.length();
        while (low < high) {
            int mid = (low + high) / 2;
            if (lines(view, "…" + text.substring(mid), width) <= most) high = mid;
            else low = mid + 1;
        }
        view.setText("…" + text.substring(low));
    }

    private static int lines(TextView view, String text, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), view.getPaint(), width)
            .setLineSpacing(view.getLineSpacingExtra(), view.getLineSpacingMultiplier())
            .setIncludePad(view.getIncludeFontPadding())
            .setBreakStrategy(view.getBreakStrategy())
            .setHyphenationFrequency(view.getHyphenationFrequency())
            .build()
            .getLineCount();
    }

    /** "Phone/Music", "SD card/DCIM": the folder under the storage it is on. */
    private String shown(File file) {
        String path = file.getPath();
        List<File> volumes = volumes();
        for (int i = 0; i < volumes.size(); i++) {
            String root = volumes.get(i).getPath();
            String label = volumeLabel(i);
            if (path.equals(root)) return label;
            if (path.startsWith(root + "/")) return label + path.substring(root.length());
        }
        return path;
    }

    private static String volumeLabel(int index) {
        return index == 0 ? "Phone" : index == 1 ? "SD card" : "Storage " + (index + 1);
    }

    /** The shared storage roots: the phone's own first, then any card. */
    private List<File> volumes() {
        List<File> roots = new ArrayList<File>();
        for (File dir : getExternalFilesDirs(null)) {
            if (dir == null) continue;
            String path = dir.getPath();
            int cut = path.indexOf("/Android/data/");
            if (cut > 0) roots.add(new File(path.substring(0, cut)));
        }
        return roots;
    }

    private void toggle() {
        if (ServeService.running()) {
            ServeService.stop(this);
        } else if (folder == null) {
            choose();
        } else {
            begin();
        }
    }

    private void begin() {
        if (!allowed(() -> begin())) return;
        if (!folder.isDirectory()) {
            problem("That folder is gone");
            return;
        }
        ServeService.start(this, folder);
    }

    private void choose() {
        if (!allowed(() -> choose())) return;
        List<File> roots = volumes();
        if (roots.size() == 1) pick(roots.get(0), roots.get(0), null);
        else pickVolume(roots);
    }

    private boolean allowed(Runnable then) {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        whenAllowed = then;
        requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, ASK_STORAGE);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != ASK_STORAGE) return;
        Runnable then = whenAllowed;
        whenAllowed = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            // An earlier refusal's "No storage access" no longer holds.
            changed();
            if (then != null) then.run();
        } else {
            problem("No storage access");
        }
    }

    private void pickVolume(final List<File> roots) {
        String[] labels = new String[roots.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = volumeLabel(i);
        new AlertDialog.Builder(this)
            .setTitle("Choose folder")
            .setItems(labels, (dialog, which) -> pick(roots.get(which), roots.get(which), null))
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * One folder: "Serve this folder", then the folders inside it. Back goes
     * up a level, and from the top of its storage closes (or goes back to
     * the storage list when there is a card). Coming back up, the folder
     * just left is the selected row.
     */
    private void pick(final File root, final File at, String from) {
        final List<String> inside = new ArrayList<String>();
        String[] names = at.list();
        if (names != null) {
            for (String name : names) {
                if (!name.startsWith(".") && new File(at, name).isDirectory()) inside.add(name);
            }
        }
        Collections.sort(inside, String.CASE_INSENSITIVE_ORDER);
        List<String> items = new ArrayList<String>();
        items.add("✓ Serve this folder");
        for (String name : inside) items.add(name + "/");

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(shown(at))
            .setItems(items.toArray(new String[0]), (shown, which) -> {
                if (which == 0) chosen(at);
                else pick(root, new File(at, inside.get(which - 1)), null);
            })
            .setNegativeButton("Cancel", null)
            .create();
        dialog.setOnKeyListener((shown, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Stay on the last row instead of moving focus to Cancel.
                ListView list = dialog.getListView();
                return list.hasFocus() && list.getSelectedItemPosition() == list.getCount() - 1;
            }
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                dialog.dismiss();
                up(root, at);
            }
            return true;
        });
        dialog.show();
        // Deep paths would all read alike if the title kept their start.
        TextView title = (TextView) dialog.findViewById(getResources().getIdentifier("alertTitle", "id", "android"));
        if (title != null) cutStart(title, shown(at));
        int row = from == null ? -1 : inside.indexOf(from);
        if (row >= 0) {
            ListView list = dialog.getListView();
            list.setSelection(row + 1);
        }
    }

    /** Back in the folder list: up a level, or off the top of the storage. */
    private void up(File root, File at) {
        if (!at.equals(root)) {
            pick(root, at.getParentFile(), at.getName());
        } else {
            List<File> roots = volumes();
            if (roots.size() > 1) pickVolume(roots);
        }
    }

    /** Remembered, and served straight away (moving a running server over). */
    private void chosen(File next) {
        folder = next;
        prefs().edit().putString("folder", next.getPath()).apply();
        begin();
        changed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (up && !event.isCanceled()) toggle();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (up) menu();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void menu() {
        final boolean running = ServeService.running();
        // With no folder yet there is nothing to start: OK would choose one too.
        if (!running && folder == null) {
            new AlertDialog.Builder(this)
                .setItems(new String[] {"Choose folder…"}, (dialog, which) -> choose())
                .show();
            return;
        }
        String[] labels = {running ? "Stop serving" : "Start serving", "Choose folder…"};
        new AlertDialog.Builder(this)
            .setItems(labels, (dialog, which) -> {
                if (which == 0) toggle();
                else choose();
            })
            .show();
    }
}
