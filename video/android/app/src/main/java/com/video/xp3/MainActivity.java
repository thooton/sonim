package com.video.xp3;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    private ProgressBar progress;
    private LinearLayout controls;
    private boolean playing;
    private Uri current;
    private String currentName = "";
    private int resumeAt = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (playing) {
                int at = resumeAt >= 0 ? resumeAt : video.getCurrentPosition();
                int total = video.getDuration();
                bar.setText(clock(at) + " / " + clock(total)
                        + (video.isPlaying() ? "" : "   PAUSED"));
                bar.setTextColor(video.isPlaying() ? Color.WHITE : 0xFFFFC857);
                progress.setMax(Math.max(1, total));
                progress.setProgress(at);
                handler.postDelayed(this, 500);
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        list = new ListView(this);
        list.setSelector(new InsetDrawable(focusCard(), dp(4), 0, dp(4), 0));
        list.setDivider(null);
        list.setOnItemClickListener((p, v, i, id) -> play(uris.get(i), names.get(i)));
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
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(0xB3000000);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF4FA3FF));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF555555));
        controls.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
        bar = new TextView(this);
        bar.setTextColor(Color.WHITE);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setTextSize(16);
        controls.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        player.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(player, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        video.setOnCompletionListener(m -> stop());
        video.setOnErrorListener((m, w, e) -> {
            Toast.makeText(this, "Can't play " + currentName, Toast.LENGTH_LONG).show();
            stop();
            return true;
        });

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        // A grant is picked up by onResume, which runs right after this.
        if (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED) {
            empty.setText("Allow storage access to see videos");
            empty.setVisibility(View.VISIBLE);
        }
    }

    private void load() {
        Uri selected = list.getSelectedItemPosition() >= 0
                && list.getSelectedItemPosition() < uris.size()
                ? uris.get(list.getSelectedItemPosition()) : null;
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
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int i, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(i, v, parent);
                t.setTextColor(Color.WHITE);
                t.setTextSize(15);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                t.setPadding(dp(10), 0, dp(10), 0);
                return t;
            }
        });
        empty.setText("No videos found");
        empty.setVisibility(uris.isEmpty() ? View.VISIBLE : View.GONE);
        list.requestFocus();
        int at = selected == null ? -1 : uris.indexOf(selected);
        if (at >= 0) list.setSelection(at);
    }

    private GradientDrawable focusCard() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1F3A5C);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(2), 0xFF4FA3FF);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void immersive(boolean on) {
        getWindow().getDecorView().setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void play(Uri uri, String name) {
        current = uri;
        currentName = name;
        resumeAt = -1;
        immersive(true);
        playing = true;
        list.setVisibility(View.GONE);
        player.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        video.setVideoURI(uri);
        video.setOnPreparedListener((MediaPlayer m) -> {
            if (resumeAt >= 0) {
                video.seekTo(resumeAt);
                resumeAt = -1;
            } else {
                video.start();
            }
        });
        handler.post(tick);
    }

    private void stop() {
        playing = false;
        resumeAt = -1;
        immersive(false);
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
        if (playing) {
            resumeAt = video.getCurrentPosition();
            video.pause();
        }
    }

    /** VideoView drops its player when the window goes away, so reopen the
     *  video paused at the place we left it. */
    @Override protected void onResume() {
        super.onResume();
        if (!playing && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            load();
        }
        if (playing && resumeAt >= 0 && current != null) {
            immersive(true);
            video.setVideoURI(current);
        }
    }

    private static String clock(int ms) {
        int s = Math.max(0, ms) / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
