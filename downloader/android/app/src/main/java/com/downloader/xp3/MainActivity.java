package com.downloader.xp3;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * One screen: an address field, a progress bar and a line of status. OK
 * downloads the APK at the address, and the install prompt opens as soon as it
 * has arrived. OK again on the same address re-opens the prompt.
 */
public final class MainActivity extends Activity {

    private static final int REQUEST_INSTALL = 1;
    private static final String PREFS = "downloader";
    private static final String KEY_ADDRESS = "address";

    private EditText address;
    private ProgressBar progress;
    private TextView status;

    /** The download in flight, or null. */
    private Download running;
    /** The address whose APK is waiting in the cache, ready to install. */
    private String readyAddress;
    private String readyLabel;
    /** Whether the waiting APK replaces an app already on the phone. */
    private boolean readyIsUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        address = (EditText) findViewById(R.id.address);
        progress = (ProgressBar) findViewById(R.id.progress);
        status = (TextView) findViewById(R.id.status);

        // Wrapped rather than scrolled sideways, so a long address can be
        // checked whole on a 240px screen.
        address.setHorizontallyScrolling(false);
        address.setText(prefs().getString(KEY_ADDRESS, ""));
        address.setSelection(address.getText().length());
        /*
         * OK acts on the press, as List's text fields do: with T9 up, the first
         * ENTER release never reaches the app because the keyboard takes it to
         * finish the word. Both halves are swallowed so no newline goes in.
         */
        address.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) go();
                return true;
            }
        });
        address.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_GO && actionId != EditorInfo.IME_ACTION_DONE) return false;
                go();
                return true;
            }
        });
        address.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs().edit().putString(KEY_ADDRESS, address.getText().toString()).apply();
    }

    @Override
    protected void onDestroy() {
        if (running != null) running.cancelled = true;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (running != null) {
            running.cancelled = true;
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_INSTALL) return;
        if (resultCode == RESULT_OK) {
            status.setText((readyIsUpdate ? "Updated " : "Installed ") + readyLabel + ".");
            ApkProvider.file(this).delete();
            readyAddress = null;
        } else {
            status.setText(readyLabel + (readyIsUpdate ? " was not updated." : " was not installed.") + " Press OK to try again.");
        }
    }

    /** OK: install what is waiting for this address, or fetch it. */
    private void go() {
        if (running != null) return;
        String typed = address.getText().toString().trim();
        if (typed.length() == 0) {
            status.setText("Type the address of an APK first.");
            return;
        }
        if (!typed.contains("://")) typed = "https://" + typed;
        if (typed.equals(readyAddress) && ApkProvider.file(this).isFile()) {
            install();
            return;
        }
        start(typed);
    }

    private void start(String url) {
        readyAddress = null;
        running = new Download(url);
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        status.setText("Connecting…\nBack cancels.");
        // A large APK on a slow line outlasts the screen timeout.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(running, "download").start();
    }

    /** Called on the main thread once {@code download} has ended, however it ended. */
    private void finished(Download download, String error) {
        if (download != running) return;
        running = null;
        progress.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (error != null) {
            status.setText(error);
            return;
        }
        File apk = ApkProvider.file(this);
        PackageManager pm = getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (info == null) {
            apk.delete();
            status.setText("That file isn't an app. Check the address goes straight to the .apk.");
            return;
        }
        // The label is only readable once the archive's own path is filled in.
        ApplicationInfo app = info.applicationInfo;
        app.sourceDir = apk.getPath();
        app.publicSourceDir = apk.getPath();
        String label = pm.getApplicationLabel(app).toString();

        /*
         * An app already on the phone is updated in place by the same install
         * prompt, keeping its data. The installer refuses a different signing
         * key or an older version with only "App not installed", so both are
         * checked here first and explained.
         */
        PackageInfo installed = installed(info.packageName);
        if (installed != null && !Arrays.equals(installed.signatures, info.signatures)) {
            apk.delete();
            status.setText("Can't update " + label + ": this APK was signed by someone other than "
                + "whoever made the copy on the phone. Uninstall that one first to switch.");
            return;
        }
        if (installed != null && versionCode(info) < versionCode(installed)) {
            apk.delete();
            status.setText("Can't update " + label + ": this APK is " + version(info)
                + ", older than the " + version(installed) + " on the phone, and Android won't go back.");
            return;
        }
        readyIsUpdate = installed != null;
        readyLabel = readyIsUpdate
            ? label + " " + version(installed) + " \u2192 " + version(info)
            : label + (info.versionName != null ? " " + info.versionName : "");
        readyAddress = download.url;
        install();
    }

    private void install() {
        status.setText((readyIsUpdate ? "Update ready: " : "Downloaded ") + readyLabel + " ("
            + Formatter.formatShortFileSize(this, ApkProvider.file(this).length())
            + ").\nPress OK to open the install prompt again.");
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(ApkProvider.uri(), ApkProvider.MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            startActivityForResult(intent, REQUEST_INSTALL);
        } catch (ActivityNotFoundException e) {
            status.setText("This phone has no installer to hand the APK to.");
        }
    }

    /** The copy of {@code packageName} already on the phone, or null. */
    private PackageInfo installed(String packageName) {
        try {
            return getPackageManager().getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // getLongVersionCode arrives in API 28; the XP3 is 27.
    @SuppressWarnings("deprecation")
    private static int versionCode(PackageInfo info) {
        return info.versionCode;
    }

    private static String version(PackageInfo info) {
        return info.versionName != null ? info.versionName : "version " + versionCode(info);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static String describe(IOException e) {
        if (e instanceof Fetch.Cancelled) return "Cancelled.";
        if (e instanceof MalformedURLException) return "That isn't a web address.";
        if (e instanceof UnknownHostException) return "Can't find that server. Check the address and the connection.";
        if (e instanceof SocketTimeoutException) return "The server stopped answering. Press OK to try again.";
        String message = e.getMessage();
        return message != null ? message : "The download failed.";
    }

    /** Runs on its own thread; reports back through {@link #runOnUiThread}. */
    private final class Download implements Runnable, Fetch.Listener {
        final String url;
        volatile boolean cancelled;
        private long lastUpdate;

        Download(String url) {
            this.url = url;
        }

        @Override
        public void run() {
            File part = new File(getCacheDir(), "download.part");
            String error = null;
            try {
                ApkProvider.file(MainActivity.this).delete();
                Fetch.to(url, part, this);
                if (!part.renameTo(ApkProvider.file(MainActivity.this))) throw new IOException("Couldn't save the file.");
            } catch (IOException e) {
                error = describe(e);
            } catch (RuntimeException e) {
                // URL parsing can throw IllegalArgumentException on odd input.
                error = "That isn't a web address.";
            } finally {
                part.delete();
            }
            final String outcome = error;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finished(Download.this, outcome);
                }
            });
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public void progress(final long done, final long total) {
            long now = SystemClock.uptimeMillis();
            if (done > 0 && now - lastUpdate < 250) return;
            lastUpdate = now;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (running != Download.this) return;
                    String size = Formatter.formatShortFileSize(MainActivity.this, done);
                    if (total > 0) {
                        progress.setIndeterminate(false);
                        progress.setProgress((int) (done * 1000 / total));
                        size += " of " + Formatter.formatShortFileSize(MainActivity.this, total);
                    }
                    status.setText("Downloading… " + size + "\nBack cancels.");
                }
            });
        }
    }
}
