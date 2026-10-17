package com.source.xp3;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.TextView;
import com.source.xp3.net.Music;
import com.xp3.common.Login;
import com.xp3.common.ui.BaseActivity;

/**
 * What every screen of Source shares: the Tonal look (DESIGN.md, "Source:
 * Tonal"), its dark theme, the soft-key bar, the command center login, and
 * watching Download. The dark theme is the platform's night resources
 * (values-night), switched on for this app alone, since the phone has no
 * dark setting of its own.
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
    private TextView keyLeft;
    private TextView keyCentre;
    private TextView keyRight;

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
        loadFonts();
        Download.init(this);
        login = loadLogin(prefs());
    }

    /** Called once the screen's views are set, to find its soft-key bar. */
    protected final void softKeys() {
        keyLeft = (TextView) findViewById(R.id.key_left);
        keyCentre = (TextView) findViewById(R.id.key_centre);
        keyRight = (TextView) findViewById(R.id.key_right);
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

    /** Switches the theme, and redraws this screen in it. */
    protected final void setDark(boolean on) {
        prefs().edit().putBoolean("dark", on).apply();
        recreate();
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (perhaps typed in from Options) is kept.
     */
    private static Login loadLogin(SharedPreferences prefs) {
        String revision = Bundled.REVISION;
        if (revision.length() > 0 && !revision.equals(prefs.getString("bundled", ""))) {
            Login bundled;
            try {
                bundled = Login.of(Bundled.SERVER, Bundled.TOKEN);
            } catch (IllegalArgumentException e) {
                bundled = Login.blank();
            }
            if (bundled.ready()) {
                prefs.edit().putString("login", bundled.toJson()).putString("bundled", revision).apply();
                return bundled;
            }
        }
        return Login.parse(prefs.getString("login", ""));
    }

    /**
     * What the soft keys do here: Menu on the left, OK in the centre pill
     * (with its icon, 0 for none), Back on the right. A null label leaves
     * that key's place empty; a null centre hides the pill.
     */
    protected final void keys(String left, int icon, String centre, String right) {
        keyLeft.setText(left == null ? "" : left);
        keyRight.setText(right == null ? "" : right);
        if (centre == null) {
            keyCentre.setVisibility(android.view.View.INVISIBLE);
            return;
        }
        keyCentre.setVisibility(android.view.View.VISIBLE);
        keyCentre.setText(centre);
        Drawable drawn = null;
        if (icon != 0) {
            drawn = getDrawable(icon).mutate();
            drawn.setTint(getColor(R.color.on_accent));
            drawn.setBounds(0, 0, dp(13), dp(13));
        }
        keyCentre.setCompoundDrawablesRelative(drawn, null, null, null);
    }

    /** A passing word, as a toast. */
    final void note(String text) {
        toast(text);
    }

    /** A tinted icon, `size` dp square, for a TextView's side. */
    protected final Drawable icon(int id, int color, int size) {
        Drawable drawn = getDrawable(id).mutate();
        drawn.setTint(getColor(color));
        drawn.setBounds(0, 0, dp(size), dp(size));
        return drawn;
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

    /** Text in a given face; TypefaceSpan only takes a Typeface from API 28. */
    static final class Face extends android.text.style.MetricAffectingSpan {
        private final android.graphics.Typeface face;

        Face(android.graphics.Typeface face) {
            this.face = face;
        }

        @Override
        public void updateMeasureState(android.text.TextPaint paint) {
            paint.setTypeface(face);
        }

        @Override
        public void updateDrawState(android.text.TextPaint paint) {
            paint.setTypeface(face);
        }
    }
}
