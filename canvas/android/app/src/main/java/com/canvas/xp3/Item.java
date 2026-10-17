package com.canvas.xp3;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xp3.common.ui.Ui;

/**
 * One place the d-pad can land: a row, a box, a button or an option.
 *
 * Each knows how it shows focus, because the design gives focus four shapes
 * that all mean "you are here": a highlighter stroke under a row's title, a
 * yellow fill on a button or an option, an ink border on a box, a yellow
 * badge on a menu number. Shape as well as colour, so it still reads on a
 * dim screen or to a student who does not see yellow.
 *
 * OK acts on release, and both the codes Sonim firmware sends for it work.
 * Each item names what OK does, which the softkey bar shows in its middle.
 */
final class Item extends LinearLayout {

    interface Painter {
        void paint(boolean focused);
    }

    final String key;
    String ok;
    Runnable action;
    Painter painter;
    /** The day this row belongs to, on the home timeline; 0 elsewhere. */
    long day;

    Item(Context context, String key, String ok, Runnable action) {
        super(context);
        this.key = key;
        this.ok = ok;
        this.action = action;
        setOrientation(VERTICAL);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        setClickable(true);
        setOnClickListener(view -> fire());
        setOnFocusChangeListener((view, focused) -> {
            if (painter != null) painter.paint(focused);
            if (focused && getContext() instanceof MainActivity) ((MainActivity) getContext()).focusMoved(this);
        });
    }

    void fire() {
        if (action != null) action.run();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Ui.isOk(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (Ui.isOk(keyCode)) {
            if (!event.isCanceled()) fire();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------------
    // The four ways focus shows
    // ------------------------------------------------------------------

    /** The highlighter under a row's title. */
    Item marks(final MarkText title, final Look look) {
        painter = focused -> title.mark(focused, look.markStroke);
        return this;
    }

    /** A box whose border turns from line (or dashed) to ink. */
    Item boxed(final Drawable idle, final Drawable focused) {
        setBackground(idle);
        painter = has -> setBackground(has ? focused : idle);
        return this;
    }

    /** A button or option that fills yellow; its text turns dark on the yellow. */
    Item fills(final Drawable idle, final Drawable focused, final TextView[] texts, final int[] colors) {
        setBackground(idle);
        painter = has -> {
            setBackground(has ? focused : idle);
            for (int i = 0; i < texts.length; i++) texts[i].setTextColor(has ? Look.ON_MARK : colors[i]);
        };
        return this;
    }

    /** A menu row: the number badge goes yellow and the label takes the highlighter. */
    Item keyed(final TextView badge, final MarkText title, final Views views) {
        final Look look = views.look;
        painter = has -> {
            title.mark(has, look.markStroke);
            badge.setBackground(has ? views.round(look.mark, 4, look.mark, 1) : views.round(0, 4, look.line, 1));
            badge.setTextColor(has ? Look.ON_MARK : look.muted);
        };
        return this;
    }

    Item also(final Painter extra) {
        final Painter first = painter;
        painter = has -> {
            if (first != null) first.paint(has);
            extra.paint(has);
        };
        return this;
    }
}
