package com.teslacharge.xp3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.teslacharge.xp3.data.ChargingRepository;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Watches the running charging session and keeps it on the notification shade.
 *
 * The Activity used to do this itself, on a Handler that stopped the moment the
 * screen slept, which is most of a charge: you could not put the phone in your
 * pocket and still be told what it was costing. A foreground service outlives
 * the screen, so this owns the poll outright and the Activity reads what it
 * finds rather than asking in parallel.
 *
 * The notification is the whole point of the thing, so it is never a silent
 * placeholder: it carries the charge level, the energy delivered and the running
 * cost, and tapping it returns to the app.
 */
public final class ChargingService extends Service {
    private static final String CHANNEL = "charging_session";
    private static final int NOTIFICATION = 1;
    static final String EXTRA_SESSION_ID = "sessionId";

    /** With someone on the Session screen, close enough to feel live while a
     *  stall authorizes and the car starts drawing. */
    private static final long WATCHED_POLL_MS = 4_000L;
    /** In a pocket, where the only reader is the notification and nothing on it
     *  moves fast enough to be worth more than this. */
    private static final long BACKGROUND_POLL_MS = 30_000L;
    private static final long FAILURE_POLL_MS = 8_000L;
    /** Roughly two and a half minutes of failures. A charge outlasts a tunnel,
     *  so a gap is worth waiting out, but a session that can never be read
     *  again must not leave an ongoing notification pinned forever. */
    private static final int MAX_FAILURES = 20;

    interface Listener {
        void onSessionUpdate(JSONObject session, String error);
    }

    // Main-thread only: registered from onResume, cleared from onPause, and
    // written from callbacks this service posts to the main looper.
    private static Listener listener;
    private static JSONObject latest;
    private static String latestError;
    private static ChargingService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService poller;
    /** The next poll, held so that a change of pace can cancel it rather than
     *  leave a second chain of them running alongside the first. */
    private ScheduledFuture<?> pending;
    private ChargingRepository repository;
    private NotificationManager notifications;
    // Written on the main thread, read by the poller: both have to be volatile
    // or the poll can keep working from a session id, or a liveness flag, that
    // was replaced while it was in flight.
    private volatile String sessionId;
    private volatile boolean stopped;
    private int consecutiveFailures;

    static void start(Context context, String sessionId) {
        if (sessionId == null || sessionId.length() == 0) return;
        context.startService(
            new Intent(context, ChargingService.class).putExtra(EXTRA_SESSION_ID, sessionId)
        );
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ChargingService.class));
    }

    /** Registers the screen currently displaying the session. Also speeds the
     *  poll up: someone is looking at it. */
    static void observe(Listener next) {
        listener = next;
        // The session screen has just opened on a reading that may be most of
        // the background interval old. Ask again now rather than making someone
        // watch a stale figure until the slow clock next comes round.
        ChargingService running = instance;
        if (running != null) running.schedule(0);
    }

    static void unobserve(Listener previous) {
        if (listener == previous) listener = null;
    }

    /** The most recent reading, for a screen that has just come back and wants
     *  to draw something before the next poll lands. */
    static JSONObject latest() {
        return latest;
    }

    static String latestError() {
        return latestError;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        repository = ChargingRepository.get(this);
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        poller = Executors.newSingleThreadScheduledExecutor();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String id = intent == null
            ? sessionId
            : intent.getStringExtra(EXTRA_SESSION_ID);
        if (id == null || id.length() == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Already on this session. Starting is how the Activity says "a session
        // is running", which it says on every resume, so it has to be free to
        // repeat.
        if (id.equals(sessionId)) return START_REDELIVER_INTENT;

        sessionId = id;
        consecutiveFailures = 0;
        startForeground(NOTIFICATION, notification(latest, null));
        schedule(0);
        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        if (instance == this) instance = null;
        poller.shutdownNow();
        // Cleared so a screen resuming later cannot mistake the last reading of
        // a finished charge for a running one.
        latest = null;
        latestError = null;
        super.onDestroy();
    }

    private void createChannel() {
        // Low importance: this is a status line, not an interruption. It belongs
        // in the shade for as long as the charge runs and should never make a
        // sound or push itself over what is on screen.
        NotificationChannel channel = new NotificationChannel(
            CHANNEL,
            "Charging session",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows the charge in progress.");
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        notifications.createNotificationChannel(channel);
    }

    /** Main thread only, which is what makes {@link #pending} safe to replace
     *  without a lock. */
    private void schedule(long delayMs) {
        if (stopped) return;
        if (pending != null) pending.cancel(false);
        try {
            pending = poller.schedule(poll, delayMs, TimeUnit.MILLISECONDS);
        } catch (Exception shuttingDown) {
            /* The service is on its way out; nothing left to poll for. */
        }
    }

    private final Runnable poll = () -> {
        if (stopped) return;
        JSONObject session = null;
        String error = null;
        try {
            session = repository.session(sessionId);
        } catch (Exception failure) {
            error = failure.getMessage();
        }
        final JSONObject result = session;
        final String message = error;
        main.post(() -> publish(result, message));
    };

    private void publish(JSONObject session, String error) {
        if (stopped) return;
        if (session != null) {
            latest = session;
            latestError = null;
            consecutiveFailures = 0;
        } else {
            latestError = error;
            consecutiveFailures++;
        }
        if (listener != null) listener.onSessionUpdate(latest, latestError);

        // The session is over: the notification has nothing left to report and
        // an ongoing one cannot be dismissed by hand, so it goes with it.
        if (session != null && !session.optBoolean("active")) {
            finish();
            return;
        }
        if (consecutiveFailures >= MAX_FAILURES) {
            finish();
            return;
        }
        notifications.notify(NOTIFICATION, notification(latest, latestError));
        schedule(
            session == null
                ? FAILURE_POLL_MS
                : listener != null ? WATCHED_POLL_MS : BACKGROUND_POLL_MS
        );
    }

    private void finish() {
        stopped = true;
        stopForeground(true);
        stopSelf();
    }

    private Notification notification(JSONObject session, String error) {
        // Behaves like the launcher icon rather than a fresh start: with
        // MainActivity marked singleTop this returns to the app as it was left,
        // on whichever tab, instead of building a second copy of it.
        Intent open = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
        PendingIntent tap = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setColor(getResources().getColor(R.color.primary)).setContentTitle(
                session == null
                    ? "Charging session"
                    : session.optString("statusText", "Charging session")
            ).setContentText(summary(session, error)).setContentIntent(tap).setOngoing(true)
            .setOnlyAlertOnce(true).setShowWhen(false).build();
    }

    /** Charge level, energy and cost, in that order, dropping whichever of them
     *  the session has not reported yet rather than showing a zero for it. */
    private static String summary(JSONObject session, String error) {
        if (session == null) {
            return error == null ? "Connecting…" : "Reconnecting…";
        }
        List<String> parts = new ArrayList<>();
        if (!session.isNull("stateOfCharge")) {
            parts.add(session.optInt("stateOfCharge") + "%");
        }
        parts.add(String.format(Locale.US, "%.2f kWh", session.optDouble("energyKwh", 0)));
        parts.add(
            SiteAdapter.money(session.optDouble("cost", 0), session.optString("currency", "USD"))
        );
        StringBuilder line = new StringBuilder();
        for (String part : parts) {
            if (line.length() > 0) line.append("  ·  ");
            line.append(part);
        }
        // Said on the notification as well as in the app: numbers that have
        // quietly stopped advancing are worse than numbers labelled as stale.
        if (error != null) line.append("  ·  reconnecting…");
        return line.toString();
    }
}
