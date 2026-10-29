package com.video.xp3;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A list of the videos on the phone and a full-screen player, d-pad driven.
 *  Playback is Android's own MediaPlayer, as VLC's fallback player is: no
 *  libraries. Keys follow VLC's: centre pauses, left/right seek 10 s,
 *  up/down seek a minute, back leaves. */
public class MainActivity extends Activity {
    private static final int SEEK = 10000;

    private final List<Uri> uris = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final Handler handler = new Handler();

    private FrameLayout root;
    private ListView list;
    private TextView empty;
    private FrameLayout player;
    private VideoView video;
    private TextView bar;
    private boolean playing;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (playing) {
                bar.setText(clock(video.getCurrentPosition()) + " / " + clock(video.getDuration())
                        + (video.isPlaying() ? "" : "  ||"));
                handler.postDelayed(this, 500);
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        list = new ListView(this);
        list.setOnItemClickListener((p, v, i, id) -> play(uris.get(i)));
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));

        empty = new TextView(this);
        empty.setTextColor(Color.WHITE);
        empty.setGravity(Gravity.CENTER);
        empty.setText("No videos found");
        empty.setVisibility(View.GONE);
        root.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        player = new FrameLayout(this);
        player.setBackgroundColor(Color.BLACK);
        player.setVisibility(View.GONE);
        video = new VideoView(this);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -1);
        vp.gravity = Gravity.CENTER;
        player.addView(video, vp);
        bar = new TextView(this);
        bar.setTextColor(Color.WHITE);
        bar.setBackgroundColor(0x99000000);
        bar.setPadding(8, 4, 8, 4);
        bar.setTextSize(12);
        player.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(player, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        video.setOnCompletionListener(m -> stop());
        video.setOnErrorListener((m, w, e) -> {
            Toast.makeText(this, "Can't play this video", Toast.LENGTH_SHORT).show();
            stop();
            return true;
        });

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            load();
        } else {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            load();
        } else {
            empty.setText("Allow storage access to see videos");
            empty.setVisibility(View.VISIBLE);
        }
    }

    private void load() {
        uris.clear();
        names.clear();
        try (Cursor c = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME},
                null, null, MediaStore.Video.Media.DATE_MODIFIED + " DESC")) {
            while (c != null && c.moveToNext()) {
                uris.add(ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
                names.add(c.getString(1));
            }
        }
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        empty.setVisibility(uris.isEmpty() ? View.VISIBLE : View.GONE);
        list.requestFocus();
    }

    private void play(Uri uri) {
        playing = true;
        list.setVisibility(View.GONE);
        player.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        video.setVideoURI(uri);
        video.setOnPreparedListener((MediaPlayer m) -> video.start());
        handler.post(tick);
    }

    private void stop() {
        playing = false;
        handler.removeCallbacks(tick);
        video.stopPlayback();
        player.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        list.requestFocus();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void seek(int by) {
        int to = Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + by));
        video.seekTo(to);
    }

    @Override public boolean onKeyDown(int key, KeyEvent e) {
        if (!playing) return super.onKeyDown(key, e);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (video.isPlaying()) video.pause(); else video.start();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: seek(-SEEK); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: seek(SEEK); return true;
            case KeyEvent.KEYCODE_DPAD_UP: seek(60000); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: seek(-60000); return true;
            case KeyEvent.KEYCODE_BACK: stop(); return true;
            default: return super.onKeyDown(key, e);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (playing) video.pause();
    }

    private static String clock(int ms) {
        int s = Math.max(0, ms) / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
