package com.authenticator.xp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.TextPaint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.xp3.common.Disk;
import com.xp3.common.Login;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * The accounts and their codes, as Authy's list view shows them: a white pill
 * for each, the service's logo inside a ring that runs down with the code's
 * time, the service and account, and the code.
 *
 * The accounts live on the command center. The last list it sent is kept in
 * the app's files so codes show at once and offline, and is synced on
 * launch and whenever the app comes back after a while. Menu adds an account
 * by camera, by picture or by hand, and edits or deletes the one under the
 * d-pad; OK copies its code. Each service's logo is fetched once and kept
 * (`Logos`) for as long as an account is that service.
 */
public final class MainActivity extends BaseActivity {

    private static final String PREFS = "authenticator";
    private static final String CACHE = "accounts.json";
    private static final long STALE_MS = 10 * 60 * 1000;
    private static final long TICK_MS = 250;

    private static final int CAMERA = 1;
    private static final int IMAGE = 2;
    private static final int ENTRY = 3;

    private static final int ADD_CAMERA = 1;
    private static final int ADD_IMAGE = 2;
    private static final int ADD_MANUAL = 3;
    private static final int DELETE = 4;
    private static final int CENTER = 5;
    private static final int EDIT = 6;

    private final ExecutorService worker = Ui.worker("authenticator");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    /** Made in onCreate: before it the activity has no context to read files or assets with. */
    private Logos logos;

    private Login login = Login.blank();
    private List<Account> accounts = new ArrayList<>();
    private long synced;
    private int busy;
    /** Why the last sync failed, or null. */
    private String error;

    private FrameLayout content;
    private ProgressBar working;
    private ListView list;
    private Adapter adapter;
    private View fab;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        logos = new Logos(this, () -> {
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(color(R.color.page));
        content = new FrameLayout(this);
        root.addView(content);
        working = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        working.setIndeterminate(true);
        working.setIndeterminateTintList(getColorStateList(R.color.ink));
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6), Gravity.TOP);
        top.topMargin = -dp(2);
        root.addView(working, top);

        list = new ListView(this);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setDivider(null);
        list.setCacheColorHint(0);
        list.setItemsCanFocus(false);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setPadding(0, dp(4), 0, dp(4));
        list.setClipToPadding(false);
        list.setDefaultFocusHighlightEnabled(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> copy(position));
        Ui.onSelect(list, position -> paintFocus());
        list.setOnFocusChangeListener((view, has) -> paintFocus());
        setContentView(root);

        accounts = readCache();
        login = loadLogin(prefs());
        show();
        if (login.ready()) sync();
        else askLogin();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        tick();
        if (login.ready() && busy == 0 && synced > 0 && System.currentTimeMillis() - synced > STALE_MS) sync();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    // The list and its codes.

