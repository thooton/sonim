package com.installer.xp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A one-screen status page: whether this app is the device owner and, if not,
 * the one command that makes it so. If it is, one button gives the role up
 * again, which is the only way to undo it short of a factory reset. The real
 * work is done by {@link InstallActivity} when Delta or the Updater calls it.
 */
public final class MainActivity extends Activity {

    private DevicePolicyManager dpm;
    private TextView heading;
    private TextView body;
    private Button release;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

        heading = new TextView(this);
        heading.setTextSize(22);
        heading.setPadding(0, 0, 0, 16);

        body = new TextView(this);
        body.setTextSize(14);
        body.setTextColor(Color.DKGRAY);
        body.setTypeface(Typeface.MONOSPACE);

        release = new Button(this);
        release.setText("Stop being device owner");
        release.setOnClickListener(view -> confirmRelease());
        // The stock focus state is a barely darker grey; show it in the accent.
        int[][] states = { { android.R.attr.state_focused }, {} };
        release.setBackgroundTintList(new ColorStateList(states, new int[] { 0xFF2E5B4F, 0xFFD6D7D7 }));
        release.setTextColor(new ColorStateList(states, new int[] { Color.WHITE, 0xDE000000 }));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);
        column.addView(heading);
        column.addView(body);
        column.addView(release);

        ScrollView root = new ScrollView(this);
        root.addView(column);
        setContentView(root);
        show();
    }

    private boolean owner() {
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private void show() {
        boolean owner = owner();
        heading.setText(owner ? "Ready" : "Not set up");
        heading.setTextColor(owner ? 0xFF1F4037 : 0xFFB00020);
        // Not set up: the one command that sets it up, and nothing else.
        body.setText("adb shell dpm set-device-owner com.installer.xp3/.Admin");
        body.setVisibility(owner ? View.GONE : View.VISIBLE);
        release.setVisibility(owner ? View.VISIBLE : View.GONE);
        if (owner) release.requestFocus();
    }

    private void confirmRelease() {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Stop being device owner?")
            .setPositiveButton("Stop", (dialog, which) -> {
                dpm.clearDeviceOwnerApp(getPackageName());
                show();
            })
            .setNegativeButton("Cancel", null)
            .show();
        // The dialog's button style ignores the theme's; say them as written.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setAllCaps(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setAllCaps(false);
    }
}
