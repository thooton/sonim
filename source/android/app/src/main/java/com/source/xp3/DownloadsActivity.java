package com.source.xp3;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.source.xp3.data.History;
import com.xp3.common.ui.Ui;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads: how full the phone is, then what is downloading or waiting its
 * turn, what failed this session, and everything saved, newest first. OK
 * opens a download in progress, tries a failed one again, and plays or reads
 * a saved one.
 */
public final class DownloadsActivity extends Tonal {

    private ListView list;
    private StorageCard storage;
    private final Adapter adapter = new Adapter();
    /** The rows: the storage card, section titles (Strings), Jobs and History.Entries. */
    private final List<Object> items = new ArrayList<Object>();
    private int picked = -1;
    private Object pickedItem;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_page);
        softKeys();
        ((TextView) findViewById(R.id.title)).setText("Downloads");
        // The page is a list rather than the shared scrolling page.
        View body = findViewById(R.id.body);
        ViewGroup parent = (ViewGroup) body.getParent();
        int at = parent.indexOfChild(body);
        parent.removeView(body);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setFocusable(true);
        list.setItemsCanFocus(false);
        list.setDefaultFocusHighlightEnabled(false);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        parent.addView(list, at, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        storage = new StorageCard(this, false);
        list.setAdapter(adapter);
        list.requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        storage.refresh();
    }

    private static boolean pickable(Object item) {
        return item instanceof Download.Job || item instanceof History.Entry;
    }

    @Override
    public void changed() {
        items.clear();
        items.add(storage);
        List<Download.Job> active = Download.active();
        if (!active.isEmpty()) {
            items.add("Downloading");
            items.addAll(active);
        }
        List<Download.Job> failed = Download.failed();
        if (!failed.isEmpty()) {
            items.add("Failed");
            items.addAll(failed);
        }
        List<History.Entry> saved = Download.history();
        items.add(saved.isEmpty() && active.isEmpty() && failed.isEmpty() ? "Nothing downloaded yet" : "Saved");
        items.addAll(saved);

        // The d-pad stays on the same download as the lists change under it.
        int at = pickedItem == null ? -1 : items.indexOf(pickedItem);
        if (at < 0) at = -1;
        if (at < 0 && picked >= 0) {
            at = Math.min(picked, items.size() - 1);
            while (at >= 0 && !pickable(items.get(at))) at--;
        }
        if (at < 0) at = next(-1, 1);
        setPicked(at);
        adapter.notifyDataSetChanged();
        showKeys();
    }

    /** The next pickable row from `from` going `step`, or -1. */
    private int next(int from, int step) {
        for (int i = from + step; i >= 0 && i < items.size(); i += step) if (pickable(items.get(i))) return i;
        return -1;
    }

    private void setPicked(int at) {
        picked = at;
        pickedItem = at >= 0 ? items.get(at) : null;
    }

    private void move(int step) {
        int at = next(picked, step);
        if (at < 0) {
            // Up from the first download shows the storage card again.
            if (step < 0) list.smoothScrollToPosition(0);
            return;
        }
        setPicked(at);
        adapter.notifyDataSetChanged();
        list.smoothScrollToPosition(at);
        showKeys();
    }

    private void showKeys() {
        Object item = pickedItem;
        if (item instanceof History.Entry) {
            boolean book = Download.BOOK.equals(((History.Entry) item).kind);
            keys("Options", book ? R.drawable.ic_book : R.drawable.ic_play, book ? "Read" : "Play", "Back");
        } else if (item instanceof Download.Job && ((Download.Job) item).state == Download.FAILED) {
            keys("Options", R.drawable.ic_refresh, "Retry", "Back");
        } else if (item instanceof Download.Job) {
            keys("Options", 0, "Open", "Back");
        } else {
            keys("Options", 0, null, "Back");
        }
    }

    private void ok() {
        Object item = pickedItem;
        if (item instanceof History.Entry) {
            MainActivity.open(this, ((History.Entry) item).key);
        } else if (item instanceof Download.Job) {
            Download.Job job = (Download.Job) item;
            if (job.state == Download.FAILED) retry(job);
            else detail(job.key);
        }
    }

    private void retry(Download.Job job) {
        if (!login.ready()) {
            note("No command center");
            return;
        }
        if (job.hit != null) Download.get(login, job.hit);
        else Download.get(login, job.book);
    }

    private void detail(String key) {
        startActivity(new Intent(this, DetailActivity.class).putExtra(DetailActivity.KEY, key));
    }

    private void options() {
        Sheet sheet = new Sheet(this);
        final Object item = pickedItem;
        if (item instanceof Download.Job) {
            final Download.Job job = (Download.Job) item;
            sheet.add(R.drawable.ic_info, "Details", null, () -> detail(job.key));
            sheet.add(R.drawable.ic_close, job.state == Download.FAILED ? "Forget it" : "Cancel download", null, () -> Download.cancel(job));
        } else if (item instanceof History.Entry) {
            final String key = ((History.Entry) item).key;
            sheet.add(R.drawable.ic_info, "Details", null, () -> detail(key));
        }
        if (Download.active().size() > 1) {
            sheet.add(R.drawable.ic_clear_all, "Cancel all", null, () -> Download.cancelAll());
        }
        sheet.show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (Ui.isOk(code)) {
            if (down && event.getRepeatCount() == 0) ok();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                if (!down) options();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private final class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            Object item = items.get(position);
            return item instanceof StorageCard ? 0 : item instanceof String ? 1 : 2;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Object item = items.get(position);
            if (item instanceof StorageCard) {
                LinearLayout frame = convert instanceof LinearLayout && !(convert instanceof ResultRow) ? (LinearLayout) convert : new LinearLayout(DownloadsActivity.this);
                if (storage.getParent() != frame) {
                    if (storage.getParent() != null) ((ViewGroup) storage.getParent()).removeView(storage);
                    frame.removeAllViews();
                    frame.setPadding(dp(8), dp(2), dp(8), dp(2));
                    frame.addView(storage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                return frame;
            }
            if (item instanceof String) {
                TextView view = convert instanceof TextView ? (TextView) convert : text("", 11, getColor(R.color.text_muted));
                view.setText((String) item);
                view.setPadding(dp(12), dp(9), dp(12), dp(3));
                return view;
            }
            ResultRow row = convert instanceof ResultRow ? (ResultRow) convert : new ResultRow(DownloadsActivity.this);
            boolean on = position == picked;
            if (item instanceof Download.Job) row.job((Download.Job) item, on);
            else row.entry((History.Entry) item, on);
            return row;
        }
    }
}
