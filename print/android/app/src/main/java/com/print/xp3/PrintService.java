package com.print.xp3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.io.File;
import java.io.IOException;

/**
 * Runs the queue's jobs one after another in the foreground, with a
 * notification saying how the current one is going, so that Hide (or
 * shutting the phone) doesn't stop a job. Stops itself when the queue is
 * empty.
 */
public final class PrintService extends Service {

    private static final String CHANNEL = "printing";
    private static final String DONE_CHANNEL = "printed";
    private static final int NOTIFICATION = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    static void wake(Context context) {
        context.startForegroundService(new Intent(context, PrintService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION, notification(null));
        if (worker == null) {
            hold();
            worker = new Thread(this::work, "print");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }

    /** On the worker: take the next job from the main thread, run it, repeat. */
    private void work() {
        while (true) {
            final Queue.Entry[] next = new Queue.Entry[1];
            runOnMain(() -> {
                next[0] = Queue.next();
                if (next[0] != null) {
                    next[0].state = Queue.RUNNING;
                    Queue.changed(next[0]);
                }
            });
            if (next[0] == null) break;
            run(next[0]);
        }
        main.post(() -> {
            worker = null;
            // Something queued while the worker was finishing gets a new one.
            if (Queue.next() != null) {
                wake(this);
                return;
            }
            stopForeground(true);
            stopSelf();
        });
    }

    private void runOnMain(Runnable task) {
        final Object done = new Object();
        final boolean[] ran = {false};
        main.post(() -> {
            task.run();
            synchronized (done) {
                ran[0] = true;
                done.notifyAll();
            }
        });
        synchronized (done) {
            while (!ran[0]) {
                try {
                    done.wait();
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void run(final Queue.Entry entry) {
        Printer printer = entry.printer;
        // What the printer can do is needed to choose how to send it.
        boolean probe;
        synchronized (printer) {
            probe = printer.ippPort > 0 && printer.ipp == null;
        }
        if (probe) {
            try {
                Probe.ipp(printer);
            } catch (IOException e) {
                // The job says so if nothing else gets through.
            }
        }
        Probe.pjl(printer);
        File spool = new File(getCacheDir(), "spool");
        spool.mkdirs();
        PdfDocument document;
        try {
            document = new PdfDocument(entry.file);
        } catch (IOException e) {
            finish(entry, new Job.Status(Job.FAILED, e.getMessage(), -1, false));
            return;
        }
        try {
            final Job.Status[] last = new Job.Status[1];
            Job job = new Job(printer, entry.options, document, entry.file, spool, entry.title, status -> {
                last[0] = status;
                main.post(() -> update(entry, status));
            });
            entry.job = job;
            job.run();
            finish(entry, last[0]);
        } finally {
            document.close();
        }
    }

    private void finish(final Queue.Entry entry, final Job.Status status) {
        main.post(() -> {
            if (status != null) entry.status = status;
            int phase = status == null ? Job.FAILED : status.phase;
            entry.state = phase == Job.DONE ? Queue.DONE : phase == Job.CANCELED ? Queue.CANCELED : Queue.FAILED;
            entry.finished = System.currentTimeMillis();
            if (entry.state == Queue.DONE) accepted(entry);
            Queue.changed(entry);
            if (entry.state == Queue.FAILED) problem(entry);
        });
    }

    private void update(Queue.Entry entry, Job.Status status) {
        if (!entry.active()) return;
        entry.status = status;
        if (status.phase >= Job.PRINTING) accepted(entry);
        Queue.changed(entry);
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.notify(NOTIFICATION, notification(entry));
    }

    /** The printer has the job: buzz once, if that's wanted. */
    private void accepted(Queue.Entry entry) {
        if (entry.accepted) return;
        entry.accepted = true;
        if (!Store.get(this).vibrate()) return;
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void channels() {
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Printing", NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel(DONE_CHANNEL, "Printing problems", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private Notification notification(Queue.Entry entry) {
        channels();
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_printer)
            .setColor(Look.BLUE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false);
        if (entry == null) return builder.setContentTitle("Printing").build();
        Job.Status status = entry.status;
        builder.setContentTitle(entry.title)
            .setContentText(status == null ? "Waiting" : status.text)
            .setContentIntent(PendingIntent.getActivity(this, entry.id, Queue.open(this, entry), PendingIntent.FLAG_UPDATE_CURRENT));
        if (status != null && status.percent >= 0 && status.phase < Job.PRINTING) builder.setProgress(100, status.percent, false);
        return builder.build();
    }

    /** A failed job stays news after the service is gone. */
    private void problem(Queue.Entry entry) {
        channels();
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.notify(100 + entry.id, new Notification.Builder(this, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_alert)
            .setColor(Look.ERR)
            .setContentTitle(entry.title + " didn't print")
            .setContentText(entry.status == null ? "" : entry.status.text)
            .setContentIntent(PendingIntent.getActivity(this, entry.id, Queue.open(this, entry), PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build());
    }

    private void hold() {
        if (wake == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "print:printing");
            wake.setReferenceCounted(false);
        }
        wake.acquire(60 * 60 * 1000L);
        if (wifi == null) {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (manager != null) {
                wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "print:printing");
                wifi.setReferenceCounted(false);
            }
        }
        if (wifi != null) wifi.acquire();
    }

    private void release() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
    }
}
