package com.newyorker.xp3;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.MetricAffectingSpan;
import android.text.TextPaint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.newyorker.xp3.news.Article;
import com.newyorker.xp3.news.Block;
import com.newyorker.xp3.news.Story;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * One article, laid out as the New Yorker app sets it: the rubric in red,
 * the headline large, the dek, the byline in capitals and the date, the lead
 * picture, then the text in Caslon, each section opening on a drop cap and
 * the last paragraph ending on the magazine's diamond.
 *
 * While the page is fetched the screen holds only a spinner, and the article
 * appears whole once it is in: nothing is shown first and then replaced. A
 * saved article opens from its saved copy, without the network.
 *
 * Down and Up move a few lines at a time; Right and Left turn a screen,
 * keeping two lines of the last in view;
 * * and # make the text smaller and larger; Menu saves it, or saves it as a PDF.
 */
public final class ArticleActivity extends BaseActivity {

    private static final String URL = "url";
    private static final String RUBRIC = "rubric";
    private static final String HED = "hed";
    private static final String DEK = "dek";
    private static final String BYLINE = "byline";
    private static final String IMAGE = "image";

    /** The body's sizes, in sp, smallest first; * and # step through them. */
    private static final float[] SIZES = {11.5f, 12.5f, 13.5f, 15f, 16.5f};

    private final ExecutorService worker = Ui.worker("article");
    private ScrollView scroll;
    private View menuAnchor;
    private LinearLayout page;
    private Story story;
    private Article article;
    private int size;
    /** After "Update saved copy": the fresh copy replaces the saved one once it is in. */
    private boolean resave;

    static Intent intent(Context context, Story story) {
        return new Intent(context, ArticleActivity.class)
            .putExtra(URL, story.url)
            .putExtra(RUBRIC, story.rubric)
            .putExtra(HED, story.hed)
            .putExtra(DEK, story.dek)
            .putExtra(BYLINE, story.byline)
            .putExtra(IMAGE, story.image);
    }

    private SharedPreferences settings() {
        return getSharedPreferences("settings", MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        story = new Story(intent.getStringExtra(URL), intent.getStringExtra(RUBRIC), intent.getStringExtra(HED),
            intent.getStringExtra(DEK), intent.getStringExtra(BYLINE), intent.getStringExtra(IMAGE));
        size = Math.max(0, Math.min(SIZES.length - 1, settings().getInt("size", 2)));

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(color(R.color.page));
        scroll.setFocusable(true);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setSmoothScrollingEnabled(true);
        page = vertical();
        page.setPadding(0, dp(10), 0, dp(24));
        // The page fills the screen, so the spinner can sit in its middle.
        scroll.setFillViewport(true);
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The menu opens from the top right corner: anchored to the page, it would open below the screen.
        FrameLayout root = new FrameLayout(this);
        root.addView(scroll);
        menuAnchor = new View(this);
        root.addView(menuAnchor, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.END));
        setContentView(root);
        scroll.requestFocus();

        Article kept = MainActivity.saved(this).article(story.url);
        if (kept != null) {
            article = kept;
            render();
        } else {
            render();
            load();
        }
    }

