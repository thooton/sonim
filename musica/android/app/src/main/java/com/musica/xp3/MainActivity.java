package com.musica.xp3;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.xp3.common.ui.CardGrant;

import java.util.List;

/**
 * Música: the songs in Musica on the SD card, each an MP3 with an SRT of the
 * same name, whose lyrics follow along as the song plays. Apple Music's look
 * (mockups/index.html, direction B) on the XP3's d-pad:
 *
 *   Library      Up/Down a song, OK plays it, Left/Right on the capsules.
 *   Now Playing  OK play/pause, Left/Right previous/next (held: seek),
 *                Down the lyrics, 4/6 back/ahead 5 s.
 *   Lyrics       Up/Down pick a line, OK play/pause or play from the
 *                picked line, Left this line again, Right the next line.
 *   Menu         the options sheet. Back goes up a level, and leaves
 *                from the library with the music still playing.
 */
public class MainActivity extends Activity implements SheetView.Host {

    private static final int ASK_STORAGE = 1;
    private static final int ASK_CARD = 2;
    private static final long NUDGE = 5000;
    /** How far each repeat of a held Left or Right seeks. */
    private static final long SCRUB_STEP = 1500;

    private final Handler handler = new Handler();
    private Library library;
    private Player player;
    private LibraryView libraryView;
    private PlayerView playerView;
    private SheetView sheet;
    private boolean playerShown;
    private boolean askedCard;
    private int heldKey;
    private boolean scrubbing;

