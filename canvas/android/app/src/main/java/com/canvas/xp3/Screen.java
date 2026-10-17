package com.canvas.xp3;

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
