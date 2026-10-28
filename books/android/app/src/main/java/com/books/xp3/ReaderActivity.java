package com.books.xp3;

import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.books.xp3.book.Book;
import com.books.xp3.book.Section;
import com.books.xp3.book.TocEntry;
import com.books.xp3.shelf.Shelf;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Reading one book: the page, and the sheet and contents over it.
 *
 * Keys, all handled here and nowhere else:
 *   Down, Right, 6, 8  next page        Up, Left, 2, 4  previous page
 *   OK                 show or hide where you are in the book
 *   Menu               Themes & Settings (Back or Menu puts it away)
 *   1 / 3              smaller / larger text
 *   #                  Night Shift      *               Contents
 *
 * The book is read and laid out on a worker thread. A section's layout is
 * kept with those either side of it, so turning into the next section is as
 * quick as turning a page within one.
 */
public final class ReaderActivity extends BaseActivity implements PageView.SizeListener, ListPanel.Listener {

    static final String EXTRA_PATH = "path";

    private static final int READING = 0;
    private static final int SHEET = 1;
    private static final int CONTENTS = 2;

    private static final int ROW_CONTENTS = 1;
    private static final int ROW_SIZE = 2;
    private static final int ROW_NIGHT = 3;
    private static final int ROW_JUSTIFY = 4;
    private static final int ROW_SPACING = 5;
    private static final int ROW_MARGINS = 6;
    private static final int ROW_FONT = 100;

    /** How long the screen stays on after the last key. */
    private static final long AWAKE = 5 * 60 * 1000;
    private static final long SAVE_EVERY = 4000;

    private interface Laid {
        void ready(Pages pages);
    }

    private Settings settings;
    private Shelf shelf;
    private String path;
    private PageView pageView;
    private ListPanel panel;
    private FrameLayout root;

    private Book book;
    /**
     * Where reading is, as a book offset: the start of the page on screen
     * once a page is turned. A relayout keeps it as it was, so changing the
     * size and back, or opening a finished book, doesn't move the place.
     */
    private long position;
    private int sectionIndex = -1;
    private int page;
    private Pages current;
    private int width;
    private int height;
    private int mode = READING;
    private long lastSave;

    /** Layouts by section, all for the current settings and screen size. */
    private final Map<Integer, Pages> cache = new HashMap<Integer, Pages>();
    /** Layouts being built, by generation and section, with who is waiting. */
    private final Map<String, List<Laid>> building = new HashMap<String, List<Laid>>();
    /** Bumped whenever the layouts in hand stop being right. */
    private int generation;
    /** A page turn is waiting for a layout; further turns wait for it. */
    private boolean waiting;

    private final ExecutorService worker = Ui.worker("books-layout");
    private final Handler handler = new Handler();
    private final Runnable sleep = () -> {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        shelf = Shared.shelf(this);
        path = getIntent().getStringExtra(EXTRA_PATH);

        root = new FrameLayout(this);
        pageView = new PageView(this);
        pageView.setSizeListener(this);
        root.addView(pageView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel = new ListPanel(this);
        panel.setListener(this);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM));
        setContentView(root);
        applyColors();
        pageView.showMessage("Opening\u2026");
        stayAwake();

