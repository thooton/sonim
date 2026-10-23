package com.canvas.xp3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Draft;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Record audio (D5), for spoken answers, monologues and language practice.
 *
 * A plain timer counts up, never down, and the recording goes straight into
 * the draft's folder on the phone, so nothing waits on the network while the
 * student speaks. AAC in an .m4a file, which Canvas plays back in the browser.
 */
final class RecorderScreen extends Screen {

    private final Assignment work;
    private MediaRecorder recorder;
    private File file;
    private boolean paused;
    private long startedAt;
    private long banked;
    private TextView time;
    private Wave wave;
    private TextView status;
    private final Runnable tick = this::tick;

    RecorderScreen(MainActivity app, Assignment work) {
        super(app);
        this.work = work;
    }

    @Override
    String center() {
        return "Stop";
    }

    @Override
    List<Sheet.Choice> options() {
        List<Sheet.Choice> out = new ArrayList<>();
        out.add(new Sheet.Choice(paused ? "Resume" : "Pause", R.drawable.ic_pause, null, this::pause));
        out.add(new Sheet.Choice("Discard", R.drawable.ic_undo, null, this::discard));
        return out;
    }

    @Override
    boolean globalOptions() {
        return false;
    }

    private void discard() {
        Sheet.ask(app, "Discard this recording?", "It's deleted from this phone.", "Discard", "Keep recording",
            () -> {
                release();
                if (file != null) file.delete();
                app.pop();
            });
    }

    @Override
    boolean onKey(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        if (com.xp3.common.ui.Ui.isOk(code)) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) stop();
            return true;
        }
        return false;
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        root.addView(parts.nav("Your work", name()), Views.wide());
        LinearLayout middle = v.column();
        middle.setGravity(Gravity.CENTER);
        v.pad(middle, 12, 0, 12, 0);
        LinearLayout label = v.row();
        label.setGravity(Gravity.CENTER);
        label.addView(v.dot(look.course(0), 7));
        status = v.label(paused ? "Paused" : "Recording");
        label.addView(status, v.margins(Views.wrap(), 5, 0, 0, 0));
        middle.addView(label, Views.wrap());
        time = v.text(clock(elapsed()), 36, look.heavy, look.ink);
        time.setLetterSpacing(-0.03f);
        Views.tabular(time);
        middle.addView(time, v.margins(Views.wrap(), 0, 7, 0, 7));
        wave = new Wave(app, look);
        middle.addView(wave, new LinearLayout.LayoutParams(-1, v.dp(40)));
        TextView hint = v.text("Press OK to stop.", 9.5f,
            look.regular, look.muted);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.3f);
        middle.addView(hint, v.margins(Views.wide(), 0, 7, 0, 0));
        root.addView(middle, Views.tall());
        return root;
    }

    private String name() {
        Draft draft = app.backend.draft(work.id);
        int takes = 1;
        for (Draft.Piece piece : draft.pieces) if (Draft.AUDIO.equals(piece.kind)) takes++;
        return "Recording " + takes;
    }

    @Override
    void shown() {
        if (recorder == null && file == null) begin();
    }

    private void begin() {
        Draft draft = app.backend.draft(work.id);
        file = new File(app.backend.draftFolder(draft), "audio-" + System.currentTimeMillis() + ".m4a");
        MediaRecorder next = new MediaRecorder();
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC);
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            next.setAudioSamplingRate(22050);
            next.setAudioEncodingBitRate(48000);
            next.setOutputFile(file.getPath());
            next.prepare();
            next.start();
        } catch (Exception e) {
            next.release();
            file.delete();
            file = null;
            app.say("The microphone couldn't be opened.");
            app.pop();
            return;
        }
        recorder = next;
        startedAt = SystemClock.elapsedRealtime();
        app.main.post(tick);
    }

    private long elapsed() {
        return banked + (recorder != null && !paused ? SystemClock.elapsedRealtime() - startedAt : 0);
    }

    private void tick() {
        if (recorder == null) return;
        time.setText(clock(elapsed()));
        if (!paused) {
            int level;
            try {
                level = recorder.getMaxAmplitude();
            } catch (RuntimeException e) {
                level = 0;
            }
            wave.add(Math.min(1f, (float) Math.sqrt(level / 32767f)));
        }
        app.main.postDelayed(tick, 120);
    }

    private void pause() {
        if (recorder == null) return;
        try {
            if (paused) {
                recorder.resume();
                startedAt = SystemClock.elapsedRealtime();
                paused = false;
            } else {
                recorder.pause();
                banked = elapsed();
                paused = true;
            }
        } catch (RuntimeException e) {
            return;
        }
        status.setText(paused ? "Paused" : "Recording");
        app.refreshKeys();
    }

    private void stop() {
        if (recorder == null) return;
        long length = elapsed();
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // Stopped before any sound was captured.
            ok = false;
        }
        release();
        if (!ok || length < 500) {
            if (file != null) file.delete();
            app.say("Nothing was recorded.");
            app.pop();
            return;
        }
        app.finishWith(Draft.Piece.file("a" + System.currentTimeMillis(), Draft.AUDIO, name(), file.getName(),
            "audio/mp4", file.length(), length));
    }

    private void release() {
        app.main.removeCallbacks(tick);
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
    }

    @Override
    boolean onBack() {
        if (recorder != null) {
            stop();
            return true;
        }
        return false;
    }

    @Override
    void closed() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                // Nothing usable.
            }
            release();
        }
    }

    @Override
    boolean globalOptions() {
        return false;
    }

    /** "0:42", "12:05". */
    static String clock(long milliseconds) {
        long seconds = milliseconds / 1000;
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }

    private static MediaPlayer playing;

    /** Plays a recording back from the phone; pressing again stops it. */
    static void play(MainActivity app, File file) {
        if (playing != null) {
            playing.release();
            playing = null;
            return;
        }
        try {
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(file.getPath());
            player.setOnCompletionListener(done -> {
                done.release();
                if (playing == done) playing = null;
            });
            player.prepare();
            player.start();
            playing = player;
            app.say("Playing.");
        } catch (Exception e) {
            app.say("This recording couldn't be played.");
        }
    }

    /** Bars of the last few seconds' loudness; the newest on the right, the rest faint. */
    private static final class Wave extends View {
        private final Look look;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Float> levels = new ArrayList<>();

        Wave(Context context, Look look) {
            super(context);
            this.look = look;
        }

        void add(float level) {
            levels.add(Float.valueOf(level));
            while (levels.size() > 40) levels.remove(0);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int bars = 40;
            float gap = 2 * getResources().getDisplayMetrics().density;
            float width = (getWidth() - gap * (bars - 1)) / bars;
            float middle = getHeight() / 2f;
            for (int i = 0; i < bars; i++) {
                int index = i - (bars - levels.size());
                float level = index >= 0 ? levels.get(index).floatValue() : 0f;
                float half = Math.max(width / 2f, level * getHeight() / 2f);
                paint.setColor(index >= 0 ? look.ink : look.line);
                float left = i * (width + gap);
                canvas.drawRoundRect(left, middle - half, left + width, middle + half, width / 2, width / 2, paint);
            }
        }
    }
}
