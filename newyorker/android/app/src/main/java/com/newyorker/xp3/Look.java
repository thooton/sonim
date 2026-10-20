package com.newyorker.xp3;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The New Yorker's type, as near as free fonts come.
 *
 * The magazine sets its text in its own cut of Adobe Caslon, its labels in
 * Graphik and its titles in Irvin, none of which can be bundled. Libre Caslon
 * Text stands in for the Caslon and Libre Caslon Display for the titles
 * (both OFL), and Inter for Graphik, as in Books. Licences are beside them
 * in assets/fonts.
 */
final class Look {

    static final int REGULAR = 0;
    static final int ITALIC = 1;
    static final int BOLD = 2;

    private static Typeface[] caslon;
    private static Typeface display;
    private static Typeface[] sans;

    private Look() {}

    static synchronized Typeface caslon(Context context, int style) {
        if (caslon == null) {
            caslon = new Typeface[] {
                load(context, "caslon_regular", Typeface.SERIF, Typeface.NORMAL),
                load(context, "caslon_italic", Typeface.SERIF, Typeface.ITALIC),
                load(context, "caslon_bold", Typeface.SERIF, Typeface.BOLD),
            };
        }
        // There is no bold italic; bold is the stronger of the two signals.
        return caslon[(style & BOLD) != 0 ? 2 : (style & ITALIC) != 0 ? 1 : 0];
    }

    static synchronized Typeface display(Context context) {
        if (display == null) display = load(context, "caslon_display", Typeface.SERIF, Typeface.NORMAL);
        return display;
    }

    static synchronized Typeface sans(Context context, boolean bold) {
        if (sans == null) {
            sans = new Typeface[] {
                load(context, "inter_regular", Typeface.SANS_SERIF, Typeface.NORMAL),
                load(context, "inter_bold", Typeface.SANS_SERIF, Typeface.BOLD),
            };
        }
        return sans[bold ? 1 : 0];
    }

    private static Typeface load(Context context, String name, Typeface fallback, int style) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/" + name + ".ttf");
        } catch (RuntimeException e) {
            return Typeface.create(fallback, style);
        }
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, Typeface face, float sp, int colorRes) {
        TextView view = new TextView(context);
        view.setTypeface(face);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(context.getColor(colorRes));
        view.setIncludeFontPadding(false);
        return view;
    }

    /** A rubric, "The Political Scene", in the site's red. */
    static TextView rubric(Context context, float sp) {
        TextView view = text(context, sans(context, false), sp, R.color.rubric);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    /** A byline, in spaced capitals as the site sets them. */
    static TextView byline(Context context, float sp) {
        TextView view = text(context, sans(context, false), sp, R.color.ink_soft);
        view.setAllCaps(true);
        view.setLetterSpacing(0.06f);
        return view;
    }
}
