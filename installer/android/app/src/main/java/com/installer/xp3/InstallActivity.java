package com.installer.xp3;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs the APK named by its data URI and returns the outcome to the caller.
 *
 * The caller (Delta or the Updater) starts this for a result, with the APK as
 * a content URI and a read grant on it. Because this app is the device owner,
 * committing the session installs straight away, with no confirmation screen.
 * The result is RESULT_OK on success, or RESULT_CANCELED with
 * {@link #EXTRA_MESSAGE} saying what went wrong.
 *
 * A caller that knows which app the APK must be (the Updater, which fetched
 * it by name) says so in {@link #EXTRA_PACKAGE}; Android then refuses an APK
 * for any other package instead of installing it.
 *
 * When the APK is this app's own update, Android stops this app to replace
 * it, so the caller gets RESULT_CANCELED with no message and has to look at
 * the installed version to learn how it went.
 *
 * No window is drawn (the theme is translucent): the caller stays on screen.
 */
public final class InstallActivity extends Activity {

    public static final String EXTRA_MESSAGE = "message";
    /** Optional: the package the APK has to be, or the install is refused. */
    public static final String EXTRA_PACKAGE = "package";

    private static final String ACTION_STATUS = "com.installer.xp3.STATUS";

    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private boolean done;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // A recreated activity has already set its session going.
        if (state != null) return;

        final Uri uri = getIntent() == null ? null : getIntent().getData();
        final String expected = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PACKAGE);
        if (uri == null) {
            fail("No file to install.");
            return;
        }
        // Without the role, Android would send the install to the XP3's own
        // installer screen, which refuses it and never answers the session.
        DevicePolicyManager dpm =
            (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
            fail("Installer isn't the device owner yet. Open Installer for how to set it up.");
            return;
        }

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onStatus(intent);
            }
        };
        registerReceiver(receiver, new IntentFilter(ACTION_STATUS));

        new Thread(() -> commit(uri, expected), "installer-commit").start();
    }

    /** Streams the APK into a session and commits it. Off the main thread. */
    private void commit(Uri uri, String expected) {
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        int id = -1;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            // The session checks the APK against it when it is committed.
            if (expected != null) params.setAppPackageName(expected);
            id = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(id);
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = session.openWrite("base.apk", 0, -1)) {
                    if (in == null) throw new java.io.IOException("Can't read the file.");
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    session.fsync(out);
                }
                Intent status = new Intent(ACTION_STATUS).setPackage(getPackageName());
                PendingIntent sender = PendingIntent.getBroadcast(
                    this, id, status, PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(sender.getIntentSender());
            } finally {
                session.close();
            }
        } catch (final Exception e) {
            if (id != -1) {
                try {
                    installer.abandonSession(id);
                } catch (RuntimeException ignored) {
                }
            }
            main.post(() -> fail(reason(e)));
        }
    }

    /** The session's answer: done, or (unexpected here) a confirmation to show. */
    private void onStatus(Intent intent) {
        int status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Not expected for a device owner. The confirmation screen would
            // be the XP3's own, which always refuses, so give up instead.
            try {
                getPackageManager().getPackageInstaller().abandonSession(
                    intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1));
            } catch (RuntimeException ignored) {
            }
            fail("Android asked for confirmation even though Installer is the device owner.");
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            succeed();
        } else {
            fail(message(intent));
        }
    }

    private void succeed() {
        if (done) return;
        done = true;
        setResult(RESULT_OK);
        finish();
    }

    private void fail(String why) {
        if (done) return;
        done = true;
        setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_MESSAGE, why));
        finish();
    }

    private static String message(Intent intent) {
        String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        return why != null ? why : "The install failed.";
    }

    private static String reason(Exception e) {
        String why = e.getMessage();
        return why != null ? why : e.getClass().getSimpleName();
    }

    @Override
    protected void onDestroy() {
        if (receiver != null) unregisterReceiver(receiver);
        super.onDestroy();
    }
}
