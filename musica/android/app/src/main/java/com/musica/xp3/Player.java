package com.musica.xp3;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The one player, for the whole process: it outlives the activity, so
 * leaving the app leaves the song playing (PlayerService keeps the process
 * alive and shows the notification). Android's own MediaPlayer plays; the
 * position, speed, repeat and shuffle are kept for next time.
 */
final class Player implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener {

    interface Listener {
        void changed();
    }

    /** What the sleep timer can be set to, in minutes; -1 is the end of the song. */
    static final int[] SLEEP = {0, 15, 30, 60, -1};
    static final float[] SPEEDS = {1f, 0.9f, 0.75f, 0.5f};

    private static Player shared;

    static Player get(Context context) {
        if (shared == null) shared = new Player(context.getApplicationContext());
        return shared;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final SharedPreferences prefs;
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final MediaSession session;
    private final Queue queue = new Queue(new Random());

    private MediaPlayer mp;
    private ParcelFileDescriptor fd;
    private Song song;
    private Lyrics lyrics = Lyrics.NONE;
    private boolean prepared;
    private boolean wantPlay;
    private boolean resumeOnFocus;
    private long seekTo = -1;
    private float speed = 1f;
    private Queue.Repeat repeat = Queue.Repeat.OFF;
    private int sleep;
    private long sleepAt;
    private String problem;
    private boolean needsGrant;
    private boolean noisyListening;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            pause();
        }
    };

    private final Runnable sleeper = () -> {
        if (sleep > 0) {
            sleep = 0;
            pause();
        }
    };

    private final Runnable saver = new Runnable() {
        @Override public void run() {
            save();
            if (isPlaying()) main.postDelayed(this, 5000);
        }
    };

    private Player(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE);
        audio = context.getSystemService(AudioManager.class);
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(this::focusChanged, main)
            .build();
        speed = prefs.getFloat("speed", 1f);
        try {
            repeat = Queue.Repeat.valueOf(prefs.getString("repeat", "OFF"));
        } catch (IllegalArgumentException e) {
            repeat = Queue.Repeat.OFF;
        }
        queue.setShuffled(prefs.getBoolean("shuffle", false));
        session = new MediaSession(context, "Musica");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onStop() { pause(); }
            @Override public void onSeekTo(long pos) { seek(pos); }
        }, main);
    }

    MediaSession.Token token() {
        return session.getSessionToken();
    }

    void listen(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void unlisten(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Listener l : new ArrayList<>(listeners)) l.changed();
        publish();
        PlayerService.update(context);
    }

    // ---- What is playing

    Song song() {
        return song;
    }

    Lyrics lyrics() {
        return lyrics;
    }

    boolean isPlaying() {
        return mp != null && prepared && mp.isPlaying();
    }

    /** Whether the song is meant to be playing (it may still be loading). */
    boolean playing() {
        return wantPlay;
    }

    long position() {
        if (seekTo >= 0) return seekTo;
        if (mp == null || !prepared) return 0;
        return mp.getCurrentPosition();
    }

    long duration() {
        if (mp != null && prepared) {
            int d = mp.getDuration();
            if (d > 0) return d;
        }
        return song == null ? 0 : song.duration;
    }

    /** Why the song couldn't be played, once; null if it could. */
    String takeProblem() {
        String p = problem;
        problem = null;
        return p;
    }

    /** Whether a read was refused for want of the card grant; asked once. */
    boolean takeNeedsGrant() {
        boolean n = needsGrant;
        needsGrant = false;
        return n;
    }

    // ---- The library and the queue

    void setLibrary(List<Song> songs) {
        List<String> ids = new ArrayList<>();
        for (Song s : songs) ids.add(s.path);
        queue.setLibrary(ids);
        if (song == null) restore();
        else {
            Song fresh = Library.get(context).find(song.path);
            if (fresh != null) song = fresh;
        }
    }

    /** The last song, paused where it was left, so the mini player shows it. */
    private void restore() {
        Song last = Library.get(context).find(prefs.getString("path", null));
        if (last == null) return;
        queue.play(last.path);
        load(last, prefs.getLong("position", 0), false);
    }

    /** Plays `s` from the start, in library order from there (or shuffled). */
    void play(Song s) {
        if (song != null && song.path.equals(s.path)) {
            play();
            return;
        }
        queue.play(s.path);
        load(s, 0, true);
    }

    /** Shuffles the library and plays it. */
    void shuffleAll(List<Song> songs) {
        if (songs.isEmpty()) return;
        setShuffled(true);
        Song first = songs.get(new Random().nextInt(songs.size()));
        queue.play(first.path);
        queue.setShuffled(true);
        load(first, 0, true);
    }

    /** Plays the library from its first song, in order. */
    void playAll(List<Song> songs) {
        if (songs.isEmpty()) return;
        setShuffled(false);
        queue.play(songs.get(0).path);
        load(songs.get(0), 0, true);
    }

    boolean shuffled() {
        return queue.isShuffled();
    }

    void setShuffled(boolean on) {
        queue.setShuffled(on);
        prefs.edit().putBoolean("shuffle", on).apply();
        changed();
    }

    Queue.Repeat repeat() {
        return repeat;
    }

    void setRepeat(Queue.Repeat r) {
        repeat = r;
        prefs.edit().putString("repeat", r.name()).apply();
        changed();
    }

    void next() {
        String id = queue.next(repeat, true);
        Song s = Library.get(context).find(id);
        if (s != null) load(s, 0, wantPlay);
    }

    /** Back to the start of the song, or (in its first seconds) to the one before. */
    void previous() {
        if (position() > 3000 || song == null) {
            seek(0);
            return;
        }
        String id = queue.previous(repeat);
        Song s = Library.get(context).find(id);
        if (s != null) load(s, 0, wantPlay);
        else seek(0);
    }

    // ---- Playing

    private void load(Song s, long at, boolean play) {
        release();
        song = s;
        lyrics = readLyrics(s);
        wantPlay = play;
        seekTo = at > 0 ? at : -1;
        prepared = false;
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build());
        mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(this);
        mp.setOnCompletionListener(this);
        mp.setOnErrorListener(this);
        mp.setOnSeekCompleteListener(this);
        try {
            fd = Files.open(context, new File(s.path));
            mp.setDataSource(fd.getFileDescriptor());
            mp.prepareAsync();
        } catch (Files.NeedsGrant e) {
            needsGrant = true;
            fail(null);
            return;
        } catch (IOException | RuntimeException e) {
            fail("Can't play " + s.base());
            return;
        }
        if (play) takeFocus();
        save();
        changed();
    }

    private Lyrics readLyrics(Song s) {
        if (s.srt == null) return Lyrics.NONE;
        try {
            return Lyrics.parse(Files.read(context, new File(s.srt), 2_000_000));
        } catch (Files.NeedsGrant e) {
            needsGrant = true;
            return Lyrics.NONE;
        } catch (IOException e) {
            return Lyrics.NONE;
        }
    }

    private void fail(String why) {
        problem = why;
        wantPlay = false;
        release();
        changed();
    }

    /** Reopens the current song where it was, after the card grant came. */
    void retry() {
        if (song != null) load(song, position(), true);
    }

    private void release() {
        if (mp != null) {
            mp.release();
            mp = null;
        }
        if (fd != null) {
            try {
                fd.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
            fd = null;
        }
        prepared = false;
        noisy(false);
    }

    @Override public void onPrepared(MediaPlayer m) {
        if (m != mp) return;
        prepared = true;
        if (seekTo > 0) mp.seekTo(seekTo, MediaPlayer.SEEK_CLOSEST);
        else seekTo = -1;
        if (wantPlay) start();
        changed();
    }

    @Override public void onSeekComplete(MediaPlayer m) {
        if (m != mp) return;
        seekTo = -1;
        changed();
    }

    @Override public void onCompletion(MediaPlayer m) {
        if (m != mp) return;
        if (sleep == -1) {
            // The sleep timer was set for the end of this song.
            sleep = 0;
            wantPlay = false;
            mp.seekTo(0);
            changed();
            return;
        }
        String id = queue.next(repeat, false);
        Song s = Library.get(context).find(id);
        if (s == null) {
            wantPlay = false;
            mp.seekTo(0);
            noisy(false);
            changed();
        } else if (s.path.equals(song.path)) {
            mp.seekTo(0);
            start();
            changed();
        } else {
            load(s, 0, true);
        }
    }

    @Override public boolean onError(MediaPlayer m, int what, int extra) {
        if (m != mp) return true;
        fail("Can't play " + (song == null ? "this song" : song.base()));
        return true;
    }

    void toggle() {
        if (wantPlay) pause();
        else play();
    }

    void play() {
        if (song == null) return;
        if (mp == null) {
            load(song, position(), true);
            return;
        }
        wantPlay = true;
        takeFocus();
        if (prepared) start();
        changed();
    }

    private void start() {
        mp.start();
        if (speed != 1f) applySpeed();
        noisy(true);
        main.removeCallbacks(saver);
        main.postDelayed(saver, 5000);
    }

    void pause() {
        wantPlay = false;
        resumeOnFocus = false;
        if (mp != null && prepared && mp.isPlaying()) mp.pause();
        noisy(false);
        save();
        changed();
    }

    void seek(long ms) {
        if (song == null) return;
        long d = duration();
        long to = Math.max(0, d > 0 ? Math.min(d - 250, ms) : ms);
        seekTo = to;
        if (mp != null && prepared) mp.seekTo(to, MediaPlayer.SEEK_CLOSEST);
        changed();
    }

    // ---- Speed and the sleep timer

    float speed() {
        return speed;
    }

    void setSpeed(float s) {
        speed = s;
        prefs.edit().putFloat("speed", s).apply();
        // Setting a speed starts a paused MediaPlayer, so a paused one waits.
        if (isPlaying()) applySpeed();
        changed();
    }

    private void applySpeed() {
        try {
            PlaybackParams p = mp.getPlaybackParams();
            mp.setPlaybackParams(p.setSpeed(speed).setPitch(1f));
        } catch (RuntimeException e) {
            // Some files can't change speed; they play at 1×.
        }
    }

    /** The timer's setting: minutes, -1 for the end of the song, 0 off. */
    int sleep() {
        return sleep;
    }

    /** Minutes left on the timer, rounded up; 0 when it isn't counting. */
    int sleepLeft() {
        if (sleep <= 0) return 0;
        long left = sleepAt - SystemClock.elapsedRealtime();
        return (int) Math.max(1, (left + 59999) / 60000);
    }

    void setSleep(int minutes) {
        sleep = minutes;
        main.removeCallbacks(sleeper);
        if (minutes > 0) {
            sleepAt = SystemClock.elapsedRealtime() + minutes * 60000L;
            main.postDelayed(sleeper, minutes * 60000L);
        }
        changed();
    }

    // ---- The system: audio focus, headphones, the media session

    private void takeFocus() {
        audio.requestAudioFocus(focus);
        session.setActive(true);
    }

    private void focusChanged(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause();
                audio.abandonAudioFocusRequest(focus);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                boolean was = wantPlay;
                pause();
                resumeOnFocus = was;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mp != null) mp.setVolume(0.25f, 0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mp != null) mp.setVolume(1f, 1f);
                if (resumeOnFocus) {
                    resumeOnFocus = false;
                    play();
                }
                break;
            default:
                break;
        }
    }

    private void noisy(boolean on) {
        if (on == noisyListening) return;
        noisyListening = on;
        if (on) context.registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        else context.unregisterReceiver(noisy);
    }

    private void publish() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(actions)
            .setState(wantPlay ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position(),
                wantPlay ? speed : 0f)
            .build());
        if (song != null) {
            session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
                .build());
        }
    }

    void save() {
        if (song == null) return;
        prefs.edit().putString("path", song.path).putLong("position", position()).apply();
    }
}
