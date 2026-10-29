package com.hbsa.xp3;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;

import java.util.List;
import java.util.TimeZone;

/**
 * One event's post: its picture, when the event is, and the whole caption
 * with its dates and times marked. Left and Right go to the event before
 * and after it in the list, OK to the next picture of a carousel, Up and
 * Down scroll.
 */
public final class PostActivity extends BaseActivity {

    static final String POSITION = "position";

    private Agenda agenda;
    /** The positions in the agenda that are events or posts. */
    private List<Integer> items;
    /** Which of `items` is shown. */
    private int item;
    private int picture;

    private ScrollView scroll;
    private LinearLayout page;
    private Picture image;
    private TextView counter;
    private TextView when;
    private TextView caption;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        agenda = Club.agenda;
        if (agenda == null) {
            finish();
            return;
        }
        items = agenda.items();
        int position = getIntent().getIntExtra(POSITION, -1);
        if (state != null) position = state.getInt(POSITION, position);
        item = items.indexOf(position);
        if (item < 0) {
            finish();
            return;
        }

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(color(R.color.page));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setDefaultFocusHighlightEnabled(false);
        page = vertical();
        FrameLayout frame = new FrameLayout(this);
        image = new Picture(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(color(R.color.thumb));
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        counter = text("", 10, 0xFFFFFFFF);
        counter.setBackgroundResource(R.drawable.counter);
        counter.setPadding(dp(6), dp(2), dp(6), dp(2));
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        corner.setMargins(0, dp(6), dp(6), 0);
        frame.addView(counter, corner);
        page.addView(frame);
        when = text("", 13, color(R.color.focus_rule));
        when.setTypeface(Family.MEDIUM);
        when.setPadding(dp(10), dp(10), dp(10), 0);
        page.addView(when);
        caption = text("", 13, color(R.color.text));
        caption.setLineSpacing(dp(3), 1f);
        caption.setPadding(dp(10), dp(8), dp(10), dp(16));
        page.addView(caption);
        scroll.addView(page);
        setContentView(scroll);

        bind();
        scroll.requestFocus();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(POSITION, items.get(item));
    }

    private Agenda.Entry entry() {
        return agenda.entries.get(items.get(item));
    }

    private void bind() {
        Agenda.Entry entry = entry();
        Club.opened = items.get(item);
        if (entry.event != null) {
            String day = Dates.heading(entry.event.day, System.currentTimeMillis(), TimeZone.getDefault());
            when.setText(entry.event.minutes >= 0 ? day + " · " + Dates.clock(entry.event.minutes) : day);
            when.setTextColor(color(entry.past ? R.color.text_muted : R.color.focus_rule));
            when.setVisibility(View.VISIBLE);
        } else {
            when.setVisibility(View.GONE);
        }
        picture = 0;
        showPicture();
        caption.setText(marked(entry.post));
        scroll.scrollTo(0, 0);
    }

    private void showPicture() {
        Post post = entry().post;
        int count = post.images.size();
        counter.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        counter.setText((picture + 1) + "/" + count);
        image.waiting();
        Pictures.show(this, image, post, picture, Pictures.WIDTH);
    }

    /** The caption with every date and time in it marked. */
    private CharSequence marked(Post post) {
        String text = post.caption.trim();
        if (text.isEmpty()) return "";
        SpannableString out = new SpannableString(text);
        for (Dates.Mention m : Dates.find(text, post.postedAt, TimeZone.getDefault())) {
            out.setSpan(new BackgroundColorSpan(color(R.color.mark)), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new TypefaceSpan("sans-serif-medium"), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** To the event before (-1) or after (+1) in the list, sliding in from that side. */
    private void turn(int direction) {
        int to = item + direction;
        if (to < 0 || to >= items.size()) return;
        item = to;
        bind();
        page.setTranslationX(direction * dp(48));
        page.setAlpha(0f);
        page.animate().translationX(0).alpha(1f).setDuration(160).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && event.getRepeatCount() == 0) turn(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) scroll.smoothScrollBy(0, (key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1) * dp(72));
                return true;
            default:
                break;
        }
        if (Ui.isOk(key)) {
            int count = entry().post.images.size();
            if (down && event.getRepeatCount() == 0 && count > 1) {
                picture = (picture + 1) % count;
                showPicture();
                scroll.smoothScrollTo(0, 0);
            }
            return true;
        }
        if (MainActivity.isMenu(key)) {
            if (!down) menu();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void menu() {
        PopupMenu popup = new PopupMenu(this, page, Gravity.END);
        popup.getMenu().add(0, 1, 0, "Open in browser");
        popup.setOnMenuItemClickListener(item -> {
            MainActivity.browse(this, entry().post.url);
            return true;
        });
        popup.show();
    }

    /** The picture, holding a square's room open until it arrives so the caption doesn't jump. */
    private static final class Picture extends ImageView {
        Picture(Context context) {
            super(context);
        }

        void waiting() {
            setMinimumHeight(getResources().getDisplayMetrics().widthPixels);
        }

        @Override
        public void setImageBitmap(Bitmap bitmap) {
            super.setImageBitmap(bitmap);
            if (bitmap != null) setMinimumHeight(0);
        }
    }
}
