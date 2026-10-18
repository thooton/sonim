package com.canvas.xp3;

import android.graphics.Rect;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * One screen of the app. The activity keeps a stack of them; Back pops one.
 *
 * A screen builds its whole view from its state every time it is shown or
 * something it shows changes, and the activity puts the d-pad back on the
 * item with the same key afterwards. Rebuilding is cheap at this size, and it
 * means a sync landing underneath, a theme change or a returning step all
 * take the same path.
 */
abstract class Screen {

    final MainActivity app;
    Look look;
    Views v;

    /** The main scrolling page, if the screen has one, so its position survives a rebuild. */
    ScrollView scroll;

    /** Where the d-pad was when another screen opened over this one. */
    String savedFocus;
    int savedScroll;

    Screen(MainActivity app) {
        this.app = app;
    }

    abstract View build();

    /** The left softkey. Every screen's is Options unless it says otherwise. */
    String left() {
        return "Options";
    }

    /** The right softkey: the screen's one shortcut, or null. */
    String right() {
        return null;
    }

    void onRight() {}

    /** What OK does when no item names it. */
    String center() {
        return "";
    }

    /** The screen's own entries in the Options menu, above the app-wide ones. */
    List<Sheet.Choice> options() {
        return new ArrayList<>();
    }

    /** Whether the app-wide entries (Classes, Notifications, Refresh, Settings) follow. */
    boolean globalOptions() {
        return true;
    }

    /** A first look at every key; true takes it. */
    boolean onKey(KeyEvent event) {
        return false;
    }

    /** Back on this screen; false lets the activity close it. */
    boolean onBack() {
        return false;
    }

    /** The key of the item to focus when nothing was focused before. */
    String firstFocus() {
        return null;
    }

    /** Called once the built view is on screen. */
    void shown() {}

    /** The screen is going away for good. */
    void closed() {}

    /** Something this screen shows may have changed; redraw it in place. */
    void refresh() {
        if (app.top() == this) app.redraw();
    }

    /** A step this screen opened has finished with something for it. */
    void result(Object value) {}

    /**
     * Up and Down on a page that is read rather than chosen from: to the next
     * link if it is on screen or a step away, otherwise a step of scrolling.
     * A focused link scrolled out of sight hands focus to `rest`, so OK never
     * acts on a link the student can no longer see.
     */
    boolean read(KeyEvent event, View rest) {
        int code = event.getKeyCode();
        if (scroll == null || (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN)) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        int step = (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * v.dp(60);
        View focused = app.getCurrentFocus();
        View from = focused != null && inside(focused, scroll) ? focused : null;
        View next = FocusFinder.getInstance().findNextFocus(scroll, from,
            step < 0 ? View.FOCUS_UP : View.FOCUS_DOWN);
        if (next != null && onScreen(next, scroll.getScrollY(), Math.abs(step))) {
            next.requestFocus();
            return true;
        }
        View content = scroll.getChildAt(0);
        int most = content == null ? 0 : Math.max(0, content.getHeight() - scroll.getHeight());
        int after = Math.max(0, Math.min(most, scroll.getScrollY() + step));
        scroll.smoothScrollBy(0, step);
        if (from != null && !onScreen(from, after, 0)) rest.requestFocus();
        return true;
    }

    private static boolean inside(View view, View ancestor) {
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            if (at == ancestor) return true;
        }
        return false;
    }

    /** Whether `view` shows, or is within `slack` of showing, with the page scrolled to `top`. */
    private boolean onScreen(View view, int top, int slack) {
        Rect rect = new Rect();
        view.getDrawingRect(rect);
        scroll.offsetDescendantRectToMyCoords(view, rect);
        return rect.bottom + slack > top && rect.top - slack < top + scroll.getHeight();
    }

    // ------------------------------------------------------------------

    static Item find(View root, String key) {
        if (key == null) return null;
        if (root instanceof Item && key.equals(((Item) root).key)) return (Item) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Item found = find(group.getChildAt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    static List<Item> items(View root) {
        List<Item> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(View root, List<Item> out) {
        if (root instanceof Item) out.add((Item) root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    static boolean isDigit(int keyCode) {
        return keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9;
    }
}
