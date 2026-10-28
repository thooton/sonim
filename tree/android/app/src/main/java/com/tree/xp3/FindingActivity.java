package com.tree.xp3;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tree.xp3.qa.Center;
import com.tree.xp3.qa.Finding;
import com.tree.xp3.qa.Qa;
import com.tree.xp3.qa.Words;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.util.TimeZone;

/**
 * One finding, whole: its title, kind and status, the fix job's reason, the
 * explore job's detail, the commit, and its screenshots. A grown feature
 * has 👍 and 👎 under its title (Left and Right move between them, OK gives
 * that verdict, OK on the one already given clears it; Menu has the same
 * and Clear verdict). Up and Down scroll.
 */
public final class FindingActivity extends Screen {

    static final String FINDING = "finding";
    static final String APP = "app";

    private Finding finding;
    private String app;

    private ScrollView scroll;
    private LinearLayout page;
    private Button up;
    private Button down;
    /** An OK that went down while the buttons were scrolled off, so its up is swallowed too. */
    private boolean okHidden;
    /** Screenshots already drawn, by name, so a redraw doesn't fetch them again. */
    private final java.util.Map<String, Bitmap> shots = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = getIntent().getStringExtra(APP);
        if (app == null) app = "";
        try {
            finding = Finding.parse(getIntent().getStringExtra(FINDING));
        } catch (RuntimeException e) {
            finish();
            return;
        }
        scroll = new ScrollView(this);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.setFocusable(true);
        scroll.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        page = vertical();
        page.setPadding(0, 0, 0, dp(8));
        scroll.addView(page);
        Ui.fill(content, scroll);
        show();
        if (up != null) up.requestFocus();
        else scroll.requestFocus();
    }

    private void show() {
        boolean upFocused = up != null && up.hasFocus();
        boolean downFocused = down != null && down.hasFocus();
        page.removeAllViews();
        up = null;
        down = null;
        Qa.App known = Tree.qa == null ? null : Tree.qa.app(app);
        String name = known != null ? known.name() : Words.name(app.substring(app.indexOf('/') + 1));
        header(name + " · " + Words.kind(finding.kind), Words.status(finding.status));
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();

        LinearLayout top = card();
        TextView title = medium(finding.title.length() > 0 ? finding.title : "Untitled", 16, color(R.color.text));
        title.setLineSpacing(dp(2), 1f);
        top.addView(title);
        TextView status = medium(Words.status(finding.status), 12, AppActivity.statusColor(this, finding));
        status.setPadding(0, dp(5), 0, 0);
        top.addView(status);
        String found = Words.when(finding.found, now, zone);
        if (found.length() > 0) {
            TextView when = text("Found " + found, 11.5f, color(R.color.text_muted));
            when.setPadding(0, dp(3), 0, 0);
            top.addView(when);
        }
        page.addView(top, cardSpacing());

        if (finding.grown()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            up = verdictButton("up");
            down = verdictButton("down");
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(36), 1);
            left.rightMargin = dp(3);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(36), 1);
            right.leftMargin = dp(3);
            row.addView(up, left);
            row.addView(down, right);
            page.addView(row, cardSpacing());
            if (upFocused) up.requestFocus();
            if (downFocused) down.requestFocus();
        }

        if (finding.reason.trim().length() > 0) page.addView(fact("Why", finding.reason.trim()), cardSpacing());
        if (finding.detail.trim().length() > 0) page.addView(fact("Detail", finding.detail.trim()), cardSpacing());
        if (finding.commit.trim().length() > 0) page.addView(fact("Commit", finding.commit.trim()), cardSpacing());

        for (int i = 0; i < finding.shots.size(); i++) page.addView(shot(finding.shots.get(i), i + 1), cardSpacing());
    }

    private LinearLayout fact(String label, String value) {
        LinearLayout card = card();
        card.addView(label(label));
        TextView text = lead(value);
        card.addView(text);
        return card;
    }

    /** 👍 or 👎: the one given carries a tick. Focusable, so Left and Right move between them. */
    private Button verdictButton(final String verdict) {
        Button button = new Button(this, null, 0, com.xp3.common.ui.R.style.FamilyButton);
        boolean given = verdict.equals(finding.verdict);
        String face = Words.verdict(verdict) + ("up".equals(verdict) ? " Keep" : " Take out");
        button.setText(given ? "✓ " + face : face);
        button.setFocusable(true);
        button.setOnClickListener(v -> give(given ? "" : verdict));
        return button;
    }

    private void give(final String verdict) {
        final Center center = center();
        if (center == null) return;
        work(() -> center.verdict(finding.id, verdict), got -> {
            finding = got;
            toast(verdict.equals("up") ? "Kept" : verdict.equals("down") ? "The next run takes it out" : "Verdict cleared");
            show();
        }, null);
    }

    /** One screenshot, fetched once into the cache and scaled to the card. */
    private View shot(final String name, int number) {
        LinearLayout card = card();
        card.addView(label("Screenshot " + number));
        final ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Bitmap had = shots.get(name);
        if (had != null) {
            image.setImageBitmap(had);
            return card;
        }
        final TextView waiting = text("…", 11.5f, color(R.color.text_muted));
        card.addView(waiting);
        final Center center = Tree.center();
        if (center == null) return card;
        final int width = getResources().getDisplayMetrics().widthPixels - dp(32);
        work(() -> {
            File dir = new File(getCacheDir(), "shots");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Couldn't keep the screenshot");
            File file = new File(dir, name);
            if (!file.isFile()) center.shot(name, file);
            Bitmap bitmap = decode(file, width);
            if (bitmap == null) {
                file.delete();
                throw new java.io.IOException("Couldn't show the screenshot");
            }
            return bitmap;
        }, bitmap -> {
            shots.put(name, bitmap);
            image.setImageBitmap(bitmap);
            waiting.setVisibility(View.GONE);
        }, why -> waiting.setText(why));
        return card;
    }

    /** At most twice the width it is shown at, so a tablet's screenshot doesn't fill the memory. */
    private static Bitmap decode(File file, int width) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= width * 2) options.inSampleSize *= 2;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        // OK gives a verdict only while its button is on screen; scrolled
        // off, OK brings the buttons back into view instead.
        if (Ui.isOk(key) && up != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) okHidden = !buttonsShown();
            if (okHidden) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    okHidden = false;
                    View row = (View) up.getParent();
                    scroll.smoothScrollTo(0, Math.max(0, row.getBottom() + dp(6) - scroll.getHeight()));
                }
                return true;
            }
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                scroll.scrollBy(0, key == KeyEvent.KEYCODE_DPAD_DOWN ? dp(48) : -dp(48));
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean buttonsShown() {
        View row = (View) up.getParent();
        int y = scroll.getScrollY();
        return row.getTop() >= y && row.getBottom() <= y + scroll.getHeight();
    }

    @Override
    protected void menu() {
        if (!finding.grown()) return;
        popup(new String[] {"👍 Keep", "👎 Take out", "Clear verdict"}, which -> {
            give(which == 0 ? "up" : which == 1 ? "down" : "");
        });
    }
}
