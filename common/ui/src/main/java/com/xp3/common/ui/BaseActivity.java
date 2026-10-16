package com.xp3.common.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import android.widget.Toast;

/** An Activity with the small helpers every screen here ends up wanting. */
public abstract class BaseActivity extends Activity {

    protected final int dp(float value) {
        return Ui.dp(this, value);
    }

    protected final int color(int resId) {
        return getColor(resId);
    }

    protected final void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    protected final LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    protected final TextView text(CharSequence value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    /**
     * A list to choose from, with Cancel. Back closes it and runs `back`,
     * which is how a folder browser goes up a level; a null `back` just
     * closes it.
     */
    protected final void listDialog(String title, List<String> items, final Ui.Selected chose, final Runnable back) {
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toArray(new String[0]), (shown, which) -> chose.at(which))
            .setNegativeButton("Cancel", null)
            .create();
        dialog.setOnKeyListener((shown, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                dialog.dismiss();
                if (back != null) back.run();
            }
            return true;
        });
        dialog.show();
    }

    /** A message with a title and an OK button. */
    protected final void alert(String title, String message) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }
}
