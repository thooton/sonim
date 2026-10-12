package com.installer.xp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
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
 * work is done by {@link InstallActivity} when Delta calls it.
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

        release = new Button(this);
        release.setText("Stop being device owner");
        release.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmRelease();
            }
        });

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
        body.setText(owner
            ? "This app is the device owner, so Delta can install apps without "
                + "the “App not installed” screen.\n"
            : "Delta's installs will still be blocked until this app is the "
                + "device owner. With the phone connected to a computer and no "
                + "accounts on the phone, run:\n\n"
                + "adb shell dpm set-device-owner com.installer.xp3/.Admin\n\n"
                + "Then reopen this screen to check.");
        release.setVisibility(owner ? View.VISIBLE : View.GONE);
        if (owner) release.requestFocus();
    }

    private void confirmRelease() {
        new AlertDialog.Builder(this)
            .setTitle("Stop being device owner?")
            .setMessage("Delta's installs will be blocked again until this is set up anew over adb.")
            .setPositiveButton("Stop", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dpm.clearDeviceOwnerApp(getPackageName());
                    show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
