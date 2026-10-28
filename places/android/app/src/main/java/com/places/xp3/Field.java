package com.places.xp3;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/**
 * A header field (the search, or Near while it is edited) that hands Back to
 * the screen before the keyboard can spend the press on hiding itself, so
 * one Back does what the screen says Back does, and hides the keyboard too.
 */
public final class Field extends EditText {

    public Field(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK || !(getContext() instanceof Activity)) return super.onKeyPreIme(keyCode, event);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            InputMethodManager ime = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(getWindowToken(), 0);
        }
        return ((Activity) getContext()).dispatchKeyEvent(event);
    }
}
