package com.tablet.xp3;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tablet.xp3.data.Browse;
import com.tablet.xp3.data.Doc;
import com.tablet.xp3.data.Downloads;
import com.tablet.xp3.data.Formats;
import com.tablet.xp3.data.Job;
import com.tablet.xp3.data.Jobs;
import com.tablet.xp3.data.Library;
import com.tablet.xp3.data.When;
import com.tablet.xp3.net.Center;
import com.xp3.common.Disk;
import com.xp3.common.Login;
import com.xp3.common.ui.Card;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

/**
 * The whole app, one activity: Library, Transforms and the preview, with the
 * drawer and the sheets over them.
 *
 * The keys have fixed jobs (README.md): Left and Right pick the thing (a
 * file, a job's button), Up and Down what to do with it (an action in a
 * file's menu or a sheet, a job, or panning a page), OK does the
 * highlighted thing (on a cover, opens its menu), Menu (the left soft
 * key) opens the drawer and Back (the right soft key) closes whatever is
 * open. In the preview 4 and 6 turn pages and * # 0 zoom.
 *
 * Everything comes from the command center (Center): the tablet's files
 * when the app comes to the front and after any change, the transform jobs
 * every few seconds while one is running, and pages as they are looked at
 * (Pages). The last listing is kept on disk, so the shelf is there offline.
 */
public final class MainActivity extends Activity {

    private static final int LIBRARY = 0;
    private static final int TRANSFORMS = 1;
    private static final int PREVIEW = 2;
    private static final int UPLOAD = 3;
    private static final String PREFS = "tablet";
    /** How often the jobs are read while one is running, and while none is. */
    private static final long POLL_RUNNING = 5000;
    private static final long POLL_IDLE = 60000;
    /** Asking for storage, to download a result. */
    private static final int STORAGE = 1;
    /** Asking for storage, to read it for Upload. */
    private static final int STORAGE_UPLOAD = 2;
    /** The folder on the SD card that documents are downloaded into. */
    private static final String DOWNLOADS = "Documents";
    /** And page images, where the camera's photos are. */
    private static final String CAMERA = "DCIM/Camera";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private Login login = Login.blank();
    private Center tablet;
    private Pages pages;

    private Library lib = Library.empty();
    private Jobs jobs = Jobs.empty();
    /** When the listing was last read, or 0; and what went wrong reading it. */
    private long updated;
    private String problem = "";
    private boolean loading;
    private boolean started;

    private int screen = LIBRARY;
    private int file;
    /** The selected file, kept across listings. */
    private String fileId = "";
    private int jobSel;
    /** The selected job, kept across listings. */
    private String jobId = "";
    private boolean drawerOpen;
    private int drawerPick;

    /** The preview: which document's page, and where Back goes. */
    private String previewId;
    private long previewStamp;
    private String previewName;
    private int previewPage;
    private int previewPages;
    private int previewFrom;

    private FrameLayout root;
    private LibraryScreen library;
    private TransformsScreen transforms;
    private UploadScreen upload;
    /** Upload: the folder being looked in (null: the phone and the SD card), what is in it, and the line picked. */
    private File browsing;
    private List<File> inside = new ArrayList<>();
    private int uploadSel;
    /** The file waiting on the Upload to sheet. */
    private File uploading;
    private PageView preview;
    private Sheet sheet;
    /** The file or job a sheet is about, so a listing arriving under it changes nothing. */
    private Doc sheetDoc;
    private Job sheetJob;
    private FrameLayout drawerLayer;
    private Drawer drawer;
    private TextView toast;

    /** The result waiting on the storage permission, or "". */
    private Runnable pendingDownload;

    private final Runnable hideToast = () -> toast.setVisibility(View.GONE);
    private final Runnable poll = this::pollJobs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Look.load(this);
        worker = Ui.worker("tablet");
        pages = new Pages(this);
        pages.onChange(() -> library.shelf.invalidate());
        login = loadLogin(prefs());
        signIn();
        String saved = Disk.read(listingFile(), 8000000);
        if (saved != null) {
            try {
                lib = Library.parse(saved);
            } catch (IllegalArgumentException e) {
                lib = Library.empty();
            }
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Look.PAPER);
        library = new LibraryScreen(this);
        library.setDocs(lib.docs, file, this::cover);
        transforms = new TransformsScreen(this);
        upload = new UploadScreen(this);
        preview = new PageView(this);
        root.addView(library, full());
        root.addView(transforms, full());
        root.addView(upload, full());
        root.addView(preview, full());

