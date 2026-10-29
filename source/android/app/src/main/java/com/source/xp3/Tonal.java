package com.source.xp3;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.source.xp3.net.Music;
import com.xp3.common.Login;
import com.xp3.common.ui.FaceSpan;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;
import com.xp3.common.ui.Logins;

/**
 * What every screen of Source shares: the Tonal look (DESIGN.md, "Source:
 * Tonal"), its dark theme, the command center login, and watching
 * Download. The dark theme is the platform's night resources (values-night),
 * switched on for this app alone, since the phone has no dark setting of
 * its own.
 */
abstract class Tonal extends BaseActivity implements Download.Listener {

    static final String PREFS = "source";

    /**
     * DM Sans (res/font), in the weights the proposal's type scale uses:
     * regular for supporting text, Medium for tabs at rest, SemiBold for
     * labels, Bold for titles. Loaded by the first screen, before any view.
     */
    static Typeface FONT = Typeface.SANS_SERIF;
    static Typeface MEDIUM = Typeface.SANS_SERIF;
    static Typeface SEMIBOLD = Typeface.DEFAULT_BOLD;
    static Typeface BOLD = Typeface.DEFAULT_BOLD;
    private static boolean fontsLoaded;

    /** The drawn covers' tones, with the ink of their initials. */
    static final int[] COVERS = {R.color.cover_1, R.color.cover_2, R.color.cover_3, R.color.cover_4, R.color.cover_5, R.color.cover_6};
    static final int[] INKS = {R.color.cover_1_ink, R.color.cover_2_ink, R.color.cover_3_ink, R.color.cover_4_ink, R.color.cover_5_ink, R.color.cover_6_ink};

    protected Login login = Login.blank();

    private static final int ASK_STORAGE = 1;
    private static final int ASK_CARD = 2;
    /** What waits for storage access or the SD card's grant. */
    private Runnable pendingWrite;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (dark(base)) {
            Configuration night = new Configuration();
            int mode = base.getResources().getConfiguration().uiMode;
            night.uiMode = mode & ~Configuration.UI_MODE_NIGHT_MASK | Configuration.UI_MODE_NIGHT_YES;
            applyOverrideConfiguration(night);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        lightStatusBar(!dark(this));
        loadFonts();
        Download.init(this);
        login = Logins.load(prefs(), Bundled.REVISION, Bundled.SERVER, Bundled.TOKEN);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Another screen may have changed it.
        login = Login.parse(prefs().getString("login", ""));
        Download.listen(this);
        changed();
    }

    @Override
    protected void onStop() {
        Download.unlisten(this);
        super.onStop();
    }

    private void loadFonts() {
        if (fontsLoaded) return;
        FONT = getResources().getFont(R.font.dm_sans);
        MEDIUM = getResources().getFont(R.font.dm_sans_medium);
        SEMIBOLD = getResources().getFont(R.font.dm_sans_semibold);
        BOLD = getResources().getFont(R.font.dm_sans_bold);
        fontsLoaded = true;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    protected final SharedPreferences prefs() {
        return prefs(this);
    }

    static boolean dark(Context context) {
        return prefs(context).getBoolean("dark", false);
    }

    /**
     * The status bar's icons dark on the light theme and light on the dark.
     * The theme sets this only on a new window, and recreate() keeps the old
     * one, so it is set here as well.
     */
    private void lightStatusBar(boolean on) {
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        decor.setSystemUiVisibility(on ? flags | light : flags & ~light);
    }

    /** Switches the theme, and redraws this screen in it. */
    protected final void setDark(boolean on) {
        prefs().edit().putBoolean("dark", on).apply();
        recreate();
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (perhaps typed in from Options) is kept.
     */

    /**
     * Runs `then`, a download, once Source may save onto the SD card: storage
     * access, which reads the card, then Android's grant to write on it
     * (CardStore), each asked for once.
     */
    final void whenWritable(Runnable then) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingWrite = then;
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE);
            return;
        }
        if (!CardStore.ready(this)) {
            Intent ask = CardStore.ask(this);
            if (ask != null) {
                try {
                    pendingWrite = then;
                    startActivityForResult(ask, ASK_CARD);
                    return;
                } catch (ActivityNotFoundException e) {
                    pendingWrite = null;
                }
            }
        }
        // No card, or no way to ask: the download says why it can't save.
        then.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != ASK_STORAGE) return;
        Runnable then = pendingWrite;
        pendingWrite = null;
        if (then == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) whenWritable(then);
        else note("No storage access");
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != ASK_CARD) return;
        Runnable then = pendingWrite;
        pendingWrite = null;
        if (then == null) return;
        if (CardStore.answered(this, result, data)) then.run();
        else note("Source can't save to the SD card unless it is allowed");
    }

    /** A passing word, as a toast. */
    final void note(String text) {
        toast(text);
    }

    /** A tinted icon, `size` dp square, for a TextView's side. */
    protected final Drawable icon(int id, int color, int size) {
        return icon(this, id, color, size);
    }

    static Drawable icon(Context context, int id, int color, int size) {
        Drawable drawn = context.getDrawable(id).mutate();
        drawn.setTint(context.getColor(color));
        drawn.setBounds(0, 0, Ui.dp(context, size), Ui.dp(context, size));
        return drawn;
    }

    /** As text(), at a size between whole sp. */
    protected final TextView line(CharSequence value, float sp, int color) {
        TextView view = text(value, 0, color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    /** A text at one of the dimens' sizes (R.dimen.text_row, text_meta), in a colour resource. */
    static TextView sized(Context context, int size, int color) {
        TextView view = new TextView(context);
        view.setTextColor(context.getColor(color));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(size));
        view.setIncludeFontPadding(false);
        return view;
    }

    /** A horizontal LinearLayout, its children placed by `gravity`. */
    protected final LinearLayout row(int gravity) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(gravity);
        return row;
    }

    /** What a tab or kind is called: Song, Album or Book. */
    static String label(String kind) {
        if (Music.ALBUM.equals(kind)) return "Album";
        if (Download.BOOK.equals(kind)) return "Book";
        return "Song";
    }

    /** A tab's or kind's icon. */
    static int glyph(String kind) {
        if (Music.ALBUM.equals(kind)) return R.drawable.ic_album;
        if (Download.BOOK.equals(kind)) return R.drawable.ic_book;
        return R.drawable.ic_music_note;
    }

    /**
     * Scrolls a list just far enough to show row `at` whole, at once. A
     * smooth scroll started on every press, while the rows were being bound
     * again, would sometimes run from the top back down to it.
     */
    protected static void keepInView(final ListView list, final int at) {
        list.post(() -> {
            if (at < 0 || at >= list.getCount()) return;
            int first = list.getFirstVisiblePosition();
            View row = list.getChildAt(at - first);
            int height = list.getHeight();
            if (row != null && row.getTop() >= 0 && row.getBottom() <= height) return;
            if (at <= first) {
                list.setSelectionFromTop(at, 0);
            } else {
                View any = row != null ? row : list.getChildAt(0);
                int rowHeight = any == null ? 0 : any.getHeight();
                list.setSelectionFromTop(at, Math.max(0, height - rowHeight));
            }
        });
    }
}
