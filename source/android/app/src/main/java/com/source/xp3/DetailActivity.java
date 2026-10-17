package com.source.xp3;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.source.xp3.data.History;
import com.source.xp3.net.Books;
import com.source.xp3.net.Music;
import com.source.xp3.net.Names;
import com.xp3.common.ui.Ui;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One result, or one download, on a page of its own: its cover, name and
 * every folder it sits in as the sharer named them, who shares it, a card
 * saying where it stands (downloading with the wavy bar and what is left,
 * queued, saved and where to, or why it failed), an album's tracks each with
 * its own state, a book's other formats to choose between with Left and
 * Right, and the download's own log from the command center. OK does what the
 * centre soft key says: Get, Play, Read or Retry.
 */
public final class DetailActivity extends Tonal {

    static final String KEY = "key";
    private static final int ASK_STORAGE = 1;

    private String key;
    private Music.Hit hit;
    private Books.Book book;
    /** A book's other formats on the results page, this one among them. */
    private List<Books.Book> formats = new ArrayList<Books.Book>();
    /** An earlier download, when that is all there is to show. */
    private History.Entry entry;

    private ScrollView body;
    private LinearLayout page;
    private Cover cover;
    private LinearLayout card;
    private TextView cardTitle;
    private TextView cardFigure;
    private Wavy wavy;
    private TextView cardLine;
    private TextView logView;
    private final List<StateButton> trackStates = new ArrayList<StateButton>();
    private final List<TextView> formatChips = new ArrayList<TextView>();