        drawerLayer = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(Look.SCRIM);
        drawerLayer.addView(scrim, full());
        drawer = new Drawer(this);
        drawerLayer.addView(drawer, new FrameLayout.LayoutParams(Look.dp(this, 168), Parts.FILL));
        root.addView(drawerLayer, full());

        toast = Look.text(this, "", Look.ui, 10.5f, Look.SHEET);
        toast.setLineSpacing(0, 1.2f);
        toast.setPadding(Look.dp(this, 10), Look.dp(this, 7), Look.dp(this, 10), Look.dp(this, 7));
        toast.setBackground(Look.round(Look.INK, 6, this));
        toast.setElevation(Look.px(this, 10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Parts.FILL, Parts.WRAP);
        lp.gravity = Gravity.BOTTOM;
        lp.setMargins(Look.dp(this, 12), 0, Look.dp(this, 12), Look.dp(this, 10));
        toast.setVisibility(View.GONE);
        root.addView(toast, lp);

        setContentView(root);
        render();
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(Parts.FILL, Parts.FILL);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        // Every time the app comes up: the newest file, at the far left.
        file = 0;
        fileId = "";
        library.setDocs(lib.docs, file, this::cover);
        refresh();
    }

    @Override
    protected void onStop() {
        started = false;
        handler.removeCallbacks(poll);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        pages.shutdown();
        super.onDestroy();
    }

    // ---- Signing in.

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (perhaps typed in from Settings) is kept.
     */
    private Login loadLogin(SharedPreferences prefs) {
        String revision = Bundled.REVISION;
        if (revision.length() > 0 && !revision.equals(prefs.getString("bundled", ""))) {
            Login bundled;
            try {
                bundled = Login.of(Bundled.SERVER, Bundled.TOKEN);
            } catch (IllegalArgumentException e) {
                bundled = Login.blank();
            }
            if (bundled.ready()) {
                prefs.edit().putString("login", bundled.toJson()).putString("bundled", revision).apply();
                return bundled;
            }
        }
        return Login.parse(prefs.getString("login", ""));
    }

    private void signIn() {
        tablet = login.ready() ? new Center(login) : null;
        pages.signIn(tablet);
    }

