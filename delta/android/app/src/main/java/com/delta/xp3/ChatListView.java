package com.delta.xp3;

import android.content.Context;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ListView;

/**
 * A ListView that can keep the bottom of its final row at the viewport bottom.
 *
 * The final row has to be measured before an oversized message can be aligned,
 * so the correction belongs at the end of layout. Arrow-key scrolling remains
 * entirely in ListView; pinning is enabled only while the composer owns focus,
 * or for one layout after an update that was already at the bottom.
 */
final class ChatListView extends ListView {

    private boolean pinnedToBottom;
    private boolean pinNextLayout;
    private boolean watchingPreDraw;
    private final ViewTreeObserver.OnPreDrawListener applyBottomPin =
        () -> {
        if (!pinnedToBottom && !pinNextLayout) {
            stopWatchingPreDraw();
            return true;
        }

        int target = getCount() - 1;
        if (target < 0) {
            pinNextLayout = false;
            if (!pinnedToBottom) stopWatchingPreDraw();
            return true;
        }
        View row = getChildAt(target - getFirstVisiblePosition());
        if (row == null) {
            setSelection(target);
            pinNextLayout = true;
            return false;
        }

        int desiredTop = getHeight() - getPaddingBottom() - row.getHeight();
        if (row.getTop() != desiredTop) {
            pinNextLayout = true;
            setSelectionFromTop(target, desiredTop);
            // Do not display the top-aligned intermediate state. The
            // selection request lays out again with the measured offset.
            return false;
        } else {
            pinNextLayout = false;
            if (!hasFocus()) row.setSelected(false);
            if (!pinnedToBottom) stopWatchingPreDraw();
            return true;
        }
    };

    ChatListView(Context context) {
        super(context);
    }

    void setPinnedToBottom(boolean pinned) {
        pinnedToBottom = pinned;
        if (pinned) pinToBottomOnce();
    }

    boolean isPinnedToBottom() {
        return pinnedToBottom;
    }

    void pinToBottomOnce() {
        pinNextLayout = true;
        watchPreDraw();
        requestLayout();
    }

    private void watchPreDraw() {
        if (watchingPreDraw) return;
        getViewTreeObserver().addOnPreDrawListener(applyBottomPin);
        watchingPreDraw = true;
    }

    private void stopWatchingPreDraw() {
        if (!watchingPreDraw) return;
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(applyBottomPin);
        watchingPreDraw = false;
    }
}
