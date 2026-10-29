package com.books.xp3;

import android.content.res.AssetManager;
import android.graphics.Typeface;

import com.books.xp3.book.Run;

/**
 * The reading fonts, bundled as assets so every phone has the same ones.
 *
 * Each has a real regular, italic, bold and bold italic: the platform's fake
 * bold and slanted italic look poor at the sizes a 240-pixel screen uses. The
 * variable fonts (Literata, Libre Baskerville, Inter) were cut to these four
 * static instances at their text optical size. Licences are beside them in
 * assets/fonts.
 */
final class Fonts {

    static final int CHARTER = 0;
    static final int INTER = 3;

    /** In the order the settings list them: serif first, as Books does. */
    static final String[] NAMES = {"Charter", "Literata", "Baskerville", "Inter", "Atkinson Hyperlegible"};
    static final boolean[] SERIF = {true, true, true, false, false};
    private static final String[] FILES = {"charter", "literata", "baskerville", "inter", "atkinson"};
    private static final String[] STYLES = {"regular", "italic", "bold", "bold_italic"};
    /** STYLES as the platform numbers them, for the fallback. */
    private static final int[] PLATFORM = {Typeface.NORMAL, Typeface.ITALIC, Typeface.BOLD, Typeface.BOLD_ITALIC};

    private static Typeface[][] faces;

    private Fonts() {}

    static int count() {
        return NAMES.length;
    }

    /** The face for `font` in a Run style (0, ITALIC, BOLD or both). */
    static synchronized Typeface face(AssetManager assets, int font, int style) {
        if (faces == null) faces = new Typeface[NAMES.length][4];
        int f = Settings.clamp(font, 0, NAMES.length - 1);
        int s = (style & Run.BOLD) != 0 ? ((style & Run.ITALIC) != 0 ? 3 : 2) : ((style & Run.ITALIC) != 0 ? 1 : 0);
        Typeface face = faces[f][s];
        if (face == null) {
            try {
                face = Typeface.createFromAsset(assets, "fonts/" + FILES[f] + "_" + STYLES[s] + ".ttf");
            } catch (RuntimeException e) {
                // A missing asset falls back to the platform's family of the same kind.
                face = Typeface.create(SERIF[f] ? Typeface.SERIF : Typeface.SANS_SERIF, PLATFORM[s]);
            }
            faces[f][s] = face;
        }
        return face;
    }

    /** The interface face: Inter, which stands in for Apple's San Francisco. */
    static Typeface ui(AssetManager assets, boolean bold) {
        return face(assets, INTER, bold ? Run.BOLD : 0);
    }
}
