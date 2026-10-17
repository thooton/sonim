package com.canvas.xp3;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;

import com.canvas.xp3.data.Prefs;

/**
 * Week Strip's visual system: one typeface, ink on paper, three course
 * colours and one yellow.
 *
 * The yellow is the highlighter, and it means one thing: this is where the
 * d-pad is. Nothing else on any screen is ever that colour, so a student can
 * find their place at a glance. Ink means selected (the day in view, a chosen
 * answer, a main button). Course colours mark which class something belongs
 * to and never how it is going: nothing is red, and no grade changes colour.
 *
 * Built in code rather than as resources because the theme and text size
 * change while the app is open, and every screen is assembled in code anyway.
 */
final class Look {

    final boolean dark;
    final float scale;

    final int ink;
    final int muted;
    final int faint;
    final int line;
    final int paper;
    final int bg;
    final int body;
    /** The highlighter. Always the same yellow behind dark text. */
    final int mark;
    /** The highlighter as a stroke under text, dimmer in the dark so white text stays legible on it. */
    final int markStroke;
    final int okBg;
    final int okText;
    final int dim;
    /** Text on the yellow, which is always dark. */
    static final int ON_MARK = 0xFF101828;

    final Typeface regular;
    final Typeface medium;
    final Typeface semibold;
    final Typeface bold;
    final Typeface heavy;

    private static final int[] COURSE = {
        0xFFFF5A36, 0xFF3D5AFE, 0xFF00A389, 0xFF9E47FF, 0xFFE8438F, 0xFFD98E04, 0xFF0098C7, 0xFF6B7A90,
    };
    /** The blue lifts in the dark, where #3D5AFE sinks into the page. */
    private static final int[] COURSE_DARK = {
        0xFFFF5A36, 0xFF6B7FFF, 0xFF00A389, 0xFFB57AFF, 0xFFF0629F, 0xFFE8A21F, 0xFF22B4E0, 0xFF8D9AAE,
    };

    private Look(Context context, boolean dark, boolean large) {
        this.dark = dark;
        this.scale = large ? 1.15f : 1f;
        ink = dark ? 0xFFF2F4F7 : 0xFF101828;
        muted = dark ? 0xFF98A2B3 : 0xFF667085;
        faint = dark ? 0xFF5D6676 : 0xFF98A2B3;
        line = dark ? 0xFF1F2633 : 0xFFEAECF0;
        paper = dark ? 0xFF161B26 : 0xFFF2F4F7;
        bg = dark ? 0xFF0B0F17 : 0xFFFFFFFF;
        body = dark ? 0xFFC9CED8 : 0xFF344054;
        mark = 0xFFFFE45C;
        markStroke = dark ? 0x6BFFE45C : 0xFFFFE45C;
        okBg = dark ? 0xFF0F2E24 : 0xFFE3F6EF;
        okText = dark ? 0xFF5FD3A6 : 0xFF05734F;
        dim = 0x80101828;
        regular = context.getResources().getFont(R.font.bricolage_regular);
        medium = context.getResources().getFont(R.font.bricolage_medium);
        semibold = context.getResources().getFont(R.font.bricolage_semibold);
        bold = context.getResources().getFont(R.font.bricolage_bold);
        heavy = context.getResources().getFont(R.font.bricolage_extrabold);
    }

    static Look of(Context context, Prefs prefs) {
        boolean dark;
        if (Prefs.DARK.equals(prefs.theme)) dark = true;
        else if (Prefs.LIGHT.equals(prefs.theme)) dark = false;
        else {
            int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            dark = night == Configuration.UI_MODE_NIGHT_YES;
        }
        return new Look(context, dark, Prefs.LARGE.equals(prefs.textSize));
    }

    int course(int index) {
        int[] set = dark ? COURSE_DARK : COURSE;
        return set[Math.abs(index) % set.length];
    }

    /** A course colour washed into the page, for the class tiles. */
    int courseWash(int index) {
        return blend(course(index), bg, dark ? 0.22f : 0.14f);
    }

    static int blend(int color, int base, float amount) {
        int r = (int) (((color >> 16) & 0xFF) * amount + ((base >> 16) & 0xFF) * (1 - amount));
        int g = (int) (((color >> 8) & 0xFF) * amount + ((base >> 8) & 0xFF) * (1 - amount));
        int b = (int) ((color & 0xFF) * amount + (base & 0xFF) * (1 - amount));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
