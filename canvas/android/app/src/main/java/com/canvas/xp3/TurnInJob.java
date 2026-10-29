package com.canvas.xp3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Detail;
import com.canvas.xp3.data.Draft;
import com.canvas.xp3.data.Sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finishes turn-ins made with no signal, once there is one.
 *
 * The phone runs this job when it reconnects, whether or not the app is
 * open, so a student can turn in on a bus, put the phone away, and have the
 * work arrive when the bus reaches signal. The draft stays on the phone,
 * marked as waiting, until Canvas has it.
 */
public final class TurnInJob extends JobService {

    private static final int JOB = 7;
    static final String CHANNEL = "turned-in";

    /** Turn-ins in flight in this process, so the app and the job never send one twice. */
    private static final Set<String> ACTIVE = new HashSet<>();

    static synchronized boolean claim(String key) {
        return ACTIVE.add(key);
    }

    static synchronized void release(String key) {
        ACTIVE.remove(key);
    }

    static synchronized boolean active(String key) {
        return ACTIVE.contains(key);
    }

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, TurnInJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build());
    }

    private volatile Thread worker;

    @Override
    public boolean onStartJob(final JobParameters params) {
        worker = new Thread(() -> {
            boolean again = send();
            jobFinished(params, again);
        }, "canvas-turn-in");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread running = worker;
        if (running != null) running.interrupt();
        return true;
    }

    /** Sends every waiting draft; true if any is still waiting afterwards. */
    private boolean send() {
        Backend backend = new Backend(getFilesDir());
        Sync.Result known = backend.cached();
        if (known == null || !backend.canvasReady()) return false;
        boolean waiting = false;
        for (Draft draft : backend.queued()) {
            Assignment item = null;
            for (Assignment candidate : known.work) {
                if (candidate.id.equals(draft.key)) item = candidate;
            }
            if (item == null || !claim(draft.key)) continue;
            try {
                Detail detail = backend.cachedDetail(item);
                List<String> extensions = detail == null ? new ArrayList<String>() : detail.extensions;
                backend.turnIn(draft, item, extensions, (piece, done, total) -> { });
                notify(this, item.title);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            } catch (IOException | RuntimeException e) {
                waiting = true;
            } finally {
                release(draft.key);
            }
        }
        return waiting;
    }

    /** One quiet line saying the work went in; nothing more. */
    static void notify(Context context, String title) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Turned in",
            NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), 0);
        manager.notify(title.hashCode(), new Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle("Turned in")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build());
    }
}
