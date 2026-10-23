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
 * One post: its picture over the whole of its caption, the dates and times
 * in the caption marked in gold. Left and Right go to the newer and older
 * post, OK to the next picture of a carousel, Up and Down scroll.
 */
public final class PostActivity extends BaseActivity {

    static final String INDEX = "index";

    private List<Post> posts;
    private int index;
    private int picture;

    private TextView date;
    private TextView ago;
    private ScrollView scroll;
    private LinearLayout page;
    private Picture image;
    private TextView counter;
    private TextView caption;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        posts = Club.posts(this);
        index = getIntent().getIntExtra(INDEX, 0);
        if (state != null) index = state.getInt(INDEX, index);
        if (index < 0 || index >= posts.size()) {
            finish();
            return;
        }

        LinearLayout root = vertical();
        root.setBackgroundColor(color(R.color.page));
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(color(R.color.primary));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), 0, dp(10), 0);
        date = text("", 15, color(R.color.on_primary));
        date.setTypeface(Family.MEDIUM);
        bar.addView(date, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ago = text("", 11, color(R.color.on_primary_dim));
        bar.addView(ago);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        scroll = new ScrollView(this);
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
        caption = text("", 13, color(R.color.text));
        caption.setLineSpacing(dp(3), 1f);
        caption.setPadding(dp(10), dp(9), dp(10), dp(16));
        page.addView(caption);
        scroll.addView(page);
        root.addView(scroll, Ui.weighted());
        setContentView(root);

        bind();
        scroll.requestFocus();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt(INDEX, index);
    }

    private void bind() {
        Post post = posts.get(index);
        Club.seen(this, post);
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        date.setText(Dates.date(post.postedAt, now, zone));
        String when = Dates.ago(post.postedAt, now, zone);
        ago.setText(when.equals(date.getText().toString()) ? "" : when);
        picture = 0;
        showPicture();
        caption.setText(marked(post));
        scroll.scrollTo(0, 0);
    }

    private void showPicture() {
        Post post = posts.get(index);
        int count = post.images.size();
        counter.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        counter.setText((picture + 1) + "/" + count);
        image.waiting();
        Pictures.show(this, image, post, picture, Pictures.WIDTH);
    }

    /** The caption with every date and time in it on a gold highlighter. */
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

    /** To the newer (-1) or older (+1) post, sliding in from that side. */
    private void turn(int direction) {
        int to = index + direction;
        if (to < 0 || to >= posts.size()) return;
        index = to;
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
            int count = posts.get(index).images.size();
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
        PopupMenu popup = new PopupMenu(this, date, Gravity.END);
        popup.getMenu().add(0, 1, 0, "Open in browser");
        popup.setOnMenuItemClickListener(item -> {
            MainActivity.browse(this, posts.get(index).url);
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