    /** For "about a minute left": bytes a second, smoothed. */
    private double rate;
    private long lastDone = -1;
    private long lastTime;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_page);
        softKeys();
        body = (ScrollView) findViewById(R.id.body);
        page = (LinearLayout) findViewById(R.id.page);
        key = getIntent().getStringExtra(KEY);
        if (key == null || !resolve()) {
            finish();
            return;
        }
        build();
        body.requestFocus();
    }

    /** Finds what `key` is: this session's download, a shown result, or an earlier download. */
    private boolean resolve() {
        Download.Job job = Download.job(key);
        if (job != null) {
            hit = job.hit;
            book = job.book;
        }
        Music.Found found = Download.found();
        if (hit == null && book == null && found != null) {
            for (Music.Hit h : found.hits) if (h.link.equals(key)) hit = h;
        }
        Books.Page results = Download.results();
        if (hit == null && book == null && results != null) {
            for (Books.Book b : results.books) if (Download.key(b).equals(key)) book = b;
        }
        if (book != null) {
            boolean listed = results != null && results.books.contains(book);
            formats = listed ? Books.formats(results, book) : Collections.singletonList(book);
        }
        if (hit == null && book == null) {
            for (History.Entry e : Download.history()) if (e.key.equals(key)) entry = e;
        }
        return hit != null || book != null || entry != null;
    }

    private String kind() {
        if (hit != null) return hit.album() ? Music.ALBUM : Music.SONG;
        if (book != null) return Download.BOOK;
        return entry.kind;
    }

    private String name() {
        if (hit != null) return hit.name();
        if (book != null) return book.title.length() > 0 ? book.title : "Untitled";
        return entry.title;
    }

    // ---- Building the page.

    private void build() {
        ((TextView) findViewById(R.id.title)).setText(label(kind()));
        page.removeAllViews();
        trackStates.clear();
        formatChips.clear();
        cover = new Cover(this);
        if (book != null || Download.BOOK.equals(kind())) buildBook();
        else if (Music.ALBUM.equals(kind())) buildAlbum();
        else buildSong();

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        cardTitle = text("", 12, getColor(R.color.text));
        cardTitle.setTypeface(BOLD);
        cardTitle.setCompoundDrawablePadding(dp(5));
        cardTitle.setSingleLine(true);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(cardTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cardFigure = text("", 18, getColor(R.color.accent));
        cardFigure.setTypeface(BOLD);
        top.addView(cardFigure);
        card.addView(top);
        wavy = new Wavy(this);
        LinearLayout.LayoutParams wavySize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        wavySize.topMargin = dp(6);
        card.addView(wavy, wavySize);
        cardLine = text("", 11, getColor(R.color.text_muted));
        cardLine.setPadding(0, dp(5), 0, 0);
        card.addView(cardLine);
        page.addView(card, margins(ViewGroup.LayoutParams.MATCH_PARENT, 8, 8));

        if (hit != null && hit.album()) buildTracks();
        if (entry != null && hit == null && book == null && entry.files.size() > 1) buildFiles();

        logView = text("", 10, getColor(R.color.text_muted));
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setLineSpacing(dp(2), 1f);
        logView.setBackgroundResource(R.drawable.log_card);
        logView.setPadding(dp(8), dp(6), dp(8), dp(6));
        page.addView(logView, margins(ViewGroup.LayoutParams.MATCH_PARENT, 8, 8));
    }

    private LinearLayout.LayoutParams margins(int width, int top, int sides) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        size.setMargins(dp(sides), dp(top), dp(sides), 0);
        return size;
    }

    private TextView centred(CharSequence value, float sp, int color) {
        TextView view = text(value, 11, color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), 0, dp(12), 0);
        return view;
    }

    /** A song: the cover over its name, its folders, its format and who shares it. */
    private void buildSong() {
        LinearLayout.LayoutParams coverSize = new LinearLayout.LayoutParams(dp(76), dp(76));
        coverSize.gravity = Gravity.CENTER_HORIZONTAL;
        coverSize.topMargin = dp(4);
        page.addView(cover, coverSize);
        TextView title = centred(name(), 16, getColor(R.color.text));
        title.setTypeface(BOLD);
        title.setPadding(dp(12), dp(8), dp(12), 0);
        page.addView(title);
        String folders = hit != null ? hit.folders() : entry.folders;
        if (folders.length() > 0) {
            TextView where = centred(folders, 10.5f, getColor(R.color.text_muted));
            where.setPadding(dp(12), dp(2), dp(12), 0);
            page.addView(where);
        }
        if (hit != null) {
            page.addView(chips());
            page.addView(sharer());
        }
    }

    /** "MP3 · 320 kbps", "4:41", "10.8 MB": a song's facts as outlined chips, centred. */
    private View chips() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setPadding(dp(8), dp(8), dp(8), 0);
        List<String> parts = new ArrayList<String>(java.util.Arrays.asList(hit.specs().split(" · ")));
        parts.remove("");
        if (hit.formats().length() > 0) {
            if (parts.isEmpty()) parts.add(hit.formats());
            else parts.set(0, hit.formats() + " · " + parts.get(0));
        }
        for (String part : parts) {
            TextView chip = text(part, 10, getColor(R.color.text_muted));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            chip.setSingleLine(true);
            chip.setBackgroundResource(R.drawable.chip);
            chip.setPadding(dp(7), dp(2), dp(7), dp(2));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            size.setMargins(dp(2), 0, dp(2), 0);
            row.addView(chip, size);
        }
        return row;
    }

    /** An album: its cover beside its folder, the folders above it, and its files. */
    private void buildAlbum() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(4), dp(10), 0);
        row.addView(cover, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(dp(10), 0, 0, 0);
        TextView title = text(name(), 15, getColor(R.color.text));
        title.setTypeface(BOLD);
        lines.addView(title);
        String folders = hit != null ? hit.folders() : entry.folders;
        if (folders.length() > 0) {
            TextView where = text(folders, 11, getColor(R.color.text_muted));
            where.setPadding(0, dp(3), 0, 0);
            where.setCompoundDrawablePadding(dp(3));
            where.setCompoundDrawablesRelative(icon(R.drawable.ic_folder, R.color.text_muted, 10), null, null, null);
            lines.addView(where);
        }
        row.addView(lines, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(row);
        if (hit != null) {
            View tags = tagLine(hit.formats(), hit.specs(), false);
            page.addView(tags);
            LinearLayout who = (LinearLayout) sharer();
            who.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            who.setPadding(dp(10), dp(5), dp(12), 0);
            page.addView(who);
        }
    }

    /** A book: its cover beside title, author, year and language; then its formats. */
    private void buildBook() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(4), dp(10), 0);
        row.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(72)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(dp(10), 0, 0, 0);
        TextView title = text(name(), 15, getColor(R.color.text));
        title.setTypeface(BOLD);
        lines.addView(title);
        String author = book != null ? book.author : entry.folders;
        if (author.length() > 0) {
            TextView by = text(author, 12, getColor(R.color.text_muted));
            by.setPadding(0, dp(4), 0, 0);
            lines.addView(by);
        }
        if (book != null) {
            String more = ResultRow.join(book.year, book.language);
            if (more.length() > 0) {
                TextView facts = text(more, 11, getColor(R.color.text_muted));
                facts.setPadding(0, dp(3), 0, 0);
                lines.addView(facts);
            }
        }
        row.addView(lines, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(row);
        if (book == null) return;
        if (formats.size() < 2) {
            page.addView(tagLine(book.extension.toUpperCase(Locale.US), book.size, false));
            return;
        }
        TextView label = text("Format", 11, getColor(R.color.text_muted));
        label.setPadding(dp(12), dp(10), dp(12), dp(4));
        page.addView(label);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(8), 0, dp(8), 0);
        for (Books.Book one : formats) {
            TextView chip = text(one.extension.toUpperCase(Locale.US) + (one.size.length() > 0 ? "\n" + one.size : ""), 11, getColor(R.color.text));
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(BOLD);
            chip.setBackgroundResource(R.drawable.chip_pick);
            chip.setPadding(dp(4), dp(5), dp(4), dp(5));
            chip.setSingleLine(false);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            size.setMargins(dp(2), 0, dp(2), 0);
            chips.addView(chip, size);
            formatChips.add(chip);
        }
        page.addView(chips);
    }

    /** A format tag and what follows it: "[FLAC] 16/44.1 kHz · 4:12 · 30 MB". */
    private View tagLine(String format, String rest, boolean centre) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL | (centre ? Gravity.CENTER_HORIZONTAL : Gravity.START));
        line.setPadding(dp(centre ? 12 : 10), dp(8), dp(12), 0);
        if (format.length() > 0) {
            TextView tag = text(format, 9, getColor(R.color.on_tag));
            tag.setTypeface(BOLD);
            tag.setBackgroundResource(R.drawable.tag);
            tag.setPadding(dp(4), dp(1), dp(4), dp(1));
            line.addView(tag);
        }
        if (rest.length() > 0) {
            TextView facts = text(rest, 11, getColor(R.color.text_muted));
            facts.setPadding(dp(5), 0, 0, 0);
            facts.setSingleLine(true);
            facts.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(facts);
        }
        return line;
    }

    /** Who shares it, how fast, and whether they have a slot free: Soulseek, undisguised. */
    private View sharer() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(12), dp(6), dp(12), 0);
        TextView who = text(hit.source(), 11, getColor(R.color.text_muted));
        who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        who.setCompoundDrawablePadding(dp(3));
        who.setCompoundDrawablesRelative(icon(R.drawable.ic_person, R.color.text_muted, 11), null, null, null);
        row.addView(who);
        return row;
    }

    /** An album's files, numbered, with their lengths and each one's state. */
    private void buildTracks() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, 0);
        for (int i = 0; i < hit.tracks.size(); i++) {
            Music.Track track = hit.tracks.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(3), dp(12), dp(3));
            row.setMinimumHeight(dp(26));
            TextView number = text(String.valueOf(i + 1), 11, getColor(R.color.text_muted));
            number.setMinWidth(dp(18));
            row.addView(number);
            TextView title = text(track.title(), 12, getColor(R.color.text));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(4), 0, dp(6), 0);
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (track.seconds > 0) row.addView(text(Music.clock(track.seconds), 11, getColor(R.color.text_muted)));
            StateButton state = new StateButton(this);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(18), dp(18));
            size.leftMargin = dp(6);
            row.addView(state, size);
            trackStates.add(state);
            list.addView(row);
        }
        page.addView(list);
    }

    /** An earlier download's files, by name. */
    private void buildFiles() {
        List<String> files = new ArrayList<String>(entry.files);
        Collections.sort(files);
        StringBuilder names = new StringBuilder();
        for (String path : files) {
            if (names.length() > 0) names.append('\n');
            names.append(new java.io.File(path).getName());
        }
        TextView view = text(names, 11, getColor(R.color.text));
        view.setLineSpacing(dp(4), 1f);
        view.setPadding(dp(12), dp(8), dp(12), 0);
        page.addView(view);
    }

    // ---- Keeping it in step.

    private int state() {
        return Download.state(key);
    }

    @Override
    public void changed() {
        if (cover == null) return;
        Download.Job job = Download.job(key);
        int state = state();
        cover.set(kind(), name(), state == Download.FAILED, state);

        card.setVisibility(state == Download.IDLE ? View.GONE : View.VISIBLE);
        switch (state) {
            case Download.QUEUED: {
                int ahead = Download.active().indexOf(job);
                showCard(R.drawable.card, R.drawable.ic_schedule, "Queued", "",
                    ahead > 0 ? ahead + (ahead == 1 ? " download" : " downloads") + " ahead" : "");
                wavy.setVisibility(View.GONE);
                break;
            }
            case Download.RUNNING: {
                float progress = job.progress();
                String figure = progress >= 0 ? Math.round(progress * 100) + "%" : "";
                showCard(R.drawable.card, R.drawable.ic_download, job.phase, figure, runningLine(job));
                wavy.setVisibility(View.VISIBLE);
                wavy.set(progress, true, false);
                break;
            }
            case Download.SAVED: {
                List<String> files = job != null && job.state == Download.SAVED ? job.files : savedFiles();
                String where = files.isEmpty() ? "" : files.size() == 1 ? Files.shown(files.get(0)) : Files.folderOf(files);
                String top = where.indexOf('/') > 0 ? where.substring(0, where.indexOf('/')) : "";
                String title = job != null && job.note.length() > 0 ? job.note : top.length() > 0 ? "Saved to " + top : "Saved";
                if (job == null && entry != null && entry.time > 0) {
                    title = "Saved " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(entry.time));
                }
                showCard(R.drawable.card_success, R.drawable.ic_folder, title, "", where);
                wavy.setVisibility(View.GONE);
                break;
            }
            case Download.FAILED: {
                float progress = job.total > 0 ? Math.min(1f, job.done / (float) job.total) : 0;
                showCard(R.drawable.card_error, R.drawable.ic_error, "Failed", "", job.error == null ? "" : job.error);
                wavy.setVisibility(View.VISIBLE);
                wavy.set(progress, false, true);
                break;
            }
            default:
                break;
        }

        for (int i = 0; i < trackStates.size(); i++) {
            String name = hit.tracks.get(i).name();
            List<String> parts = Names.parts(name);
            boolean arrived = job != null && !parts.isEmpty() && job.arrived.contains(parts.get(parts.size() - 1));
            int track = arrived || state == Download.SAVED ? Download.SAVED
                : state == Download.RUNNING || state == Download.QUEUED ? Download.QUEUED : Download.IDLE;
            StateButton button = trackStates.get(i);
            button.set(track, -1, false);
            button.setVisibility(track == Download.IDLE ? View.INVISIBLE : View.VISIBLE);
        }
        for (int i = 0; i < formatChips.size(); i++) {
            boolean on = formats.get(i) == book;
            formatChips.get(i).setSelected(on);
            formatChips.get(i).setTextColor(getColor(on ? R.color.on_accent_container : R.color.text));
        }

        List<String> log = job != null ? job.log : Collections.<String>emptyList();
        StringBuilder text = new StringBuilder();
        for (int i = Math.max(0, log.size() - 60); i < log.size(); i++) {
            if (text.length() > 0) text.append('\n');
            text.append(log.get(i));
        }
        logView.setText(text);
        logView.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
        showKeys(state);
    }

    private List<String> savedFiles() {
        History.Entry saved = Download.saved(key);
        return saved == null ? Collections.<String>emptyList() : saved.files;
    }

    private void showCard(int background, int icon, String title, String figure, String line) {
        boolean saved = background == R.drawable.card_success;
        boolean failed = background == R.drawable.card_error;
        int ink = saved ? R.color.on_success_container : failed ? R.color.on_error_container : R.color.text;
        card.setBackgroundResource(background);
        cardTitle.setText(title);
        cardTitle.setTextColor(getColor(ink));
        cardTitle.setCompoundDrawablesRelative(icon(icon, ink, 13), null, null, null);
        cardFigure.setText(figure);
        cardFigure.setVisibility(figure.length() == 0 ? View.GONE : View.VISIBLE);
        cardLine.setText(line);
        cardLine.setTextColor(getColor(saved || failed ? ink : R.color.text_muted));
        cardLine.setVisibility(line.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** "5.4 of 8.4 MB · about 7 sec left", or the size to come while that isn't known. */
    private String runningLine(Download.Job job) {
        if (job.total <= 0) {
            long expected = hit != null ? hit.bytes() : 0;
            return expected > 0 ? Download.human(expected) : "";
        }
        long now = SystemClock.uptimeMillis();
        if (lastDone >= 0 && job.done > lastDone && now > lastTime) {
            double seen = (job.done - lastDone) * 1000.0 / (now - lastTime);
            rate = rate == 0 ? seen : rate * 0.7 + seen * 0.3;
        }
        if (job.done != lastDone) {
            lastDone = job.done;
            lastTime = now;
        }
        String line = Download.human(job.done) + " of " + Download.human(job.total);
        if (rate > 0 && job.total > job.done) {
            long seconds = Math.round((job.total - job.done) / rate);
            line += " · about " + (seconds < 60 ? Math.max(1, seconds) + " sec" : (seconds + 30) / 60 + " min") + " left";
        }
        return line;
    }

    private void showKeys(int state) {
        boolean isBook = Download.BOOK.equals(kind());
        switch (state) {
            case Download.SAVED:
                keys("Options", isBook ? R.drawable.ic_book : R.drawable.ic_play, isBook ? "Read" : "Play", "Back");
                break;
            case Download.FAILED:
                keys("Options", R.drawable.ic_refresh, "Retry", "Back");
                break;
            case Download.QUEUED:
            case Download.RUNNING:
                keys("Options", 0, null, "Back");
                break;
            default:
                if (hit == null && book == null) {
                    keys("Options", 0, null, "Back");
                } else {
                    String what = book != null ? "Get " + book.extension.toUpperCase(Locale.US) : "Get";
                    keys("Options", R.drawable.ic_download, what, "Back");
                }
        }
    }

    // ---- Doing things.

    private void ok() {
        switch (state()) {
            case Download.SAVED:
                MainActivity.open(this, key);
                return;
            case Download.QUEUED:
            case Download.RUNNING:
                return;
            default:
                get();
        }
    }

    private void get() {
        if (hit == null && book == null) return;
        if (!login.ready()) {
            note("No command center");
            return;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE);
            return;
        }
        if (hit != null) Download.get(login, hit);
        else Download.get(login, book);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != ASK_STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) get();
        else note("No storage access");
    }

    /** Left and Right: the book's previous or next format. */
    private void switchFormat(int step) {
        if (formats.size() < 2) return;
        int i = formats.indexOf(book);
        int next = Math.max(0, Math.min(formats.size() - 1, i + step));
        if (next == i) return;
        book = formats.get(next);
        key = Download.key(book);
        changed();
    }

    private void options() {
        Sheet sheet = new Sheet(this);
        final Download.Job job = Download.job(key);
        if (job != null && (job.state == Download.QUEUED || job.state == Download.RUNNING)) {
            sheet.add(R.drawable.ic_close, "Cancel download", null, () -> Download.cancel(job));
        }
        if (job != null && job.state == Download.FAILED) {
            sheet.add(R.drawable.ic_close, "Forget it", null, () -> Download.cancel(job));
        }
        if (state() == Download.SAVED && (hit != null || book != null)) {
            sheet.add(R.drawable.ic_download, "Get it again", null, () -> get());
        }
        sheet.add(R.drawable.ic_history, "Downloads", null,
            () -> startActivity(new android.content.Intent(this, DownloadsActivity.class)));
        sheet.show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        if (Ui.isOk(code)) {
            if (first) ok();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                if (!down) options();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) body.smoothScrollBy(0, (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * dp(40));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (first) switchFormat(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }
}
