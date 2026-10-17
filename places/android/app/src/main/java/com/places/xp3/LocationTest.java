package com.places.xp3;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/**
 * TEMPORARY: whether this phone can tell where it is at all. Menu → Test
 * location lists what the phone says it has (GPS, network location, the
 * location setting, the providers and their last known fixes), then listens
 * to every enabled provider for 90 seconds and writes down each fix.
 *
 * To remove it: delete this file, its Menu item and
 * onRequestPermissionsResult in MainActivity, and the two location
 * permissions in the manifest.
 */
final class LocationTest implements LocationListener {

    static final int REQUEST = 7;
    private static final long LISTEN_MS = 90000;

    private final Activity activity;
    private final LocationManager manager;
    private final TextView out;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long started = System.currentTimeMillis();
    private int fixes;

    /** Asks for the permission first if need be; MainActivity calls again once it is answered. */
    static void start(Activity activity) {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST);
            return;
        }
        new LocationTest(activity).run();
    }

    private LocationTest(Activity activity) {
        this.activity = activity;
        this.manager = (LocationManager) activity.getSystemService(Activity.LOCATION_SERVICE);
        this.out = new TextView(activity);
        out.setTextSize(12f);
        out.setTextColor(activity.getColor(R.color.text));
        out.setTextIsSelectable(false);
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        out.setPadding(pad, pad / 2, pad, pad / 2);
    }

    private void run() {
        PackageManager packages = activity.getPackageManager();
        say("GPS hardware: " + yes(packages.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)));
        say("Network location: " + yes(packages.hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK)));
        say("Any location: " + yes(packages.hasSystemFeature(PackageManager.FEATURE_LOCATION)));
        say("Location setting: " + mode());
        if (manager == null) {
            say("No location service on this phone.");
        } else {
            say("Providers: " + manager.getAllProviders());
            say("Enabled: " + manager.getProviders(true));
            for (String provider : manager.getAllProviders()) {
                try {
                    Location last = manager.getLastKnownLocation(provider);
                    say("Last " + provider + ": " + (last == null ? "none" : describe(last)));
                } catch (SecurityException | IllegalArgumentException e) {
                    say("Last " + provider + ": " + e.getMessage());
                }
            }
            int listening = 0;
            for (String provider : manager.getProviders(true)) {
                if (provider.equals(LocationManager.PASSIVE_PROVIDER)) continue;
                try {
                    manager.requestLocationUpdates(provider, 0, 0, this, Looper.getMainLooper());
                    listening++;
                } catch (SecurityException | IllegalArgumentException e) {
                    say("Can't listen to " + provider + ": " + e.getMessage());
                }
            }
            if (listening > 0) {
                say("Listening for 90 s (go outside for GPS)…");
                main.postDelayed(this::finish, LISTEN_MS);
            } else {
                say("Nothing to listen to.");
            }
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(out);
        new AlertDialog.Builder(activity)
            .setTitle("Location test")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setOnDismissListener(dialog -> stop())
            .show();
    }

    @Override
    public void onLocationChanged(Location location) {
        fixes++;
        say("Fix after " + (System.currentTimeMillis() - started) / 1000 + " s: " + describe(location));
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        say(provider + " turned on");
    }

    @Override
    public void onProviderDisabled(String provider) {
        say(provider + " turned off");
    }

    private void finish() {
        stop();
        say(fixes == 0 ? "No fix in 90 s." : "Done: " + fixes + " fix" + (fixes == 1 ? "" : "es") + ".");
    }

    private void stop() {
        main.removeCallbacksAndMessages(null);
        if (manager != null) manager.removeUpdates(this);
    }

    private String mode() {
        try {
            int mode = Settings.Secure.getInt(activity.getContentResolver(), Settings.Secure.LOCATION_MODE);
            switch (mode) {
                case Settings.Secure.LOCATION_MODE_OFF:
                    return "off";
                case Settings.Secure.LOCATION_MODE_SENSORS_ONLY:
                    return "device only (GPS)";
                case Settings.Secure.LOCATION_MODE_BATTERY_SAVING:
                    return "battery saving (network)";
                case Settings.Secure.LOCATION_MODE_HIGH_ACCURACY:
                    return "high accuracy";
                default:
                    return "mode " + mode;
            }
        } catch (Settings.SettingNotFoundException e) {
            return "unknown";
        }
    }

    private static String describe(Location location) {
        long age = (System.currentTimeMillis() - location.getTime()) / 1000;
        return String.format(Locale.US, "%s %.5f,%.5f ±%.0f m, %d s old", location.getProvider(),
            location.getLatitude(), location.getLongitude(), location.getAccuracy(), age);
    }

    private static String yes(boolean has) {
        return has ? "yes" : "no";
    }

    private void say(String line) {
        out.append((out.length() == 0 ? "" : "\n") + line);
    }
}
