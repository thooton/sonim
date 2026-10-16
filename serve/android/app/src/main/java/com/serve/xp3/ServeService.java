package com.serve.xp3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import com.serve.xp3.net.Addresses;
import com.serve.xp3.net.Server;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Holds the one running {@link Server} with a notification up, so it keeps
 * answering after the screen is left or the phone is shut. Its state is
 * static and main-thread only, for the activity to read.
 */
public final class ServeService extends Service {

    /** Told on the main thread whenever serving starts, stops or fails. */
    interface Listener {
        void changed();
    }

    private static final String EXTRA_FOLDER = "folder";
    private static final String ACTION_STOP = "com.serve.xp3.STOP";
    private static final String CHANNEL = "serving";
    private static final int NOTIFICATION = 1;

    private static Server server;
    private static String error = "";
    private static Listener listener;

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    static void listen(Listener next) {
        listener = next;
    }

    static boolean running() {
        return server != null;
    }

    /** Why the last start failed, or "". */
    static String error() {
        return error;
    }

    static void start(Context context, File folder) {
        Intent intent = new Intent(context, ServeService.class).putExtra(EXTRA_FOLDER, folder.getPath());
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ServeService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Up first: a service started in the foreground has five seconds to say so.
        startForeground(NOTIFICATION, notification(null));
        String folder = intent == null ? null : intent.getStringExtra(EXTRA_FOLDER);
        if (intent == null || ACTION_STOP.equals(intent.getAction()) || folder == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (server != null) server.stop();
        server = null;
        try {
            Server next = new Server(new File(folder), Server.PORT);
            next.start();
            server = next;
            error = "";
        } catch (IOException e) {
            error = e.getMessage() == null ? "Couldn't start" : e.getMessage();
            stopSelf();
            changed();
            return START_NOT_STICKY;
        }
        hold();
        startForeground(NOTIFICATION, notification(server.root()));
        changed();
        // Killed for memory, it comes back serving the same folder.
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        server = null;
        release();
        changed();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void changed() {
        if (listener != null) listener.changed();
    }

    private void hold() {
        if (wake == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "serve:serving");
            wake.setReferenceCounted(false);
        }
        wake.acquire();
        if (wifi == null) {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (manager != null) {
                wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "serve:serving");
                wifi.setReferenceCounted(false);
            }
        }
        if (wifi != null) wifi.acquire();
    }

    private void release() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
    }

    private Notification notification(File folder) {
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(
            new NotificationChannel(CHANNEL, "Serving", NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, ServeService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT);

        List<String> addresses = Addresses.local();
        String address = addresses.isEmpty() ? "No network" : MainActivity.url(addresses.get(0));
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getResources().getColor(R.color.primary))
            .setContentTitle(folder == null ? "Starting…" : "Serving " + folder.getName())
            .setContentText(address)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification), "Stop", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build();
    }
}
