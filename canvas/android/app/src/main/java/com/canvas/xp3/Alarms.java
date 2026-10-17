package com.canvas.xp3;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Prefs;
import com.canvas.xp3.data.Reminders;
import com.canvas.xp3.data.Sync;

import java.util.ArrayList;
import java.util.List;

/**
 * The reminders the student chose in Settings, and nothing else.
 *
 * Only the next one is ever set. When it fires it posts one line naming the
 * work and its date, then sets the one after, from the work list as it is
 * then: anything turned in since is no longer mentioned.
 */
public final class Alarms extends BroadcastReceiver {

    static final String CHANNEL = "reminders";

    static void schedule(Context context, Backend backend) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, new Intent(context, Alarms.class),
            PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(pending);
        Reminders.Next next = next(backend);
        if (next == null) return;
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending);
    }

    private static Reminders.Next next(Backend backend) {
        Prefs prefs = backend.prefs();
        Sync.Result known = backend.cached();
        if (known == null) return null;
        List<Assignment> open = new ArrayList<>();
        for (Assignment item : known.work) {
            if (!known.checked.contains(item.assignmentId)) open.add(item);
        }
        return Reminders.next(System.currentTimeMillis(), prefs.reminders, open, prefs.quiet);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Backend backend = new Backend(context.getFilesDir());
        // A minute's grace: the alarm may fire a moment early or late, and the
        // reminder it was set for is the one due now.
        Prefs prefs = backend.prefs();
        Sync.Result known = backend.cached();
        if (known != null) {
            List<Assignment> open = new ArrayList<>();
            for (Assignment item : known.work) {
                if (!known.checked.contains(item.assignmentId)) open.add(item);
            }
            Reminders.Next due = Reminders.next(System.currentTimeMillis() - 60000, prefs.reminders, open,
                prefs.quiet);
            if (due != null && Math.abs(due.at - System.currentTimeMillis()) < 15 * 60000) post(context, due);
        }
        schedule(context, backend);
    }

    private static void post(Context context, Reminders.Next due) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Reminders",
            NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, 1, new Intent(context, MainActivity.class), 0);
        manager.notify(1, new Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(Reminders.title(due))
            .setContentText(Reminders.text(due))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build());
    }

    /** Sets the next reminder again after the phone restarts. */
    public static final class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            schedule(context, new Backend(context.getFilesDir()));
            TurnInJob.schedule(context);
        }
    }
}
