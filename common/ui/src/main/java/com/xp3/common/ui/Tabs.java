package com.xp3.common.ui;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

/**
 * A row of tab buttons: pressing one opens it, the open one is marked, and
 * the d-pad's left and right step through them.
 *
 * Which tab is open, and what opening one draws, stay with the activity; this
 * only finds the buttons and turns presses into tab numbers.
 */
public final class Tabs {

    public interface Listener {
        void open(int tab);
    }

    private final View[] buttons;

    /** `ids` are the buttons' view ids, in tab order. */
    public Tabs(Activity activity, final Listener listener, int... ids) {
        buttons = new View[ids.length];
        for (int i = 0; i < ids.length; i++) {
            final int tab = i;
            buttons[i] = activity.findViewById(ids[i]);
            buttons[i].setOnClickListener(view -> listener.open(tab));
        }
    }

    /** Marks `tab` as the open one. */
    public void mark(int tab) {
        for (int i = 0; i < buttons.length; i++) buttons[i].setSelected(i == tab);
    }

    /**
     * The tab a d-pad press moves to from `current`, or -1 when the event is
     * not one: not left or right, not the first press (auto-repeat from a held
     * d-pad would cycle the tabs as fast as the key fires and redraw each on
     * the way), or a key the text cursor in `focus` owns.
     */
    public int step(KeyEvent event, View focus, int current) {
        int key = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return -1;
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return -1;
        if (cursorOwns(focus, key)) return -1;
        int count = buttons.length;
        return key == KeyEvent.KEYCODE_DPAD_LEFT ? (current + count - 1) % count : (current + 1) % count;
    }

    /**
     * Whether left or right belongs to the text cursor in `focus` rather than
     * to the tabs.
     *
     * Inside typed text it belongs to the cursor. At either end of it, it goes
     * back to being a tab key: pressing right when the cursor is already as
     * far right as it goes cannot mean "further right", so it can safely mean
     * the next tab. Without that, a box with something in it is a room with no
     * door. A selection counts as inside the text; an empty box never owns them.
     */
    public static boolean cursorOwns(View focus, int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (!(focus instanceof EditText)) return false;
        EditText field = (EditText) focus;
        int length = field.getText().length();
        if (length == 0) return false;
        int start = field.getSelectionStart();
        int end = field.getSelectionEnd();
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return !(start == 0 && end == 0);
        return !(start == length && end == length);
    }
}
