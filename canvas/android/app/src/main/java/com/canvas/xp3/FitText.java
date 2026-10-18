package com.canvas.xp3;

import android.content.Context;
import android.widget.TextView;

/**
 * One line that shows the longest of several wordings that fits its width,
 * or nothing when none does.
 *
 * For the week's range beside the home tabs: the tabs grow with the text
 * size and leave the range whatever is over, which at the larger sizes is
 * too little for "Oct 12 – 18". Running off the screen or being cut to
 * "Oct 1…" would show a wrong date; a shorter true one is better.
 */
final class FitText extends TextView {

    private final String[] choices;

    FitText(Context context, String[] choices) {
        super(context);
        this.choices = choices;
        setSingleLine(true);
        setIncludeFontPadding(false);
        setText(choices[0]);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int room = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        String pick = "";
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) pick = choices[0];
        else {
            for (String choice : choices) {
                if (getPaint().measureText(choice) <= room) {
                    pick = choice;
                    break;
                }
            }
        }
        if (!pick.contentEquals(getText())) setText(pick);
        super.onMeasure(widthSpec, heightSpec);
    }
}
