package com.musica.xp3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.IBinder;

/**
 * Keeps the process alive while a song plays, with the notification Android
 * requires for that (which doubles as the lock screen's controls). Paused,
 * it lets go of the foreground, so the system may end it.
 */
public class PlayerService extends Service {

    private static final String CHANNEL = "playback";
    private static final int ID = 1;
    private static final String TOGGLE = "com.musica.xp3.TOGGLE";
    private static final String NEXT = "com.musica.xp3.NEXT";
    private static final String PREVIOUS = "com.musica.xp3.PREVIOUS";

    private static boolean running;

    /** Brings the notification up to date with the player, starting the service if it plays. */
    static void update(Context context) {
        Player p = Player.get(context);
        if (p.song() == null) {
            if (running) context.stopService(new Intent(context, PlayerService.class));
            return;
        }
        if (p.playing()) {
            context.startForegroundService(new Intent(context, PlayerService.class));
        } else if (running) {
            context.startService(new Intent(context, PlayerService.class));
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Player p = Player.get(this);
        String action = intent == null ? null : intent.getAction();
        if (TOGGLE.equals(action)) p.toggle();
        else if (NEXT.equals(action)) p.next();
        else if (PREVIOUS.equals(action)) p.previous();
        Song song = p.song();
        if (song == null) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // startForegroundService must be answered with startForeground, playing or not.
        startForeground(ID, notification(p, song));
        if (!p.playing()) stopForeground(false);
        return START_NOT_STICKY;
    }

    private Notification notification(Player p, Song song) {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("now", true);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOngoing(p.playing())
            .setColor(Look.RED)
            .addAction(action(R.drawable.ic_rew, "Previous", PREVIOUS))
            .addAction(p.playing() ? action(R.drawable.ic_pause, "Pause", TOGGLE) : action(R.drawable.ic_play, "Play", TOGGLE))
            .addAction(action(R.drawable.ic_ff, "Next", NEXT))
            .setStyle(new Notification.MediaStyle().setMediaSession(p.token()).setShowActionsInCompactView(0, 1, 2));
        Bitmap art = Art.get(this).cover(song, 64, null);
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    private Notification.Action action(int icon, String title, String what) {
        Intent i = new Intent(this, PlayerService.class).setAction(what);
        PendingIntent pi = PendingIntent.getService(this, what.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, pi).build();
    }

    @Override public void onDestroy() {
        running = false;
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