    private void load() {
        worker.execute(() -> {
            Article result = null;
            String error = null;
            try {
                result = Article.parse(story.url, Net.page(story.url), Net.PICTURE_WIDTH);
                if (!result.hasBody()) error = "This page has no article to read here.";
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                error = e.getMessage() == null ? "Something went wrong." : e.getMessage();
            }
            final Article loaded = result;
            final String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (failure == null) {
                    article = loaded;
                    render();
                    if (resave) {
                        resave = false;
                        try {
                            MainActivity.saved(this).add(article);
                            toast("Saved copy updated");
                        } catch (IOException e) {
                            toast("Couldn\u2019t save it: " + e.getMessage());
                        }
                    }
                } else {
                    failed(failure);
                }
            });
        });
    }

    /** The header from the article when it is in, else from what the list knew; then the body, or a spinner. */
    private void render() {
        int keep = scroll.getScrollY();
        page.removeAllViews();
        // Loading, or failed: the spinner (or the error) alone, in the middle of the screen.
        page.setGravity(article == null ? Gravity.CENTER : Gravity.TOP);
        if (article == null) {
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminateTintList(getColorStateList(R.color.ink));
            LinearLayout.LayoutParams placed = new LinearLayout.LayoutParams(dp(28), dp(28));
            placed.gravity = Gravity.CENTER_HORIZONTAL;
            page.addView(spinner, placed);
            return;
        }
        Story head = article.story();

        if (!head.rubric.isEmpty()) {
            TextView rubric = Look.rubric(this, 11);
            rubric.setSingleLine(false);
            rubric.setGravity(Gravity.CENTER_HORIZONTAL);
            rubric.setText(head.rubric);
            add(rubric, 12, 0, 12, 6);
        }
        TextView hed = Look.text(this, Look.caslon(this, Look.REGULAR), 21, R.color.ink);
        hed.setGravity(Gravity.CENTER_HORIZONTAL);
        hed.setLineSpacing(0, 1.05f);
        hed.setText(head.hed);
        add(hed, 12, 0, 12, 8);
        if (!head.dek.isEmpty()) {
            TextView dek = Look.text(this, Look.caslon(this, Look.REGULAR), 13, R.color.ink_soft);
            dek.setGravity(Gravity.CENTER_HORIZONTAL);
            dek.setLineSpacing(0, 1.15f);
            dek.setText(head.dek);
            add(dek, 14, 0, 14, 8);
        }
        if (!head.byline.isEmpty()) {
            TextView byline = Look.byline(this, 9.5f);
            byline.setGravity(Gravity.CENTER_HORIZONTAL);
            byline.setText(head.byline);
            add(byline, 12, 2, 12, 3);
        }
        if (!article.date.isEmpty()) {
            TextView date = Look.text(this, Look.sans(this, false), 9.5f, R.color.muted);
            date.setGravity(Gravity.CENTER_HORIZONTAL);
            date.setText(article.date);
            add(date, 12, 0, 12, 0);
        }
        if (!head.image.isEmpty()) {
            picture(head.image, article.caption, 12);
        }

        body();
        scroll.post(() -> scroll.scrollTo(0, keep));
    }

    private void body() {
        int last = -1;
        for (int i = 0; i < article.blocks.size(); i++) {
            if (article.blocks.get(i).kind == Block.PARAGRAPH) last = i;
        }
        boolean first = true;
        for (int i = 0; i < article.blocks.size(); i++) {
            Block block = article.blocks.get(i);
            switch (block.kind) {
                case Block.PICTURE:
                    picture(block.extra, block.caption, 6);
                    break;
                case Block.BREAK:
                    View gap = new View(this);
                    page.addView(gap, new LinearLayout.LayoutParams(1, dp(12)));
                    break;
                case Block.HEADING: {
                    TextView view = Look.text(this, Look.caslon(this, Look.BOLD), SIZES[size] + 2, R.color.ink);
                    view.setText(styled(this, block, false));
                    view.setLineSpacing(0, 1.1f);
                    add(view, 10, 10, 10, 6);
                    break;
                }
                case Block.QUOTE: {
                    TextView view = paragraph(block, false, false);
                    add(view, 22, 0, 16, 9);
                    break;
                }
                case Block.ITEM: {
                    TextView view = paragraph(block, false, false);
                    SpannableStringBuilder text = new SpannableStringBuilder(block.extra + " ");
                    int hang = (int) Math.ceil(view.getPaint().measureText(text.toString()));
                    text.append(view.getText());
                    text.setSpan(new LeadingMarginSpan.Standard(0, hang), 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                    view.setText(text);
                    add(view, 16, 0, 10, 6);
                    break;
                }
                default: {
                    // The opening paragraph takes a drop cap too, as the magazine's do.
                    boolean drop = block.dropCap || first;
                    TextView view = paragraph(block, drop, i == last);
                    add(view, 10, drop && !first ? 6 : 0, 10, 9);
                    first = false;
                    break;
                }
            }
        }
    }

    private TextView paragraph(Block block, boolean drop, boolean end) {
        TextView view = Look.text(this, Look.caslon(this, Look.REGULAR), SIZES[size], R.color.ink);
        view.setLineSpacing(0, 1.28f);
        view.setIncludeFontPadding(true);
        SpannableStringBuilder text = styled(this, block, end);
        if (drop) dropCap(view, text);
        view.setText(text);
        return view;
    }

    private void dropCap(TextView view, SpannableStringBuilder text) {
        int at = 0;
        // An opening quotation mark goes with the initial, as in print.
        while (at < text.length() && "“‘\"'".indexOf(text.charAt(at)) >= 0) at++;
        if (at >= text.length() || !Character.isLetterOrDigit(text.charAt(at))) return;
        // Too short a paragraph to wrap three lines beside an initial.
        if (text.length() < 160) return;
        String letter = text.subSequence(0, at + 1).toString();
        text.delete(0, at + 1);
        Paint paint = view.getPaint();
        Rect bounds = new Rect();
        paint.getTextBounds("H", 0, 1, bounds);
        int lineHeight = Math.round(paint.getFontMetricsInt(null) * view.getLineSpacingMultiplier() + view.getLineSpacingExtra());
        text.setSpan(new DropCap(letter, Look.caslon(this, Look.REGULAR), color(R.color.ink), lineHeight, bounds.height(), dp(3)),
            0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
    }

    /**
     * The block's runs in their italic and bold, and the end mark after the
     * article's last words. Shared with the PDF.
     */
    static SpannableStringBuilder styled(Context context, Block block, boolean end) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (Block.Run run : block.runs) {
            int start = text.length();
            text.append(run.text);
            int style = (run.style & Block.Run.BOLD) != 0 ? Look.BOLD : (run.style & Block.Run.ITALIC) != 0 ? Look.ITALIC : Look.REGULAR;
            if (style != Look.REGULAR) {
                text.setSpan(new Face(Look.caslon(context, style)), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (end) text.append(" ♦");
        return text;
    }

    /** A run in another of the Caslon faces. */
    private static final class Face extends MetricAffectingSpan {
        private final Typeface face;

        Face(Typeface face) {
            this.face = face;
        }

        @Override
        public void updateMeasureState(TextPaint paint) {
            paint.setTypeface(face);
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setTypeface(face);
        }
    }

    private void picture(String url, String caption, int top) {
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams placed = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        placed.topMargin = dp(top);
        page.addView(image, placed);
        final TextView line = caption.isEmpty() ? null : Look.text(this, Look.sans(this, false), 9, R.color.muted);
        if (line != null) {
            line.setText(caption);
            line.setLineSpacing(0, 1.15f);
            add(line, 10, 4, 10, 10);
        }
        Pictures.show(image, Net.sized(url, 240), 240, ok -> {
            if (ok) return;
            image.setVisibility(View.GONE);
            if (line != null) line.setVisibility(View.GONE);
        });
        if (line == null) {
            View gap = new View(this);
            page.addView(gap, new LinearLayout.LayoutParams(1, dp(10)));
        }
    }

    private void add(View view, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams placed = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        placed.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        page.addView(view, placed);
    }

    private void failed(String error) {
        // The spinner becomes the error and a way to try again.
        page.removeAllViews();
        TextView message = Look.text(this, Look.sans(this, false), 11, R.color.muted);
        message.setGravity(Gravity.CENTER_HORIZONTAL);
        message.setText(error);
        add(message, 16, 16, 16, 10);
        TextView button = Look.text(this, Look.sans(this, true), 11, R.color.ink);
        button.setText("Try again");
        button.setAllCaps(true);
        button.setLetterSpacing(0.08f);
        button.setBackgroundResource(R.drawable.button);
        button.setPadding(dp(16), dp(7), dp(16), dp(7));
        button.setFocusable(true);
        button.setOnFocusChangeListener((view, focused) ->
            button.setTextColor(color(focused ? R.color.page : R.color.ink)));
        button.setOnClickListener(view -> {
            render();
            load();
        });
        LinearLayout.LayoutParams placed = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        placed.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(button, placed);
        button.requestFocus();
    }

    /**
     * Taken before the views see them: the ScrollView would otherwise move a
     * few pixels on each arrow, and the menu key would reach the popup it opens.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (MainActivity.isMenu(keyCode)) {
            if (!down && !event.isCanceled()) menu();
            return true;
        }
        int direction = 0;
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) direction = 1;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) direction = -1;
        boolean whole = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        // On the Try again button, Up goes back to the reading and OK presses it.
        if (direction != 0 && (getCurrentFocus() == scroll || direction < 0)) {
            if (down) turn(direction, whole);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_POUND) {
            if (down) resize(keyCode == KeyEvent.KEYCODE_STAR ? -1 : 1);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Lines moved by Down and Up: a small, steady step, so your place never jumps. */
    private static final int STEP_LINES = 2;

    /**
     * Down and Up move STEP_LINES lines; a `whole` turn moves a screen,
     * keeping two lines of the one before in view.
     */
    private void turn(int direction, boolean whole) {
        View focused = getCurrentFocus();
        if (focused != null && focused != scroll) {
            // The Try again button, at the foot: Up leaves it to read.
            scroll.requestFocus();
        }
        int line = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, SIZES[size], getResources().getDisplayMetrics()) * 1.28f * 1.2f);
        int step = whole ? Math.max(line, scroll.getHeight() - 2 * line) : STEP_LINES * line;
        // At once, not animated: the text is where it will stay as soon as the key is pressed.
        scroll.scrollBy(0, direction * step);
    }

    private void resize(int step) {
        int next = Math.max(0, Math.min(SIZES.length - 1, size + step));
        if (next == size) return;
        // Keep the same part of the article in view: scale the scroll position by the change in length.
        float fraction = page.getHeight() == 0 ? 0 : scroll.getScrollY() / (float) page.getHeight();
        size = next;
        settings().edit().putInt("size", size).apply();
        if (article == null) return;
        render();
        scroll.post(() -> scroll.scrollTo(0, Math.round(fraction * page.getHeight())));
    }

    private void menu() {
        PopupMenu popup = new PopupMenu(this, menuAnchor, Gravity.END);
        Menu menu = popup.getMenu();
        final boolean kept = MainActivity.saved(this).contains(story.url);
        if (article != null) menu.add(0, 1, 0, kept ? "Remove from Saved" : "Save");
        if (size < SIZES.length - 1) menu.add(0, 2, 0, "Larger text");
        if (size > 0) menu.add(0, 3, 0, "Smaller text");
        if (kept) menu.add(0, 4, 0, "Update saved copy");
        if (article != null) menu.add(0, 6, 0, "Save as PDF");
        menu.add(0, 5, 0, "Back to top");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    try {
                        if (kept) MainActivity.saved(this).remove(story.url);
                        else MainActivity.saved(this).add(article);
                        toast(kept ? "Removed from Saved" : "Saved");
                    } catch (IOException e) {
                        toast("Couldn’t save it: " + e.getMessage());
                    }
                    return true;
                case 2:
                    resize(1);
                    return true;
                case 3:
                    resize(-1);
                    return true;
                case 4:
                    article = null;
                    resave = true;
                    render();
                    load();
                    return true;
                case 5:
                    scroll.scrollTo(0, 0);
                    return true;
                case 6:
                    pdf();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private static final int ASK_STORAGE = 1;
    private boolean exporting;

    /**
     * Saves the article as a PDF in Download/New Yorker, asking for storage
     * the first time. Made in the background, since its pictures are
     * fetched again at print size.
     */
    private void pdf() {
        if (article == null || exporting) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE);
            return;
        }
        exporting = true;
        final Article from = article;
        toast("Making the PDF\u2026");
        worker.execute(() -> {
            File file = null;
            String error = null;
            try {
                file = PdfExport.write(getApplicationContext(), from);
                MediaScannerConnection.scanFile(getApplicationContext(),
                    new String[] {file.getAbsolutePath()}, new String[] {"application/pdf"}, null);
            } catch (InterruptedException e) {
                return;
            } catch (Exception | OutOfMemoryError e) {
                error = e.getMessage() == null ? "Something went wrong." : e.getMessage();
            }
            final File done = file;
            final String failure = error;
            runOnUiThread(() -> {
                exporting = false;
                if (failure != null) toast("Couldn\u2019t make the PDF: " + failure);
                else toast("Saved to Download/New Yorker/" + done.getName());
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != ASK_STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) pdf();
        else toast("Saving a PDF needs access to storage");
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
