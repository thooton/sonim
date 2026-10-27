package com.print.xp3;

import android.view.KeyEvent;
import android.view.View;
import java.util.List;

/**
 * One screen on {@link MainActivity}'s stack. Its view is built once, when
 * it is opened, and kept while screens above it come and go, so going back
 * finds it as it was left, focus included.
 */
abstract class Screen {

    MainActivity app;
    View view;
    private View lastFocus;

    /** Builds the view. */
    abstract View build();

    /** What has the focus when the screen first opens. */
    View first() {
        return null;
    }

    /** Each time it comes to the top: opened, or gone back to. */
    void shown() {}

    /** Each time another screen covers it, or it closes. */
    void hidden() {}

    /** When it closes for good. */
    void closed() {}

    /** A key, before the focused view sees it; true when handled. Both presses and releases come here. */
    boolean key(KeyEvent event) {
        return false;
    }

    /** One thing Menu offers: what it says, and what it does. */
    static final class Option {
        final String title;
        final Runnable action;

        Option(String title, Runnable action) {
            this.title = title;
            this.action = action;
        }
    }

    /**
     * What Menu offers here, as things stand (often for the focused row).
     * Only what no button on the screen does: Menu is the one way to it.
     */
    void options(List<Option> out) {}

    /** Back: true when the screen dealt with it itself (cleared a search, say). */
    boolean back() {
        return false;
    }

    void remember() {
        lastFocus = view == null ? null : view.findFocus();
    }

    View restore() {
        return lastFocus;
    }

    static boolean down(KeyEvent event) {
        return event.getAction() == KeyEvent.ACTION_DOWN;
    }

    /** The digit a number key types, or -1. */
    static int digit(KeyEvent event) {
        int code = event.getKeyCode();
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return code - KeyEvent.KEYCODE_0;
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) return code - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }
}