        if (path == null) {
            finish();
            return;
        }
        final File file = new File(path);
        worker.execute(() -> {
            Book loaded = null;
            String problem = null;
            try {
                loaded = Shelf.open(new CardFiles(this).readable(file));
            } catch (Exception | OutOfMemoryError e) {
                problem = e.getMessage();
            }
            final Book result = loaded;
            final String error = problem;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (result == null) {
                    pageView.showMessage("This book can\u2019t be opened");
                    if (error != null) flash(error);
                    return;
                }
                opened(result);
            });
        });
    }

    private void opened(Book loaded) {
        book = loaded;
        Shelf.Entry entry = shelf.get(path);
        position = entry != null && entry.position > 0 ? Math.min(entry.position, book.length) : 0;
        display();
    }

    @Override
    public void onPageSize(int w, int h) {
        if (w == width && h == height) return;
        width = w;
        height = h;
        relayout();
    }

    /*
     * The theme already hides the status bar. A phone with on-screen
     * navigation buttons hides those too, until a swipe. The XP3 has none,
     * and is spared the system's one-time "Viewing full screen" tip.
     */
    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || !hasNavigationBar()) return;
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private boolean hasNavigationBar() {
        int id = getResources().getIdentifier("config_showNavigationBar", "bool", "android");
        return id != 0 && getResources().getBoolean(id);
    }

    @Override
    protected void onPause() {
        super.onPause();
        record(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(sleep);
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /** Throws away every layout and shows the current place afresh. */
    private void relayout() {
        generation++;
        cache.clear();
        building.clear();
        waiting = false;
        display();
    }

    private void display() {
        if (book == null || width == 0) return;
        goTo(position, false, true);
    }

    private void goTo(long target, boolean last) {
        goTo(target, last, false);
    }

    /**
     * Shows the page holding `target`; with `last`, the last page of its
     * section. With `keep`, the place stays `target` rather than becoming
     * the start of that page.
     */
    private void goTo(final long target, final boolean last, final boolean keep) {
        final int index = book.sectionAt(target);
        waiting = true;
        laid(index, pages -> {
            waiting = false;
            sectionIndex = index;
            current = pages;
            Section section = pages.section;
            if (last || target >= book.length) page = pages.count() - 1;
            else page = pages.pageOf((int) Math.max(0, target - section.start));
            showPage(keep);
            prefetch(index - 1);
            prefetch(index + 1);
        });
    }

    private void prefetch(int index) {
        if (index < 0 || index >= book.sections.size()) return;
        laid(index, null);
    }

    /** Hands over section `index` laid out, at once if it is ready, else once it is. */
    private void laid(final int index, Laid then) {
        Pages ready = cache.get(Integer.valueOf(index));
        if (ready != null) {
            if (then != null) then.ready(ready);
            return;
        }
        final int gen = generation;
        final String job = gen + ":" + index;
        List<Laid> waiters = building.get(job);
        if (waiters != null) {
            if (then != null) waiters.add(then);
            return;
        }
        waiters = new ArrayList<Laid>();
        if (then != null) waiters.add(then);
        building.put(job, waiters);

        final Section section = book.sections.get(index);
        final String key = settings.layoutKey();
        final int font = settings.font;
        final int size = dp(settings.size);
        final boolean justify = settings.justify;
        final float spacing = Settings.SPACING[settings.spacing];
        final int margin = dp(Settings.MARGINS[settings.margins]);
        final int w = width;
        final int h = height;
        worker.execute(() -> {
            final Pages pages = Pages.build(getAssets(), section, key, font, size, justify, spacing, margin, w, h);
            runOnUiThread(() -> {
                List<Laid> waiting = building.remove(job);
                if (gen != generation || isFinishing()) return;
                cache.put(Integer.valueOf(index), pages);
                trim(index);
                if (waiting != null) for (Laid laid : waiting) laid.ready(pages);
            });
        });
    }

    /** Keeps only the layouts near the one in use: a long book's would fill the memory. */
    private void trim(int around) {
        int centre = sectionIndex >= 0 ? sectionIndex : around;
        Iterator<Integer> keys = cache.keySet().iterator();
        while (keys.hasNext()) {
            int index = keys.next().intValue();
            if (Math.abs(index - centre) > 1 && index != around) keys.remove();
        }
    }

    // ------------------------------------------------------------------
    // Turning pages
    // ------------------------------------------------------------------

    private void next() {
        if (current == null || waiting) return;
        if (page + 1 < current.count()) {
            page++;
            showPage();
        } else if (sectionIndex + 1 < book.sections.size()) {
            goTo(book.sections.get(sectionIndex + 1).start, false);
        } else {
            // Past the last page: the book is finished.
            position = book.length;
            record(true);
            pageView.setStatus(book.title, "End of book", "100%", 1f);
            pageView.setChrome(true);
        }
    }

    private void previous() {
        if (current == null || waiting) return;
        if (page > 0) {
            page--;
            showPage();
        } else if (sectionIndex > 0) {
            goTo(book.sections.get(sectionIndex - 1).start, true);
        }
    }

    private void showPage() {
        showPage(false);
    }

    private void showPage(boolean keep) {
        Section section = current.section;
        if (!keep) position = section.start + current.startOf(page);
        pageView.setChrome(false);
        pageView.showPage(current, page);
        status();
        record(false);
    }

    /** Fills in the controls OK shows: chapter, pages left in it, and the whole book's progress. */
    private void status() {
        if (book == null || current == null) return;
        int entry = book.contentsAt(position);
        String chapter = entry >= 0 ? book.contents.get(entry).title : book.title;

        Section section = current.section;
        long chapterEnd = book.nextContentsOffset(position);
        long sectionEnd = section.start + section.length();
        int left;
        if (chapterEnd <= sectionEnd) {
            int lastPage = current.pageOf((int) Math.max(0, chapterEnd - 1 - section.start));
            left = Math.max(0, lastPage - page);
        } else {
            left = current.count() - 1 - page;
            // Later sections are not laid out yet: estimate them at this one's density.
            float perPage = section.length() / (float) Math.max(1, current.count());
            left += (int) Math.ceil((chapterEnd - sectionEnd) / Math.max(1f, perPage));
        }
        String detail = left == 0 ? "Last page in chapter"
            : left == 1 ? "1 page left in chapter" : left + " pages left in chapter";
        long percent = book.length == 0 ? 0 : position * 100 / book.length;
        pageView.setStatus(chapter, detail, percent + "%", book.length == 0 ? 0f : position / (float) book.length);
    }

    /** Notes the place on the shelf; writes it down now and then, and always when asked. */
    private void record(boolean now) {
        if (book == null) return;
        long time = System.currentTimeMillis();
        shelf.progress(path, position, book.length, time);
        if (now || time - lastSave > SAVE_EVERY) {
            lastSave = time;
            Shared.save(shelf);
        }
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        stayAwake();
        if (Ui.isOk(keyCode) || keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) event.startTracking();
            return true;
        }
        if (mode != READING) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_2:
                    panel.move(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_8:
                    panel.move(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_4:
                    if (mode == CONTENTS) panel.page(-1);
                    else panel.adjust(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_6:
                    if (mode == CONTENTS) panel.page(1);
                    else panel.adjust(1);
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_SPACE:
                next();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_PAGE_UP:
                previous();
                return true;
            case KeyEvent.KEYCODE_1:
                if (event.getRepeatCount() == 0) changeSize(-1);
                return true;
            case KeyEvent.KEYCODE_3:
                if (event.getRepeatCount() == 0) changeSize(1);
                return true;
            case KeyEvent.KEYCODE_POUND:
                if (event.getRepeatCount() == 0) {
                    setNightShift(!settings.nightShift);
                    flash(settings.nightShift ? "Night Shift on" : "Night Shift off");
                }
                return true;
            case KeyEvent.KEYCODE_STAR:
                if (event.getRepeatCount() == 0) openContents();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /*
     * OK and Menu act on their release, as the platform's own menus do: acted
     * on the press, the release would land on whatever had just opened.
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (Ui.isOk(keyCode) || keyCode == KeyEvent.KEYCODE_MENU) {
            if (!event.isTracking() || event.isCanceled()) return true;
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (mode == READING) openSheet();
                else close();
            } else if (mode == READING) {
                if (current != null) {
                    status();
                    pageView.setChrome(!pageView.chrome());
                }
            } else {
                panel.activate();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (mode != READING) {
            close();
        } else if (pageView.chrome()) {
            pageView.setChrome(false);
        } else {
            record(true);
            super.onBackPressed();
        }
    }

    /** Keeps the screen on while reading, until the keys have been left alone for a while. */
    private void stayAwake() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(sleep);
        handler.postDelayed(sleep, AWAKE);
    }

    // ------------------------------------------------------------------
    // Themes & Settings, and Contents
    // ------------------------------------------------------------------

    private void openSheet() {
        if (book == null) return;
        pageView.setChrome(false);
        mode = SHEET;
        panel.show("Themes & Settings", true, sheetRows(), 0);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        params.height = panel.sheetHeight(Math.round(height * 0.66f));
        params.gravity = Gravity.BOTTOM;
        panel.setLayoutParams(params);
        panel.setVisibility(View.VISIBLE);
    }

    private List<ListPanel.Row> sheetRows() {
        List<ListPanel.Row> rows = new ArrayList<ListPanel.Row>();
        rows.add(new ListPanel.Row(ROW_CONTENTS, ListPanel.LINK, "Contents"));
        ListPanel.Row size = new ListPanel.Row(ROW_SIZE, ListPanel.STEPPER, "Text Size");
        size.value = String.valueOf(settings.size);
        rows.add(size);
        ListPanel.Row night = new ListPanel.Row(ROW_NIGHT, ListPanel.TOGGLE, "Night Shift");
        night.on = settings.nightShift;
        rows.add(night);
        ListPanel.Row justify = new ListPanel.Row(ROW_JUSTIFY, ListPanel.TOGGLE, "Justify");
        justify.on = settings.justify;
        rows.add(justify);
        ListPanel.Row spacing = new ListPanel.Row(ROW_SPACING, ListPanel.STEPPER, "Line Spacing");
        spacing.value = Settings.SPACING_NAMES[settings.spacing];
        rows.add(spacing);
        ListPanel.Row margins = new ListPanel.Row(ROW_MARGINS, ListPanel.STEPPER, "Margins");
        margins.value = Settings.MARGIN_NAMES[settings.margins];
        rows.add(margins);
        rows.add(new ListPanel.Row(0, ListPanel.HEADER, "Font"));
        for (int i = 0; i < Fonts.count(); i++) {
            ListPanel.Row font = new ListPanel.Row(ROW_FONT + i, ListPanel.CHOICE, Fonts.NAMES[i]);
            font.face = Fonts.face(getAssets(), i, 0);
            font.on = i == settings.font;
            rows.add(font);
        }
        return rows;
    }

    private void openContents() {
        if (book == null) return;
        pageView.setChrome(false);
        mode = CONTENTS;
        List<ListPanel.Row> rows = new ArrayList<ListPanel.Row>();
        int here = book.contentsAt(position);
        if (book.contents.isEmpty()) {
            rows.add(new ListPanel.Row(-1, ListPanel.PLAIN, "Beginning"));
        }
        for (int i = 0; i < book.contents.size(); i++) {
            TocEntry entry = book.contents.get(i);
            ListPanel.Row row = new ListPanel.Row(i, ListPanel.PLAIN, entry.title);
            row.depth = Math.min(entry.depth, 3);
            row.current = i == here;
            rows.add(row);
        }
        panel.show(book.title == null ? "Contents" : book.title, false, rows, Math.max(0, here));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        panel.setLayoutParams(params);
        panel.setVisibility(View.VISIBLE);
    }

    private void close() {
        mode = READING;
        panel.setVisibility(View.GONE);
    }

    @Override
    public void onActivate(ListPanel.Row row) {
        if (mode == CONTENTS) {
            close();
            if (book == null || current == null) return;
            long target = row.id >= 0 && row.id < book.contents.size() ? book.contents.get(row.id).offset : 0;
            goTo(target, false);
            return;
        }
        if (row.id == ROW_CONTENTS) {
            openContents();
        } else if (row.id == ROW_NIGHT || row.id == ROW_JUSTIFY) {
            onAdjust(row, 1);
        } else if (row.id >= ROW_FONT) {
            int font = row.id - ROW_FONT;
            if (font == settings.font) return;
            settings.font = font;
            for (ListPanel.Row other : panel.rows()) {
                if (other.id >= ROW_FONT) other.on = other.id == row.id;
            }
            panel.refresh();
            settingsChanged(true);
        }
    }

    @Override
    public void onAdjust(ListPanel.Row row, int delta) {
        switch (row.id) {
            case ROW_SIZE:
                changeSize(delta);
                row.value = String.valueOf(settings.size);
                break;
            case ROW_NIGHT:
                setNightShift(!settings.nightShift);
                row.on = settings.nightShift;
                break;
            case ROW_JUSTIFY:
                settings.justify = !settings.justify;
                row.on = settings.justify;
                settingsChanged(true);
                break;
            case ROW_SPACING:
                int spacing = Settings.clamp(settings.spacing + delta, 0, Settings.SPACING.length - 1);
                if (spacing == settings.spacing) return;
                settings.spacing = spacing;
                row.value = Settings.SPACING_NAMES[spacing];
                settingsChanged(true);
                break;
            case ROW_MARGINS:
                int margins = Settings.clamp(settings.margins + delta, 0, Settings.MARGINS.length - 1);
                if (margins == settings.margins) return;
                settings.margins = margins;
                row.value = Settings.MARGIN_NAMES[margins];
                settingsChanged(true);
                break;
            default:
                return;
        }
        panel.refresh();
    }

    private void changeSize(int delta) {
        int size = Settings.clamp(settings.size + delta, Settings.MIN_SIZE, Settings.MAX_SIZE);
        if (size == settings.size) return;
        settings.size = size;
        settingsChanged(true);
    }

    private void setNightShift(boolean on) {
        settings.nightShift = on;
        settingsChanged(false);
    }

    private void settingsChanged(boolean layout) {
        settings.save();
        applyColors();
        if (layout) relayout();
    }

    /** The page's colours, with Night Shift warming the whole screen over them. */
    private void applyColors() {
        pageView.setColors(getResources().getColor(R.color.text_day), getResources().getColor(R.color.text_day_dim));
        NightShift.apply(root, settings.nightShift);
    }

    /*
     * Notices are drawn on the page rather than shown as toasts: a toast is
     * a window of the system's, which Night Shift's filter cannot reach.
     */
    private void flash(String text) {
        pageView.flash(text);
    }
}