    /** The fallback for a build without a bundled login, and for moving to another command center. */
    private void askLogin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Look.dp(this, 12);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText server = new EditText(this);
        server.setHint("https://…");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(login.server() == null ? "" : login.server());
        final EditText token = new EditText(this);
        token.setHint(login.ready() ? "••••••••" : "Token");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(server);
        box.addView(token);
        new AlertDialog.Builder(this)
            .setTitle("Command center")
            .setView(box)
            .setPositiveButton("Save", (dialog, which) -> {
                String typed = token.getText().toString();
                if (typed.trim().length() == 0 && login.ready()) typed = login.token();
                try {
                    Login next = Login.of(server.getText().toString(), typed);
                    if (!next.ready()) throw new IllegalArgumentException("Both the address and the token are needed");
                    login = next;
                    prefs().edit().putString("login", next.toJson()).apply();
                    signIn();
                    say("", "Signed in to " + next.host());
                    refresh();
                } catch (IllegalArgumentException e) {
                    say("", e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ---- Reading the tablet.

    private File listingFile() {
        return new File(getFilesDir(), "library.json");
    }

    /** Reads the files and the jobs again. */
    private void refresh() {
        handler.removeCallbacks(poll);
        if (tablet == null) {
            problem = "";
            render();
            return;
        }
        loading = true;
        render();
        final Center from = tablet;
        worker.execute(() -> {
            Library next = null;
            String listing = null;
            String error = "";
            try {
                listing = from.library();
                next = Library.parse(listing);
            } catch (IOException | IllegalArgumentException e) {
                error = message(e);
            } catch (InterruptedException e) {
                return;
            }
            if (listing != null) {
                try {
                    synchronized (this) {
                        Disk.write(listingFile(), listing, false);
                    }
                } catch (IOException ignored) {
                    // The listing is still shown; only the offline copy is older.
                }
            }
            final Library got = next;
            final String why = error;
            handler.post(() -> {
                if (from != tablet) return;
                loading = false;
                problem = why;
                if (got != null) {
                    lib = got;
                    updated = System.currentTimeMillis();
                    pages.retry();
                    relist();
                }
                render();
                pollJobs();
            });
        });
    }

    /** Puts a new listing on the shelf, staying on the selected file if it is still there. */
    private void relist() {
        int at = lib.indexOf(fileId);
        file = at >= 0 ? at : Math.max(0, Math.min(file, lib.docs.size() - 1));
        library.setDocs(lib.docs, file, this::cover);
        if (sheet != null && sheetDoc != null && lib.find(sheetDoc.id) == null) closeSheet();
    }

    /** Reads the jobs, says which have finished, and comes back sooner while one is running. */
    private void pollJobs() {
        handler.removeCallbacks(poll);
        if (tablet == null || !started) return;
        final Center from = tablet;
        worker.execute(() -> {
            Jobs next;
            try {
                next = Jobs.parse(from.jobs());
            } catch (IOException | IllegalArgumentException e) {
                next = null;
            } catch (InterruptedException e) {
                return;
            }
            final Jobs got = next;
            handler.post(() -> {
                if (from != tablet) return;
                if (got != null) {
                    List<Job> finished = got.finishedSince(jobs);
                    jobs = got;
                    for (Job job : finished) {
                        if (job.status == Job.DONE) {
                            say("Done", "  " + (job.result.length() > 0 ? job.result : job.heading()));
                        } else {
                            say("Failed", "  " + job.heading());
                        }
                    }
                    // A finished job put its result on the tablet.
                    if (!finished.isEmpty()) refresh();
                    render();
                }
                if (started) handler.postDelayed(poll, jobs.active() > 0 ? POLL_RUNNING : POLL_IDLE);
            });
        });
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null || text.length() == 0 ? "Something went wrong" : text;
    }

    /** Page 1 of a file, for its cover. */
    private Page cover(Doc doc) {
        return pages.cover(doc.id, doc.modified, doc.name);
    }

    /**
     * Runs `call` off the main thread; then `done` on it, or the error is
     * said. Either way the listing is read again, since the tablet changed.
     */
    private void change(Call call, Runnable done) {
        final Center with = tablet;
        if (with == null) {
            say("", "Not signed in. Sign in from Menu → Settings.");
            return;
        }
        worker.execute(() -> {
            String error = null;
            try {
                call.run(with);
            } catch (IOException | IllegalArgumentException e) {
                error = message(e);
            } catch (InterruptedException e) {
                return;
            }
            final String why = error;
            handler.post(() -> {
                if (why != null) say("", why);
                else done.run();
                refresh();
            });
        });
    }

    private interface Call {
        void run(Center tablet) throws IOException, InterruptedException;
    }

    // ---- Saying things.

    /** A note at the foot of the screen for a moment; `lead` is bold, in the highlighter's yellow. */
    private void say(String lead, String rest) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (lead.length() > 0) {
            text.append(lead);
            text.setSpan(new ForegroundColorSpan(Look.MARK), 0, lead.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new Face(Look.uiBold), 0, lead.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.append(rest);
        toast.setText(text);
        toast.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, Math.max(2600, 45L * text.length()));
    }

    /** A run of text in another face (TypefaceSpan only takes a Typeface from API 28). */
    private static final class Face extends MetricAffectingSpan {
        private final Typeface face;

        Face(Typeface face) {
            this.face = face;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setTypeface(face);
        }

        @Override
        public void updateMeasureState(TextPaint paint) {
            paint.setTypeface(face);
        }
    }

    // ---- Drawing.

    /** How fresh the listing is, for the Library's header and the drawer. */
    private String status() {
        if (tablet == null) return "Not signed in";
        if (loading && updated == 0) return "Loading…";
        if (problem.length() > 0) return "Offline";
        if (updated == 0) return "";
        return "Updated " + When.ago(updated, System.currentTimeMillis(), TimeZone.getDefault());
    }

    private void render() {
        library.setVisibility(screen == LIBRARY ? View.VISIBLE : View.GONE);
        transforms.setVisibility(screen == TRANSFORMS ? View.VISIBLE : View.GONE);
        upload.setVisibility(screen == UPLOAD ? View.VISIBLE : View.GONE);
        preview.setVisibility(screen == PREVIEW ? View.VISIBLE : View.GONE);
        drawerLayer.setVisibility(drawerOpen ? View.VISIBLE : View.GONE);

        if (screen == LIBRARY) renderLibrary();
        else if (screen == TRANSFORMS) {
            List<Job> list = jobs.ordered();
            for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(jobId)) jobSel = i;
            if (jobSel >= list.size()) jobSel = Math.max(0, list.size() - 1);
            jobId = list.isEmpty() ? "" : list.get(jobSel).id;
            String empty = tablet == null ? "Not signed in" : "No transforms";
            transforms.show(list, jobs.active(), drawerOpen || list.isEmpty() ? -1 : jobSel, empty);
        } else if (screen == UPLOAD) {
            renderUpload();
        }
        if (drawerOpen) {
            Job running = jobs.running();
            String line = running != null ? running.heading() + " · " + running.percent + "%" : "Nothing running";
            drawer.show(drawerPick, lib.docs.size(), line, jobs.active(), status(),
                tablet == null ? "Not signed in" : login.host());
        }
    }

    private void renderLibrary() {
        String status = status();
        if (!lib.docs.isEmpty()) {
            Doc doc = lib.docs.get(file);
            fileId = doc.id;
            library.show(file, status, doc.name, doc.meta(System.currentTimeMillis(), TimeZone.getDefault()), true);
        } else if (tablet == null) {
            library.show(0, status, "Not signed in", "", false);
        } else if (loading || updated == 0 && problem.length() == 0) {
            library.show(0, status, "Reading the tablet…", "", false);
        } else if (problem.length() > 0) {
            library.show(0, status, "Couldn't read the tablet", problem, false);
        } else {
            library.show(0, status, "No files", "", false);
        }
    }

    private void openSheet(Sheet next, Doc about) {
        closeSheet();
        sheet = next;
        sheetDoc = about;
        root.addView(sheet, root.indexOfChild(drawerLayer), full());
        if (sheet.field != null) {
            sheet.field.requestFocus();
            sheet.field.selectAll();
            sheet.field.post(() -> {
                InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (ime != null) ime.showSoftInput(sheet.field, 0);
            });
        }
    }

    private void closeSheet() {
        if (sheet == null) return;
        if (sheet.field != null) {
            InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(sheet.field.getWindowToken(), 0);
        }
        root.removeView(sheet);
        sheet = null;
        sheetDoc = null;
        sheetJob = null;
    }

    /** Opens a document's pages, from page 1 at 100%, the whole width in view. */
    private void openPreview(String id, long stamp, String name, int known, int from) {
        previewId = id;
        previewStamp = stamp;
        previewName = name;
        previewPage = 1;
        previewPages = Math.max(known, pages.count(id, stamp));
        previewFrom = from;
        preview.viewport.fit();
        screen = PREVIEW;
        showPage(false);
    }

    private void showPage(boolean turned) {
        final String id = previewId;
        final int n = previewPage;
        preview.show(new Pages.Blank(previewName, "Loading page " + n + "…"), previewName, n, previewPages);
        if (turned) preview.turned();
        int width = pages.pageWidth(Math.max(1, preview.getWidth() > 0 ? preview.getWidth()
            : getResources().getDisplayMetrics().widthPixels));
        pages.page(id, previewStamp, n, width, (page, count, why) -> {
            if (screen != PREVIEW || !id.equals(previewId) || n != previewPage) return;
            if (count > 0) previewPages = count;
            if (page != null) {
                preview.show(page, previewName, n, previewPages);
            } else if (count == 0 && n == 1) {
                // Nothing to show at all (an ePub nothing is written on): back where it was opened from.
                screen = previewFrom;
                say("", why);
                render();
            } else if (count > 0 && n > count) {
                previewPage = count;
                showPage(false);
            } else {
                preview.show(new Pages.Blank(previewName, why), previewName, n, previewPages);
            }
        });
    }

    // ---- Keys.

    private static final int UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4, OK = 5, MENU = 6, BACK = 7,
        PREV = 8, NEXT = 9, ZOOM_OUT = 10, ZOOM_IN = 11, FIT = 12;

    private static int key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return OK;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SOFT_LEFT: return MENU;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_SOFT_RIGHT: return BACK;
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_NUMPAD_4: return PREV;
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_NUMPAD_6: return NEXT;
            case KeyEvent.KEYCODE_STAR:
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return ZOOM_OUT;
            case KeyEvent.KEYCODE_POUND: return ZOOM_IN;
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_NUMPAD_0: return FIT;
            default: return 0;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = key(event.getKeyCode());
        // While a name is being typed, the field takes every key but the
        // ones that answer the sheet.
        if (sheet != null && sheet.field != null && k != OK && k != BACK && k != UP && k != DOWN && k != MENU) {
            return super.dispatchKeyEvent(event);
        }
        if (k == 0) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (k == BACK && event.getRepeatCount() > 0) return true;
        if (!press(k)) {
            finish();
            return true;
        }
        render();
        return true;
    }

    /** Handles one press; false means Back had nothing left to close. */
    private boolean press(int k) {
        if (drawerOpen) {
            drawerKey(k);
            return true;
        }
        if (sheet != null && k != MENU) {
            sheetKey(k);
            return true;
        }
        if (k == MENU && screen != PREVIEW) {
            closeSheet();
            drawerOpen = true;
            drawerPick = screen == LIBRARY ? Drawer.LIBRARY : screen == UPLOAD ? Drawer.UPLOAD : Drawer.TRANSFORMS;
            return true;
        }
        if (screen == LIBRARY) return libraryKey(k);
        if (screen == TRANSFORMS) return transformsKey(k);
        if (screen == UPLOAD) return uploadKey(k);
        previewKey(k);
        return true;
    }

    private void drawerKey(int k) {
        if (k == UP) drawerPick = Math.max(0, drawerPick - 1);
        else if (k == DOWN) drawerPick = Math.min(Drawer.SETTINGS, drawerPick + 1);
        else if (k == MENU || k == BACK) drawerOpen = false;
        else if (k == OK) {
            drawerOpen = false;
            if (drawerPick == Drawer.SETTINGS) {
                openSheet(Sheet.settings(this, tablet == null ? "" : login.host()), null);
                return;
            }
            if (drawerPick == Drawer.UPLOAD) {
                openUpload();
                return;
            }
            screen = drawerPick == Drawer.LIBRARY ? LIBRARY : TRANSFORMS;
            jobSel = 0;
            jobId = "";
            if (screen == TRANSFORMS) pollJobs();
        }
    }

    private boolean libraryKey(int k) {
        if (k == LEFT && file > 0) {
            file--;
        } else if (k == RIGHT && file < lib.docs.size() - 1) {
            file++;
        } else if (k == OK && !lib.docs.isEmpty()) {
            Doc doc = lib.docs.get(file);
            openSheet(Sheet.actions(this, doc), doc);
        } else if (k == BACK) {
            return false;
        }
        return true;
    }

    private void sheetKey(int k) {
        if (k == UP) sheet.pick(sheet.picked - 1);
        else if (k == DOWN) sheet.pick(sheet.picked + 1);
        else if (k == BACK) closeSheet();
        else if (k == OK) {
            Doc doc = sheetDoc;
            int picked = sheet.picked;
            switch (sheet.kind) {
                case Sheet.ACTIONS:
                    // The menu gives way to what was chosen, so Back from
                    // that goes straight back to the shelf.
                    switch (picked) {
                        case LibraryScreen.OPEN:
                            closeSheet();
                            openPreview(doc.id, doc.modified, doc.name, 0, LIBRARY);
                            break;
                        case LibraryScreen.TRANSFORM:
                            Job running = jobs.runningOn(doc.id);
                            openSheet(running != null ? Sheet.transform(this, doc, running) : Sheet.formats(this, doc), doc);
                            break;
                        case LibraryScreen.DOWNLOAD:
                            closeSheet();
                            downloadPdf(doc);
                            break;
                        case LibraryScreen.RENAME: openSheet(Sheet.rename(this, doc), doc); break;
                        case LibraryScreen.MOVE: openSheet(Sheet.move(this, doc, lib.folders), doc); break;
                        case LibraryScreen.DELETE: openSheet(Sheet.delete(this, doc), doc); break;
                        default:
                            String edited = doc.modified > 0
                                ? When.day(doc.modified, System.currentTimeMillis(), TimeZone.getDefault(), DateFormat.is24HourFormat(this))
                                : "—";
                            openSheet(Sheet.details(this, doc, edited), doc);
                    }
                    return;
                case Sheet.FORMATS:
                    if (!sheet.onStart()) {
                        sheet.toggle();
                        return;
                    }
                    if (!sheet.ready()) return;
                    if (jobs.runningOn(doc.id) == null) start(doc.id, doc.name, sheet.chosen());
                    break;
                case Sheet.FILES:
                    Job finished = sheetJob;
                    int which = sheet.doing();
                    closeSheet();
                    downloadResult(finished, which);
                    return;
                case Sheet.RENAME:
                    String name = sheet.field.getText().toString().trim();
                    if (picked == 0 && name.length() > 0 && !name.equals(doc.name)) {
                        change(t -> t.rename(doc.id, name), () -> {
                            doc.name = name;
                            say("", "Renamed");
                        });
                    }
                    break;
                case Sheet.MOVE:
                    Library.Folder folder = lib.folders.get(picked);
                    if (!folder.id.equals(doc.parent)) {
                        change(t -> t.move(doc.id, folder.id), () -> {
                            doc.parent = folder.id;
                            doc.folder = folder.path;
                            say("", "Moved to " + folder.path);
                        });
                    }
                    break;
                case Sheet.UPLOAD:
                    send(uploading, lib.folders.get(picked));
                    break;
                case Sheet.DELETE:
                    if (picked == 1) change(t -> t.move(doc.id, "trash"), () -> say("", "Deleted " + doc.name));
                    break;
                case Sheet.JOB:
                    Job job = sheetJob;
                    int what = sheet.doing();
                    closeSheet();
                    jobAction(job, what);
                    return;
                case Sheet.SETTINGS:
                    if (picked == Sheet.REFRESH) {
                        if (tablet == null) {
                            closeSheet();
                            askLogin();
                            return;
                        }
                        refresh();
                    } else if (picked == Sheet.SIGN_IN) {
                        closeSheet();
                        askLogin();
                        return;
                    }
                    break;
                default:
                    break;
            }
            closeSheet();
        }
    }

    /** Starts a transform job on a document, and says so once the command center has. */
    private void start(String id, String name, List<String> formats) {
        say("", "Starting…");
        final Job[] started = new Job[1];
        change(t -> started[0] = t.start(id, formats), () -> {
            jobs = jobs.with(started[0]);
            say("", "Transform started");
            render();
        });
    }

    private boolean transformsKey(int k) {
        List<Job> list = jobs.ordered();
        if (k == BACK) {
            screen = LIBRARY;
        } else if (k == UP && jobSel > 0) {
            jobId = list.get(--jobSel).id;
        } else if (k == DOWN && jobSel < list.size() - 1) {
            jobId = list.get(++jobSel).id;
        } else if (k == OK && jobSel < list.size()) {
            Job job = list.get(jobSel);
            openSheet(Sheet.job(this, job, job.finished(System.currentTimeMillis(), TimeZone.getDefault(),
                DateFormat.is24HourFormat(this))), null);
            sheetJob = job;
        }
        return true;
    }

    /** Does what was picked on a job's sheet. */
    private void jobAction(Job job, int what) {
        switch (what) {
            case Sheet.CANCEL_JOB:
                forget(job, "Cancelled");
                break;
            case Sheet.REMOVE:
                forget(job, "Removed");
                break;
            case Sheet.RETRY:
                if (lib.find(job.document) == null && updated > 0) {
                    say("", "File no longer on the tablet");
                } else {
                    // The same formats again; a job from before there was a choice, what it made.
                    start(job.document, job.name, job.formats.isEmpty() ? job.made() : job.formats);
                }
                break;
            case Sheet.DOWNLOAD:
                openSheet(Sheet.files(this, job), null);
                sheetJob = job;
                break;
            case Sheet.VIEW:
                Doc result = lib.find(job.tablet);
                openPreview(job.tablet, result != null ? result.modified : job.updated,
                    job.result.length() > 0 ? job.result : job.heading(), result != null ? result.pages : 0, TRANSFORMS);
                break;
            default:
                break;
        }
    }

    /** A file from the Library, as the tablet shows it, as one PDF, into Documents on the SD card. */
    private void downloadPdf(Doc doc) {
        String name = Downloads.pdfName(doc.name);
        download(doc.name, Collections.singletonList(new Item(DOWNLOADS, name, (t, dest) -> t.pdf(doc.id, dest))));
    }

    /**
     * A finished job's files in one format (its index in Formats.ALL), or
     * all of them (Sheet.ALL_FILES): documents into Documents, page images
     * into DCIM/Camera, where the gallery and messages find them.
     */
    private void downloadResult(Job job, int which) {
        List<Item> items = new ArrayList<>();
        for (String format : job.made()) {
            if (which != Sheet.ALL_FILES && which != Formats.index(format)) continue;
            String into = format.equals(Formats.JPG) ? CAMERA : DOWNLOADS;
            for (String name : job.files(format)) items.add(new Item(into, name, (t, dest) -> t.download(name, dest)));
        }
        if (items.isEmpty()) return;
        download(items.size() == 1 ? items.get(0).name : job.heading(), items);
    }

    /**
     * Saves files onto the SD card, never the phone's own storage, each
     * into its folder there; Canvas's file picker finds Documents (SD card,
     * Documents) for handing in. `said` is what the toast calls them while
     * they come. Asks for storage first, once.
     */
    private void download(String said, List<Item> items) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = () -> download(said, items);
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE);
            return;
        }
        final Center with = tablet;
        if (with == null) {
            say("", "Not signed in");
            return;
        }
        File card = Card.root(this);
        if (card == null) {
            say("", "No SD card");
            return;
        }
        say("", "Downloading " + said + "…");
        worker.execute(() -> {
            String error = null;
            List<String> saved = new ArrayList<>();
            try {
                for (Item item : items) {
                    File folder = new File(card, item.into);
                    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Couldn't make the " + item.into + " folder");
                    File dest = Downloads.free(folder, item.name);
                    item.fetch.run(with, dest);
                    saved.add(dest.getPath());
                }
            } catch (IOException e) {
                error = message(e);
            } catch (InterruptedException e) {
                return;
            }
            final String why = error;
            handler.post(() -> {
                // So other apps (Canvas's picker, the gallery, the file manager) see them straight away.
                if (!saved.isEmpty()) MediaScannerConnection.scanFile(this, saved.toArray(new String[0]), null, null);
                if (why != null) {
                    say("", why);
                } else if (items.size() == 1) {
                    say("Saved", "  SD card/" + items.get(0).into + "/" + new File(saved.get(0)).getName());
                } else {
                    boolean oneFolder = true;
                    for (Item item : items) oneFolder &= item.into.equals(items.get(0).into);
                    say("Saved", "  " + items.size() + " files, SD card" + (oneFolder ? "/" + items.get(0).into : ""));
                }
            });
        });
    }

    /** A file to download: the folder on the SD card it goes into, its name, and what fetches it. */
    private static final class Item {
        final String into;
        final String name;
        final Fetch fetch;

        Item(String into, String name, Fetch fetch) {
            this.into = into;
            this.name = name;
            this.fetch = fetch;
        }
    }

    private interface Fetch {
        void run(Center tablet, File dest) throws IOException, InterruptedException;
    }

    // ---- Upload.

    /** Opens Upload on the phone and the SD card, asking for storage first, once. */
    private void openUpload() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_UPLOAD);
            return;
        }
        screen = UPLOAD;
        browse(null, null);
    }

    /** The phone's storage, then the SD card when one is in. */
    private List<File> places() {
        List<File> out = new ArrayList<>();
        out.add(Environment.getExternalStorageDirectory());
        File card = Card.root(this);
        if (card != null) out.add(card);
        return out;
    }

    private String placeName(File place) {
        return place.equals(Environment.getExternalStorageDirectory()) ? "Phone" : "SD card";
    }

    /** Looks in `folder` (null: the places), on the line for `from` if it is there. */
    private void browse(File folder, File from) {
        browsing = folder;
        inside = folder == null ? places() : Browse.list(folder);
        int at = from == null ? -1 : inside.indexOf(from);
        uploadSel = Math.max(0, at);
    }

    private void renderUpload() {
        List<UploadScreen.Line> lines = new ArrayList<>();
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        for (File f : inside) {
            if (browsing == null) lines.add(new UploadScreen.Line(placeName(f), "", true));
            else if (f.isDirectory()) lines.add(new UploadScreen.Line(f.getName(), "", true));
            else lines.add(new UploadScreen.Line(f.getName(), Doc.size(f.length()) + " · " + When.ago(f.lastModified(), now, zone), false));
        }
        upload.show(where(browsing), lines, drawerOpen || sheet != null ? -1 : uploadSel, "No PDFs or ePubs");
    }

    /** "SD card/Documents/School", for Upload's header. */
    private String where(File folder) {
        if (folder == null) return "";
        for (File place : places()) {
            String root = place.getAbsolutePath();
            String path = folder.getAbsolutePath();
            if (path.equals(root)) return placeName(place);
            if (path.startsWith(root + "/")) return placeName(place) + path.substring(root.length());
        }
        return folder.getAbsolutePath();
    }

    private boolean uploadKey(int k) {
        if (k == UP && uploadSel > 0) {
            uploadSel--;
        } else if (k == DOWN && uploadSel < inside.size() - 1) {
            uploadSel++;
        } else if (k == OK && uploadSel < inside.size()) {
            File picked = inside.get(uploadSel);
            if (browsing == null || picked.isDirectory()) {
                browse(picked, null);
            } else if (tablet == null) {
                say("", "Not signed in");
            } else {
                uploading = picked;
                openSheet(Sheet.uploadTo(this, Browse.title(picked), lib.folders), null);
            }
        } else if (k == BACK) {
            if (browsing == null) {
                screen = LIBRARY;
            } else {
                File up = places().contains(browsing) ? null : browsing.getParentFile();
                browse(up, browsing);
            }
        }
        return true;
    }

    /** Puts `file` on the tablet in `folder`, then shows it in the Library. */
    private void send(File file, Library.Folder folder) {
        final String name = Browse.title(file);
        say("", "Uploading " + name + "…");
        final String[] id = {""};
        final int[] shown = {0};
        change(t -> id[0] = t.upload(file, name, folder.id, (done, total) -> {
            int percent = total > 0 ? (int) (done * 100 / total) : 100;
            // A word every quarter, not every buffer.
            if (percent / 25 > shown[0] / 25 && percent < 100) {
                shown[0] = percent;
                handler.post(() -> say("", "Uploading " + name + " · " + percent + "%"));
            }
        }), () -> {
            screen = LIBRARY;
            fileId = id[0];
            say("Uploaded", "  " + name);
        });
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == STORAGE_UPLOAD) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                openUpload();
                render();
            } else {
                say("", "Storage permission denied");
            }
            return;
        }
        if (request != STORAGE) return;
        Runnable pending = pendingDownload;
        pendingDownload = null;
        if (pending == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) pending.run();
        else say("", "Storage permission denied");
    }

    /** Takes a job off the list, which for a running one also ends it. */
    private void forget(Job job, String said) {
        jobs = jobs.without(job.id);
        change(t -> t.forget(job.id), () -> say("", said));
    }

    private void previewKey(int k) {
        com.tablet.xp3.data.Viewport v = preview.viewport;
        switch (k) {
            case LEFT: v.pan(-1, 0); break;
            case RIGHT: v.pan(1, 0); break;
            case UP: v.pan(0, -1); break;
            case DOWN: v.pan(0, 1); break;
            case ZOOM_IN: v.zoomIn(); break;
            case ZOOM_OUT: v.zoomOut(); break;
            case FIT: v.fit(); break;
            case PREV:
            case NEXT:
                int next = previewPage + (k == NEXT ? 1 : -1);
                if (next < 1 || previewPages > 0 && next > previewPages) break;
                previewPage = next;
                v.y = 0;
                v.clamp();
                showPage(true);
                break;
            case BACK:
                screen = previewFrom;
                break;
            default:
                break;
        }
        preview.invalidate();
    }
}
