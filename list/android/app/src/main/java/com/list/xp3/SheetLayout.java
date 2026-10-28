package com.list.xp3;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.LinearLayout;

/**
 * The task sheet's root, which takes Back before the input method can.
 *
 * Once focus has been to Save and back, the input method counts itself as
 * shown, and Android gives it Back first: it would only hide itself and the
 * sheet would need a second press. Seen here, ahead of the input method,
 * one Back always closes the sheet.
 */
public final class SheetLayout extends LinearLayout {

    private Runnable onBack;

    public SheetLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setOnBack(Runnable onBack) {
        this.onBack = onBack;
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        if (onBack != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBack.run();
            return true;
        }
        return super.dispatchKeyEventPreIme(event);
    }
}
