package com.delta.xp3;

import android.widget.AdapterView;
import android.widget.BaseAdapter;

/**
 * A list whose highlight is painted by its rows rather than by the platform
 * selector.
 *
 * ListView's own selector draws underneath its children, and every row in this
 * app has an opaque background, so the highlight has to be a state on the row
 * itself. That means the adapter has to know which position ListView considers
 * current, which is what this holds. ListView still owns all d-pad movement;
 * this class only mirrors its selection for painting and activation.
 */
abstract class SelectableAdapter extends BaseAdapter {

    private int selectedPosition = AdapterView.INVALID_POSITION;

    void setSelectedPosition(int position) {
        if (position == selectedPosition) return;
        selectedPosition = position;
        notifyDataSetChanged();
    }

    /** Records ListView's native selection without causing a second layout. */
    void trackSelectedPosition(int position) {
        selectedPosition = position;
    }

    int getSelectedPosition() {
        return selectedPosition;
    }

    /** Whether the row at this position should paint itself as current. */
    boolean isSelected(int position) {
        return position == selectedPosition;
    }
}