    private final Library.Listener libraryChanged = this::showSongs;
    private final Player.Listener playerChanged = this::playerChanged;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (playerShown) playerView.invalidate();
            else libraryView.invalidate();
            if (sheet.isOpen()) sheet.invalidate();
            // The lyrics move on a line within a tenth of a second.
            handler.postDelayed(this, playerShown && playerView.lyricsShown() ? 100 : 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = Library.get(this);
        player = Player.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        FrameLayout root = new FrameLayout(this);
        libraryView = new LibraryView(this);
        playerView = new PlayerView(this);
        sheet = new SheetView(this, this);
        root.addView(libraryView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
        playerView.setVisibility(View.GONE);
        sheet.setVisibility(View.GONE);
        setContentView(root);
        bars();

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, ASK_STORAGE);
        }
        if (state != null && state.getBoolean("player") && player.song() != null) {
            showPlayer(true, false);
            playerView.showLyrics(state.getBoolean("lyrics"), false);
        } else if (getIntent().getBooleanExtra("now", false) && player.song() != null) {
            showPlayer(true, false);
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("player", playerShown);
        out.putBoolean("lyrics", playerView.lyricsShown());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("now", false) && player.song() != null && !playerShown) showPlayer(true, false);
    }

    @Override protected void onResume() {
        super.onResume();
        library.listen(libraryChanged);
        player.listen(playerChanged);
        showSongs();
        if (allowed()) library.load();
        handler.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        library.unlisten(libraryChanged);
        player.unlisten(playerChanged);
        handler.removeCallbacks(tick);
        player.save();
    }

    private boolean allowed() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // onResume runs next and loads the songs if it was allowed.
        showSongs();
    }

    private void showSongs() {
        List<Song> songs = library.songs();
        LibraryView.State state;
        if (!allowed()) state = LibraryView.State.NO_ACCESS;
        else if (!library.loaded()) state = LibraryView.State.LOADING;
        else state = songs.isEmpty() ? LibraryView.State.EMPTY : LibraryView.State.SONGS;
        if (library.loaded()) player.setLibrary(songs);
        libraryView.setSongs(songs, state);
    }

    private void playerChanged() {
        String problem = player.takeProblem();
        if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
        if (player.takeNeedsGrant()) askCard();
        if (player.song() == null && playerShown) showPlayer(false, true);
        libraryView.invalidate();
        playerView.invalidate();
        sheet.invalidate();
    }

    /** Android 8.1 may refuse to read what another app put on the card without the card grant. */
    private void askCard() {
        if (askedCard) return;
        askedCard = true;
        Intent ask = CardGrant.ask(this);
        if (ask == null) return;
        try {
            startActivityForResult(ask, ASK_CARD);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Can't read the SD card", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code == ASK_CARD && CardGrant.answered(this, result, data)) {
            library.load();
            player.retry();
        }
    }

    // ---- Screens

    private void showPlayer(boolean show, boolean animate) {
        if (show == playerShown) return;
        playerShown = show;
        bars();
        playerView.animate().cancel();
        if (show) {
            playerView.setVisibility(View.VISIBLE);
            playerView.snapLyrics();
            if (animate) {
                playerView.setTranslationY(playerView.getHeight() > 0 ? playerView.getHeight() : 320 * getResources().getDisplayMetrics().density);
                playerView.animate().translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(1.6f)).start();
            } else {
                playerView.setTranslationY(0);
            }
            playerView.requestFocus();
        } else {
            libraryView.focusPlaying();
            libraryView.setVisibility(View.VISIBLE);
            if (animate) {
                playerView.animate().translationY(playerView.getHeight()).setDuration(220)
                    .withEndAction(() -> {
                        playerView.setVisibility(View.GONE);
                        playerView.showLyrics(false, false);
                    }).start();
            } else {
                playerView.setVisibility(View.GONE);
                playerView.showLyrics(false, false);
            }
            libraryView.requestFocus();
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** Dark status bar icons on the white library, light ones on the player. */
    private void bars() {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!playerShown) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override public boolean lyricsShown() {
        return playerShown && playerView.lyricsShown();
    }

    @Override public void showLyrics(boolean on) {
        if (player.song() == null) return;
        if (!playerShown) {
            showPlayer(true, true);
            playerView.showLyrics(on, false);
        } else {
            playerView.showLyrics(on, true);
        }
    }

    @Override public void closeSheet() {
        sheet.close();
    }

    // ---- Keys

    @Override public boolean onKeyDown(int key, KeyEvent e) {
        if (sheet.isOpen()) return sheetKey(key) || super.onKeyDown(key, e);
        if (isMenu(key)) {
            return true;
        }
        if (playerShown) return playerKey(key, e) || super.onKeyDown(key, e);
        return libraryKey(key) || super.onKeyDown(key, e);
    }

    @Override public boolean onKeyUp(int key, KeyEvent e) {
        if (isMenu(key) && !e.isCanceled()) {
            if (sheet.isOpen()) sheet.close();
            else sheet.open(playerShown, libraryView.focusedSong());
            return true;
        }
        if (playerShown && heldKey == key) {
            heldKey = 0;
            if (scrubbing) {
                scrubbing = false;
                player.seek(playerView.position());
                playerView.setScrub(-1);
            } else if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                player.previous();
            } else {
                player.next();
            }
            return true;
        }
        return super.onKeyUp(key, e);
    }

    private static boolean isMenu(int key) {
        return key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_SOFT_LEFT;
    }

    private static boolean isOk(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
            || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private boolean sheetKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: sheet.move(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: sheet.move(1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: sheet.step(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: sheet.step(1); return true;
            case KeyEvent.KEYCODE_BACK: sheet.close(); return true;
            default:
                if (isOk(key)) {
                    sheet.step(1);
                    return true;
                }
                // Menu closes it on key up; nothing else reaches what is under it.
                return true;
        }
    }

    private boolean libraryKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: return libraryView.move(-1, 0);
            case KeyEvent.KEYCODE_DPAD_DOWN: return libraryView.move(1, 0);
            case KeyEvent.KEYCODE_DPAD_LEFT: return libraryView.move(0, -1);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return libraryView.move(0, 1);
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                player.toggle();
                return true;
            default:
                break;
        }
        if (!isOk(key)) return false;
        List<Song> songs = library.songs();
        int focus = libraryView.focus();
        if (focus == LibraryView.PLAY) player.playAll(songs);
        else if (focus == LibraryView.SHUFFLE) player.shuffleAll(songs);
        else if (libraryView.focusedSong() != null) player.play(libraryView.focusedSong());
        else return true;
        if (player.song() != null) showPlayer(true, true);
        return true;
    }

    private boolean playerKey(int key, KeyEvent e) {
        boolean lyrics = playerView.lyricsShown();
        switch (key) {
            case KeyEvent.KEYCODE_BACK:
                if (lyrics) playerView.showLyrics(false, true);
                else showPlayer(false, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (lyrics) playerView.movePick(1);
                else playerView.showLyrics(true, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (lyrics) playerView.movePick(-1);
                return true;
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_NUMPAD_4:
                nudge(-NUDGE);
                return true;
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_NUMPAD_6:
                nudge(NUDGE);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                player.toggle();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (lyrics) {
                    if (e.getRepeatCount() == 0) lineKey(key == KeyEvent.KEYCODE_DPAD_RIGHT);
                    return true;
                }
                if (e.getRepeatCount() == 0) {
                    heldKey = key;
                    scrubbing = false;
                } else if (heldKey == key) {
                    // Held: seek through the song, and settle there on release.
                    long at = scrubbing ? playerView.position() : player.position();
                    scrubbing = true;
                    long step = key == KeyEvent.KEYCODE_DPAD_RIGHT ? SCRUB_STEP : -SCRUB_STEP;
                    long dur = player.duration();
                    playerView.setScrub(Math.max(0, dur > 0 ? Math.min(dur - 1000, at + step) : at + step));
                }
                return true;
            default:
                break;
        }
        if (isOk(key)) {
            long from = lyrics ? playerView.takePick() : -1;
            if (from >= 0) {
                player.seek(from);
                if (!player.playing()) player.play();
            } else {
                player.toggle();
            }
            playerView.invalidate();
            return true;
        }
        return false;
    }

    private void nudge(long by) {
        playerView.clearPick();
        player.seek(player.position() + by);
    }

    /** Left: this line again (the one before, if it has only just begun). Right: the next line. */
    private void lineKey(boolean forward) {
        Lyrics l = player.lyrics();
        playerView.clearPick();
        if (l.isEmpty()) {
            nudge(forward ? NUDGE : -NUDGE);
            return;
        }
        long pos = player.position();
        int cur = l.at(pos);
        if (forward) {
            int next = l.lineAfter(cur);
            if (next >= 0) player.seek(l.rows.get(next).start);
            return;
        }
        int line = l.lineAtOrBefore(cur);
        if (line < 0) {
            player.seek(0);
            return;
        }
        if (pos - l.rows.get(line).start < 800 && l.lineAtOrBefore(line - 1) >= 0) {
            line = l.lineAtOrBefore(line - 1);
        }
        player.seek(l.rows.get(line).start);
    }
}
