package com.xp3.common.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Helpers for views and for work off the main thread. See also BaseActivity. */
public final class Ui {

    private Ui() {}

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * The BitmapFactory inSampleSize that decodes a picture `size` pixels
     * along a side to no less than `target`: the largest power of two that
     * keeps it at least that big.
     */
    public static int sampleSize(int size, int target) {
        int sample = 1;
        while (size / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    /** Fills the rest of a vertical LinearLayout. */
    public static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    }

    /** The phone's OK key, which Sonim firmware variants report three ways. */
    public static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    public interface Selected {
        void at(int position);
    }

    /** Tells `selected` each time the list's d-pad selection moves. */
    public static void onSelect(ListView list, final Selected selected) {
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selected.at(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** Makes `view` the frame's only child, filling it. */
    public static void fill(FrameLayout frame, View view) {
        frame.removeAllViews();
        frame.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * One background thread, named for debugging, that never keeps the
     * process alive: work queued behind it runs in order.
     */
    public static ExecutorService worker(String name) {
        return workers(name, 1);
    }

    /** As {@link #worker}, with up to `threads` jobs at once. */
    public static ExecutorService workers(final String name, int threads) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