    /** The list, or with nothing in it, why not and the add button. */
    private void show() {
        working.setVisibility(busy > 0 ? View.VISIBLE : View.GONE);
        if (!accounts.isEmpty()) {
            boolean first = list.getParent() == null;
            adapter.notifyDataSetChanged();
            if (first) {
                Ui.fill(content, list);
                list.requestFocus();
                if (list.getSelectedItemPosition() < 0) list.setSelection(0);
            }
            fab = null;
            return;
        }
        LinearLayout empty = vertical();
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(20), dp(20), dp(20));
        if (busy > 0 && synced == 0) {
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminateTintList(getColorStateList(R.color.ink));
            empty.addView(spinner, new LinearLayout.LayoutParams(dp(30), dp(30)));
            Ui.fill(content, empty);
            fab = null;
            return;
        }
        TextView title = text(login.ready() ? "No accounts" : "No command center", 14, color(R.color.ink));
        title.setTypeface(Look.medium(this));
        title.setGravity(Gravity.CENTER);
        empty.addView(title);
        if (error != null) {
            TextView detail = text(error, 12, color(R.color.ink_muted));
            detail.setGravity(Gravity.CENTER);
            detail.setPadding(0, dp(6), 0, 0);
            empty.addView(detail);
        }
        TextView plus = text("+", 26, 0xFFFFFFFF);
        plus.setTypeface(Look.regular(this));
        plus.setGravity(Gravity.CENTER);
        plus.setFocusable(true);
        plus.setDefaultFocusHighlightEnabled(false);
        plus.setBackground(Look.fab(this, false));
        plus.setOnFocusChangeListener((view, has) -> view.setBackground(Look.fab(this, has)));
        plus.setOnClickListener(view -> menu());
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(44), dp(44));
        at.topMargin = dp(16);
        empty.addView(plus, at);
        Ui.fill(content, empty);
        plus.requestFocus();
        fab = plus;
    }

    private Account selected() {
        if (list.getParent() == null) return null;
        int at = list.getSelectedItemPosition();
        return at >= 0 && at < accounts.size() ? accounts.get(at) : null;
    }

    private void select(String secret) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).secret.equals(secret)) {
                final int at = i;
                list.post(() -> list.setSelection(at));
                return;
            }
        }
    }

    /** Codes change with their period, the rings every tick. Only rows on screen are touched. */
    private void tick() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.getChildCount(); i++) {
            Object tag = list.getChildAt(i).getTag();
            if (tag instanceof Row) ((Row) tag).update(now);
        }
        handler.postDelayed(tick, TICK_MS);
    }

    private void paintFocus() {
        int selected = list.hasFocus() ? list.getSelectedItemPosition() : -1;
        for (int i = 0; i < list.getChildCount(); i++) {
            Object tag = list.getChildAt(i).getTag();
            if (tag instanceof Row) ((Row) tag).focus(list.getFirstVisiblePosition() + i == selected);
        }
    }

    private final class Row {
        final View view;
        final LinearLayout card;
        final Logo logo;
        final TextView name;
        final TextView code;
        Account account;
        String shown = "";
        boolean focused;

        Row() {
            FrameLayout frame = new FrameLayout(MainActivity.this);
            frame.setPadding(dp(7), dp(3), dp(7), dp(3));
            card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(6), 0, dp(14), 0);
            card.setBackground(Look.card(MainActivity.this, false));
            frame.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

            logo = new Logo(MainActivity.this);
            card.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));

            LinearLayout words = vertical();
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            at.leftMargin = dp(9);
            card.addView(words, at);
            name = text("", 12, color(R.color.ink));
            name.setTypeface(Look.medium(MainActivity.this));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(name);
            code = text("", 21, color(R.color.ink));
            code.setTypeface(Look.medium(MainActivity.this));
            // Even figures, so the code doesn't shift as it changes.
            code.setFontFeatureSettings("tnum");
            code.setSingleLine(true);
            code.setPadding(0, dp(3), 0, 0);
            words.addView(code);

            view = frame;
            view.setTag(this);
        }

        void bind(Account next, boolean focus) {
            account = next;
            shown = "";
            logo.show(next, logos.get(next), logos.tint(next));
            SpannableStringBuilder line = new SpannableStringBuilder(next.title());
            String detail = next.detail();
            if (detail.length() > 0) {
                line.append(' ');
                int start = line.length();
                line.append('(').append(detail).append(')');
                line.setSpan(new ForegroundColorSpan(color(R.color.ink_muted)), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                line.setSpan(new AbsoluteSizeSpan(10, true), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                line.setSpan(new Face(Look.regular(MainActivity.this)), start, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            name.setText(line);
            focused = !focus;
            focus(focus);
            update(System.currentTimeMillis());
        }

        void update(long now) {
            if (account == null) return;
            String next = Account.spaced(account.code(now));
            if (!next.equals(shown)) {
                shown = next;
                code.setText(next);
            }
            logo.left(account.left(now));
        }

        void focus(boolean on) {
            if (on == focused) return;
            focused = on;
            card.setBackground(Look.card(MainActivity.this, on));
        }
    }

    /** A typeface for part of a line. */
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

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return accounts.size();
        }

        @Override
        public Object getItem(int position) {
            return accounts.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Row row = convert != null && convert.getTag() instanceof Row ? (Row) convert.getTag() : new Row();
            row.bind(accounts.get(position), list.hasFocus() && position == list.getSelectedItemPosition());
            return row.view;
        }
    }

    private void copy(int position) {
        if (position < 0 || position >= accounts.size()) return;
        Account account = accounts.get(position);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(account.title(), account.code(System.currentTimeMillis())));
        toast("Copied");
    }

    // Keys.

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            if (!down && !event.isCanceled()) menu();
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)
            && list.getParent() != null && list.hasFocus()) {
            if (down) move(keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Up and Down move one account, at once, with no easing. An account
     * wholly on screen is chosen where it is; one that isn't is brought just
     * into view at the edge it comes from, so the list moves by one row and
     * no more.
     */
    private void move(int direction) {
        int selected = list.getSelectedItemPosition();
        int target = selected < 0 ? 0 : selected + direction;
        if (target < 0 || target >= accounts.size()) return;
        int top = list.getPaddingTop();
        int bottom = list.getHeight() - list.getPaddingBottom();
        View view = list.getChildAt(target - list.getFirstVisiblePosition());
        // setSelectionFromTop measures from inside the list's top padding,
        // so each place is given less that padding; given as it is, the list
        // would creep by the padding at every press.
        if (view != null && view.getTop() >= top && view.getBottom() <= bottom) {
            list.setSelectionFromTop(target, view.getTop() - top);
        } else if (direction > 0) {
            View any = view != null ? view : list.getChildAt(0);
            int height = any == null ? 0 : any.getHeight();
            list.setSelectionFromTop(target, bottom - height - top);
        } else {
            list.setSelectionFromTop(target, 0);
        }
        paintFocus();
    }

    // Menu.

    private void menu() {
        View anchor = list.getParent() != null && list.getSelectedView() != null ? list.getSelectedView()
            : fab != null ? fab : content;
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.END);
        Menu menu = popup.getMenu();
        menu.add(0, ADD_CAMERA, 0, "Add by camera");
        menu.add(0, ADD_IMAGE, 1, "Add by image");
        menu.add(0, ADD_MANUAL, 2, "Add manually");
        final Account account = selected();
        if (account != null) menu.add(0, EDIT, 3, "Edit");
        if (account != null) menu.add(0, DELETE, 4, "Delete");
        if (!login.ready() || account == null || error != null) menu.add(0, CENTER, 5, "Command center");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case ADD_CAMERA: camera(); return true;
                case ADD_IMAGE: image(); return true;
                case ADD_MANUAL: startActivityForResult(new Intent(this, EntryActivity.class), ENTRY); return true;
                case EDIT: startActivityForResult(editing(account), ENTRY); return true;
                case DELETE: confirmDelete(account); return true;
                case CENTER: askLogin(); return true;
                default: return false;
            }
        });
        popup.show();
    }

    /** The entry screen with the account's fields, but not its secret. */
    private Intent editing(Account account) {
        return new Intent(this, EntryActivity.class)
            .putExtra(EntryActivity.ID, account.id)
            .putExtra(EntryActivity.ISSUER, account.issuer)
            .putExtra(EntryActivity.ACCOUNT, account.account)
            .putExtra(EntryActivity.ALGORITHM, account.algorithm)
            .putExtra(EntryActivity.DIGITS, account.digits)
            .putExtra(EntryActivity.PERIOD, account.period);
    }

    private void camera() {
        File photo = PhotoProvider.file(this);
        photo.delete();
        Uri uri = PhotoProvider.uri();
        Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        capture.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        capture.setClipData(ClipData.newRawUri("", uri));
        capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(capture, CAMERA);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("There's no camera app");
        }
    }

    private void image() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(pick, IMAGE);
        } catch (ActivityNotFoundException e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI), IMAGE);
            } catch (ActivityNotFoundException again) {
                toast("There's no app to pick a picture with");
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != Activity.RESULT_OK) return;
        if (request == CAMERA) {
            File photo = PhotoProvider.file(this);
            Bitmap thumbnail = null;
            if (photo.length() == 0 && data != null && data.getExtras() != null
                && data.getExtras().get("data") instanceof Bitmap) {
                // A camera app that ignored where to save sends a small copy.
                thumbnail = (Bitmap) data.getExtras().get("data");
            }
            final Bitmap small = thumbnail;
            scan(() -> {
                try {
                    if (small != null) return Pictures.read(small);
                    if (photo.length() == 0) throw new IOException("The camera didn't send a photo");
                    return Pictures.read(this, Uri.fromFile(photo));
                } finally {
                    photo.delete();
                }
            });
        } else if (request == IMAGE && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            scan(() -> Pictures.read(this, uri));
        } else if (request == ENTRY && data != null) {
            String link = data.getStringExtra(EntryActivity.LINK);
            String id = data.getStringExtra(EntryActivity.ID);
            try {
                if (link != null) {
                    add(OtpLink.parse(link));
                    return;
                }
                String secret = data.getStringExtra(EntryActivity.SECRET);
                if (id != null) {
                    Account old = null;
                    for (Account account : accounts) if (id.equals(account.id)) old = account;
                    if (old == null) return;
                    if (secret == null || secret.length() == 0) secret = old.secret;
                }
                Account typed = new Account(id, data.getStringExtra(EntryActivity.ISSUER), data.getStringExtra(EntryActivity.ACCOUNT),
                    secret, data.getStringExtra(EntryActivity.ALGORITHM),
                    data.getIntExtra(EntryActivity.DIGITS, 6), data.getIntExtra(EntryActivity.PERIOD, 30));
                if (id != null) edit(typed);
                else add(Collections.singletonList(typed));
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
            }
        }
    }

    private interface Reading {
        String read() throws IOException;
    }

    /** Reads a QR code off the main thread, then adds what it holds. */
    private void scan(Reading reading) {
        busy++;
        show();
        worker.execute(() -> {
            String text = null;
            String failure = null;
            try {
                text = reading.read();
                if (text == null) failure = "No QR code found";
            } catch (IOException e) {
                failure = e.getMessage();
            } catch (RuntimeException | OutOfMemoryError e) {
                failure = "That picture can't be read";
            }
            final String found = text;
            final String failed = failure;
            runOnUiThread(() -> {
                busy--;
                show();
                if (failed != null) {
                    toast(failed);
                    return;
                }
                try {
                    add(OtpLink.parse(found));
                } catch (IllegalArgumentException e) {
                    toast(e.getMessage());
                }
            });
        });
    }

    // The command center.

    private void sync() {
        if (!login.ready()) return;
        busy++;
        show();
        final Login using = login;
        worker.execute(() -> {
            List<Account> fresh = null;
            String failure = null;
            try {
                fresh = new Vault(using).list();
                writeCache(fresh);
            } catch (Exception e) {
                failure = message(e);
            }
            final List<Account> got = fresh;
            final String failed = failure;
            runOnUiThread(() -> {
                busy--;
                error = failed;
                if (got != null) {
                    String keep = selected() == null ? null : selected().secret;
                    accounts = got;
                    synced = System.currentTimeMillis();
                    show();
                    logos.keep(accounts);
                    if (keep != null) select(keep);
                } else {
                    show();
                }
            });
        });
    }

    private void add(final List<Account> found) {
        if (!login.ready()) {
            askLogin();
            return;
        }
        final Set<String> had = new HashSet<>();
        for (Account account : accounts) had.add(account.secret);
        busy++;
        show();
        final Login using = login;
        worker.execute(() -> {
            List<Account> fresh = null;
            String failure = null;
            try {
                fresh = new Vault(using).add(found);
                writeCache(fresh);
            } catch (Exception e) {
                failure = message(e);
            }
            final List<Account> got = fresh;
            final String failed = failure;
            runOnUiThread(() -> {
                busy--;
                if (got == null) {
                    show();
                    toast(failed);
                    return;
                }
                accounts = got;
                error = null;
                synced = System.currentTimeMillis();
                show();
                int added = 0;
                for (Account account : found) if (!had.contains(account.secret)) added++;
                Account first = found.get(0);
                if (added == 0) toast(found.size() == 1 ? first.title() + " is already here" : "These are already here");
                else toast(added == 1 ? "Added " + first.title() : "Added " + added + " accounts");
                select(first.secret);
            });
        });
    }

    private void edit(final Account changed) {
        if (!login.ready()) {
            askLogin();
            return;
        }
        busy++;
        show();
        final Login using = login;
        worker.execute(() -> {
            List<Account> fresh = null;
            String failure = null;
            try {
                fresh = new Vault(using).update(changed);
                writeCache(fresh);
            } catch (Exception e) {
                failure = message(e);
            }
            final List<Account> got = fresh;
            final String failed = failure;
            runOnUiThread(() -> {
                busy--;
                if (got == null) {
                    show();
                    toast(failed);
                    return;
                }
                accounts = got;
                error = null;
                synced = System.currentTimeMillis();
                show();
                logos.keep(accounts);
                toast("Saved " + changed.title());
                select(changed.secret);
            });
        });
    }

    private void confirmDelete(final Account account) {
        String detail = account.detail();
        new AlertDialog.Builder(this)
            .setTitle("Delete " + account.title() + "?")
            .setMessage(detail.length() > 0 ? detail : null)
            .setPositiveButton("Delete", (dialog, which) -> delete(account))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void delete(final Account account) {
        if (account.id == null) return;
        final int at = list.getSelectedItemPosition();
        busy++;
        show();
        final Login using = login;
        worker.execute(() -> {
            List<Account> fresh = null;
            String failure = null;
            try {
                fresh = new Vault(using).remove(account.id);
                writeCache(fresh);
            } catch (Exception e) {
                failure = message(e);
            }
            final List<Account> got = fresh;
            final String failed = failure;
            runOnUiThread(() -> {
                busy--;
                if (got == null) {
                    show();
                    toast(failed);
                    return;
                }
                accounts = got;
                show();
                logos.keep(accounts);
                if (!accounts.isEmpty()) list.setSelection(Math.max(0, Math.min(at, accounts.size() - 1)));
                toast("Deleted " + account.title());
            });
        });
    }

    private static String message(Exception e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) return "Offline";
        if (e instanceof java.net.SocketTimeoutException) return "The command center didn't answer";
        return e.getMessage() == null ? "Something went wrong" : e.getMessage();
    }

    // What is kept on the phone.

    private List<Account> readCache() {
        try {
            String json = Disk.read(new File(getFilesDir(), CACHE), 4 << 20);
            return json == null ? new ArrayList<>() : Vault.read(json);
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private void writeCache(List<Account> list) throws IOException {
        Disk.write(new File(getFilesDir(), CACHE), Vault.write(list), true);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (typed in from the menu) is kept.
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

    /** The fallback for a build without a bundled login. */
    private void askLogin() {
        LinearLayout box = vertical();
        int pad = dp(16);
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
                    toast("Saved: " + next.host());
                    sync();
                } catch (IllegalArgumentException e) {
                    toast(e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
