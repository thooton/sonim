package com.mail.xp3;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import com.mail.xp3.net.Address;
import com.mail.xp3.net.Attachment;
import com.mail.xp3.net.Change;
import com.mail.xp3.net.Conversation;
import com.mail.xp3.net.Folder;
import com.mail.xp3.net.Gmail;
import com.mail.xp3.net.Me;
import com.mail.xp3.net.Message;
import com.mail.xp3.net.Mime;
import com.mail.xp3.net.Outgoing;
import com.mail.xp3.net.People;
import com.xp3.common.Login;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * One screen, three ways: a folder's conversations, one conversation, or a
 * message being written.
 *
 * The list: Up and Down pick (Down past the last loads more), OK opens, Left
 * and Right step through Gmail's own folders, Menu has everything else.
 * A conversation: Up and Down scroll, Left and Right jump between its
 * messages, OK or Menu for reply, forward, archive and the rest, Back to the
 * list. Writing: the fields as usual, OK starts a new line in the message,
 * Menu sends, Back offers to save a draft.
 */
public final class MainActivity extends BaseActivity {

    private static final String PREFS = "mail";
    private static final int LIST = 0;
    private static final int READ = 1;
    private static final int WRITE = 2;
    private static final int ASK_STORAGE = 1;
    /** A long message is cut here on screen; its whole text still goes into a reply. */
    private static final int MAX_SHOWN = 20000;
    /** The list is fetched again on coming back to the app after this long. */
    private static final long STALE_MS = 120000;

    /** Work for the background thread: returns what to do with its result on the main thread. */
    private interface Work {
        Runnable call() throws Exception;
    }

    private TextView titleView;
    private TextView countView;
    private TextView statusView;
    private ListView listView;
    private ScrollView reader;
    private TextView readerText;
    private View compose;
    private View ccRow;
    private EditText toField;
    private EditText ccField;
    private EditText subjectField;
    private EditText bodyField;
    private final ConversationAdapter adapter = new ConversationAdapter();
    private final ExecutorService worker = Ui.worker("mail");
    /** Archive, delete and the rest go one at a time, in order, beside whatever is loading. */
    private final ExecutorService changes = Ui.worker("mail-changes");

    private Login login = Login.blank();
    private Gmail gmail;
    private Me me;
    private People people = new People();
    private List<Folder> labels = new ArrayList<Folder>();
    private boolean labelsLoaded;

    private int screen = LIST;

    // The list.
    private Folder folder = Folder.INBOX;
    private final List<Conversation> items = new ArrayList<Conversation>();
    private String next;
    private int picked;
    private int unread = -1;
    private long loadedAt;

    // The conversation open, whole, and where each message starts in its text.
    private Conversation reading;
    private final Map<String, Conversation> whole = new HashMap<String, Conversation>();
    private final List<Integer> starts = new ArrayList<Integer>();
    private boolean showQuoted;

    // The message being written, as it began (to tell whether anything was typed), and where it was begun.
    private Outgoing writing;
    private Outgoing writingStart;
    private int writeFrom = LIST;
    private boolean sending;

    private Attachment pendingSave;
    private Future<?> job;
    private int generation;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        titleView = (TextView) findViewById(R.id.title);
        countView = (TextView) findViewById(R.id.count);
        statusView = (TextView) findViewById(R.id.status);
        listView = (ListView) findViewById(R.id.list);
        reader = (ScrollView) findViewById(R.id.reader);
        readerText = (TextView) findViewById(R.id.reader_text);
        compose = findViewById(R.id.compose);
        ccRow = findViewById(R.id.cc_row);
        toField = (EditText) findViewById(R.id.to);
        ccField = (EditText) findViewById(R.id.cc);
        subjectField = (EditText) findViewById(R.id.subject);
        bodyField = (EditText) findViewById(R.id.body);
        listView.setAdapter(adapter);
        Family.list(listView);

        SharedPreferences prefs = prefs();
        login = loadLogin(prefs);
        people = People.parse(prefs.getString("people", ""));

        Outgoing kept = Outgoing.parse(prefs.getString("compose", ""));
        if (kept != null) {
            write(kept, LIST, "Your unfinished message was kept.");
            writingStart = Outgoing.compose("");
        } else {
            refresh();
        }
        if (!login.ready()) say("No command center: Menu → Command center.");
        else if (kept == null) load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (screen == LIST && !busy() && loadedAt > 0 && System.currentTimeMillis() - loadedAt > STALE_MS) load();
    }

    @Override
    protected void onPause() {
        // What is being written survives the app being stopped.
        if (screen == WRITE && writing != null && !sending) {
            collect();
            prefs().edit().putString("compose", writing.toJson()).apply();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        changes.shutdown();
        if (gmail != null) gmail.close();
        super.onDestroy();
    }

    /**
     * The bundled login is adopted once per build that carries a new one;
     * otherwise whatever was saved (perhaps typed in from the menu) is kept.
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

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** One client per login, so its access token is reused. */
    private Gmail gmail() {
        if (gmail == null) gmail = new Gmail(login);
        return gmail;
    }

    // ---- Background work ----

    /** Runs `work` off the main thread, in place of anything already running. */
    private void start(String doing, final Work work) {
        stop();
        final int mine = ++generation;
        say(doing);
        job = worker.submit(() -> {
            Runnable done;
            try {
                done = work.call();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                final String message = message(e);
                done = () -> say(message);
            }
            final Runnable then = done;
            runOnUiThread(() -> {
                if (mine != generation) return;
                job = null;
                then.run();
            });
        });
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private boolean busy() {
        return job != null;
    }

    /** Abandons what is running; whatever it was doing is never shown. */
    private void stop() {
        if (job == null) return;
        job.cancel(true);
        job = null;
        generation++;
    }

    // ---- The list ----

    /** The folder's first page, keeping the pick where it was. */
    private void load() {
        if (!login.ready()) {
            askLogin();
            return;
        }
        final Gmail client = gmail();
        final Folder from = folder;
        final boolean wantLabels = !labelsLoaded;
        start(items.isEmpty() ? "Loading " + from.name + "…" : "Checking for mail…", () -> {
            final Me user = client.me();
            final List<Folder> found = wantLabels ? client.labels() : null;
            final Gmail.Page page = client.list(from, null);
            final int count = from == Folder.INBOX ? client.unread() : -1;
            return () -> {
                me = user;
                if (found != null) {
                    labels = found;
                    labelsLoaded = true;
                }
                items.clear();
                items.addAll(page.conversations);
                next = page.next;
                unread = count;
                picked = Math.max(0, Math.min(picked, items.size() - 1));
                loadedAt = System.currentTimeMillis();
                learn(page.conversations);
                say(items.isEmpty() ? "Nothing in " + from.name + "." : "");
                refresh();
            };
        });
    }

    /** The next page, after the ones already shown. */
    private void more() {
        if (busy() || next == null) return;
        final Gmail client = gmail();
        final Folder from = folder;
        final String after = next;
        start("Loading more…", () -> {
            final Gmail.Page page = client.list(from, after);
            return () -> {
                boolean wasLast = picked == items.size() - 1;
                items.addAll(page.conversations);
                next = page.next;
                if (wasLast && picked + 1 < items.size()) picked++;
                learn(page.conversations);
                say("");
                refresh();
            };
        });
    }

    private void setFolder(Folder to) {
        stop();
        folder = to;
        items.clear();
        next = null;
        picked = 0;
        unread = -1;
        screen = LIST;
        refresh();
        load();
    }

    /** Left and Right on the list: the folder before or after this one among Gmail's own. */
    private void stepFolder(int by) {
        int at = -1;
        for (int i = 0; i < Folder.SYSTEM.size(); i++) if (Folder.SYSTEM.get(i).is(folder)) at = i;
        int count = Folder.SYSTEM.size();
        setFolder(Folder.SYSTEM.get(at < 0 ? 0 : (at + by + count) % count));
    }

    private void movePick(int by) {
        if (items.isEmpty()) return;
        int to = picked + by;
        if (to < 0) return;
        if (to >= items.size()) {
            more();
            return;
        }
        picked = to;
        refresh();
    }

    /** Remembers the people in these conversations, to offer when writing. */
    private void learn(List<Conversation> conversations) {
        boolean changed = false;
        for (Conversation conversation : conversations) changed |= people.seen(conversation.addresses(), me);
        if (changed) prefs().edit().putString("people", people.toJson()).apply();
    }

    private Conversation current() {
        return items.isEmpty() ? null : items.get(Math.min(picked, items.size() - 1));
    }

    // ---- Showing ----

    /** Puts the screen in step with the state. */
    private void refresh() {
        listView.setVisibility(screen == LIST ? View.VISIBLE : View.GONE);
        reader.setVisibility(screen == READ ? View.VISIBLE : View.GONE);
        compose.setVisibility(screen == WRITE ? View.VISIBLE : View.GONE);
        if (screen == WRITE) {
            String kind = writing.kind;
            titleView.setText(kind.equals(Outgoing.REPLY) ? "Reply" : kind.equals(Outgoing.FORWARD) ? "Forward"
                : kind.equals(Outgoing.DRAFT) ? "Draft" : "New message");
            countView.setText("");
            return;
        }
        titleView.setText(folder.name + (unread > 0 ? " (" + unread + ")" : ""));
        countView.setText(items.isEmpty() ? "" : (picked + 1) + "/" + items.size() + (next != null ? "+" : ""));
        adapter.notifyDataSetChanged();
        if (screen == LIST && !items.isEmpty()) listView.post(() -> listView.smoothScrollToPosition(picked));
    }

    /** The line under the bar; it takes no room while there is nothing to say. */
    private void say(String text) {
        statusView.setText(text);
        statusView.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    // ---- Reading ----

    private void open() {
        Conversation listed = current();
        if (listed == null) return;
        if (listed.onlyDrafts()) editDraft(listed);
        else show(listed);
    }

    /** A conversation, whole: at once when it was opened before, else its headers while it loads. */
    private void show(Conversation listed) {
        stop();
        screen = READ;
        showQuoted = false;
        final String id = listed.id;
        reading = whole.get(id);
        render(reading != null ? reading : listed, reading == null);
        refresh();
        if (reading != null) {
            say("");
            markRead(reading);
            return;
        }
        final Gmail client = gmail();
        start("Loading…", () -> {
            final Conversation full = client.conversation(id);
            return () -> {
                whole.put(id, full);
                reading = full;
                say("");
                if (screen == READ) render(full, false);
                markRead(full);
            };
        });
    }

    /** Opening an unread conversation reads it, as in Gmail. */
    private void markRead(Conversation conversation) {
        if (conversation.unread()) change(conversation, Change.READ, true);
    }

    private void closeReader() {
        stop();
        screen = LIST;
        reading = null;
        say("");
        refresh();
    }

    /**
     * The subject, then each message: who from, when and to whom, its text
     * (a reply's quote of the one before it hidden), and its files. A
     * conversation known only from the list shows its messages' headers
     * while the rest loads.
     */
    private void render(Conversation conversation, boolean partial) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        starts.clear();
        span(out, conversation.subject(), new TypefaceSpan("sans-serif-medium"), new RelativeSizeSpan(1.2f));
        int first = -1;
        List<Message> messages = conversation.messages;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (first < 0 && message.unread()) first = i;
            out.append("\n\n");
            starts.add(out.length());
            Avatar avatar = new Avatar(message.sender(), dp(26), dp(8), color(R.color.focus_fill), color(R.color.focus_rule));
            int name = out.length();
            span(out, message.sender(), new TypefaceSpan("sans-serif-medium"));
            if (message.draft()) span(out, "  Draft", new ForegroundColorSpan(color(R.color.draft)));
            out.append('\n');
            out.setSpan(avatar, name, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int date = out.length();
            span(out, longDate(message.time()) + recipients(message), new ForegroundColorSpan(color(R.color.text_muted)),
                new RelativeSizeSpan(0.88f));
            out.append('\n');
            out.setSpan(new LeadingMarginSpan.Standard(avatar.margin()), date, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append('\n');
            if (partial) {
                span(out, message.snippet(), new ForegroundColorSpan(color(R.color.text_muted)));
                continue;
            }
            Message.Content content = message.content();
            String body = content.body();
            boolean hidden = false;
            if (i > 0 && !showQuoted) {
                String cut = Mime.unquoted(body);
                if (cut != null) {
                    body = cut;
                    hidden = true;
                }
            }
            if (body.length() > MAX_SHOWN) body = body.substring(0, MAX_SHOWN) + "…";
            if (body.trim().length() == 0) span(out, "(no text)", new ForegroundColorSpan(color(R.color.text_muted)));
            else out.append(body);
            if (hidden) {
                out.append('\n');
                span(out, "[quoted text hidden]", new ForegroundColorSpan(color(R.color.text_muted)));
            }
            for (Attachment attachment : content.attachments) {
                out.append('\n');
                span(out, "📎 " + attachment.label(), new ForegroundColorSpan(color(R.color.text_muted)));
            }
        }
        readerText.setText(out);
        // The first unread message, or the last, as Gmail opens a conversation.
        final int at = first >= 0 ? first : messages.size() - 1;
        if (at <= 0) reader.scrollTo(0, 0);
        else readerText.post(() -> scrollToMessage(at));
    }

    /** " · to Jane, Bob +2". */
    private String recipients(Message message) {
        List<Address> to = new ArrayList<Address>(message.to());
        to.addAll(message.cc());
        if (to.isEmpty()) return "";
        StringBuilder out = new StringBuilder(" · to ");
        int shown = Math.min(2, to.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append(", ");
            out.append(me != null && me.is(to.get(i).email) ? "me" : to.get(i).brief());
        }
        if (to.size() > shown) out.append(" +").append(to.size() - shown);
        return out.toString();
    }

    private void scrollToMessage(int index) {
        Layout layout = readerText.getLayout();
        if (layout == null || index < 0 || index >= starts.size()) return;
        reader.scrollTo(0, layout.getLineTop(layout.getLineForOffset(starts.get(index))) + readerText.getPaddingTop());
    }

    /** The message at the top of the screen. */
    private int messageInView() {
        Layout layout = readerText.getLayout();
        if (layout == null) return Math.max(0, starts.size() - 1);
        int y = reader.getScrollY() + dp(8);
        int found = 0;
        for (int i = 0; i < starts.size(); i++) {
            if (layout.getLineTop(layout.getLineForOffset(starts.get(i))) <= y) found = i;
        }
        return found;
    }

    /** Left and Right in a conversation: the message before or after the one in view. */
    private void stepMessage(int by) {
        if (starts.isEmpty()) return;
        int to = messageInView() + by;
        if (by < 0) {
            // Back to the top of the one in view first, if it is scrolled into.
            Layout layout = readerText.getLayout();
            int here = messageInView();
            if (layout != null && reader.getScrollY() > layout.getLineTop(layout.getLineForOffset(starts.get(here))) + dp(8)) {
                to = here;
            }
        }
        if (to < 0) {
            reader.smoothScrollTo(0, 0);
            return;
        }
        if (to >= starts.size()) return;
        scrollToMessage(to);
    }

    /** Whom a reply answers: the message in view, or the latest when that is a draft. */
    private Message replyTarget() {
        if (reading == null) return null;
        int at = Math.min(messageInView(), reading.messages.size() - 1);
        Message message = reading.messages.get(at);
        return message.draft() ? reading.latest() : message;
    }

    private static void span(SpannableStringBuilder out, String text, Object... spans) {
        int start = out.length();
        out.append(text);
        for (Object span : spans) out.setSpan(span, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** For the list: "3:04 PM" today, "Oct 13" this year, else "10/13/25". */
    private static String shortDate(long time) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        String pattern = now.get(Calendar.YEAR) != then.get(Calendar.YEAR) ? "M/d/yy"
            : now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ? "h:mm a" : "MMM d";
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(time));
    }

    /** For a message: "Tue, Oct 13, 3:04 PM", with the year when it isn't this one. */
    private static String longDate(long time) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        boolean thisYear = then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR);
        return new SimpleDateFormat(thisYear ? "EEE, MMM d, h:mm a" : "EEE, MMM d, yyyy, h:mm a", Locale.US).format(new Date(time));
    }

    // ---- Changing conversations ----

    /** The phone's copies of a conversation: the list's and the whole one. */
    private List<Conversation> copies(String id) {
        List<Conversation> out = new ArrayList<Conversation>();
        for (Conversation item : items) if (item.id.equals(id)) out.add(item);
        Conversation full = whole.get(id);
        if (full != null && !out.contains(full)) out.add(full);
        if (reading != null && reading.id.equals(id) && !out.contains(reading)) out.add(reading);
        return out;
    }

    /**
     * Makes a change at once on the phone and then in Gmail. One that takes
     * the conversation out of this folder (archive in the inbox, say) takes it
     * off the list, and out of the reader back to the list, with the pick on
     * the one after it.
     */
    private void change(Conversation conversation, final Change change, boolean quiet) {
        final String id = conversation.id;
        boolean wasUnread = conversation.unread();
        for (Conversation copy : copies(id)) change.apply(copy);
        if (folder == Folder.INBOX && unread >= 0 && conversation.has("INBOX")) {
            if (change == Change.READ && wasUnread) unread = Math.max(0, unread - 1);
            if (change == Change.UNREAD && !wasUnread) unread++;
        }
        boolean gone = !folder.holds(conversation);
        if (gone) removeFromList(id);
        if (!quiet) toast(change.done);
        if (screen == READ && (gone || change == Change.UNREAD)) closeReader();
        else refresh();
        final Gmail client = gmail();
        changes.submit(() -> {
            try {
                client.change(id, change);
            } catch (Exception e) {
                final String message = message(e);
                runOnUiThread(() -> {
                    say("Couldn't do that: " + message);
                    whole.remove(id);
                    if (screen == LIST) load();
                });
            }
        });
    }

    private void removeFromList(String id) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (!items.get(i).id.equals(id)) continue;
            items.remove(i);
            if (picked > i) picked--;
        }
        picked = Math.max(0, Math.min(picked, items.size() - 1));
    }

    /** Ticks the user's labels on and off a conversation. */
    private void chooseLabels(final Conversation conversation) {
        if (labels.isEmpty()) {
            toast("There are no labels of your own.");
            return;
        }
        final String[] names = new String[labels.size()];
        final boolean[] on = new boolean[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            names[i] = labels.get(i).name;
            on[i] = conversation.has(labels.get(i).label);
        }
        final boolean[] was = on.clone();
        new AlertDialog.Builder(this)
            .setTitle("Labels")
            .setMultiChoiceItems(names, on, (dialog, which, checked) -> on[which] = checked)
            .setPositiveButton("OK", (dialog, which) -> {
                final List<String> add = new ArrayList<String>();
                final List<String> remove = new ArrayList<String>();
                for (int i = 0; i < labels.size(); i++) {
                    if (on[i] && !was[i]) add.add(labels.get(i).label);
                    if (!on[i] && was[i]) remove.add(labels.get(i).label);
                }
                if (add.isEmpty() && remove.isEmpty()) return;
                final String id = conversation.id;
                for (Conversation copy : copies(id)) copy.relabel(add, remove);
                if (!folder.holds(conversation)) {
                    removeFromList(id);
                    if (screen == READ) closeReader();
                }
                refresh();
                toast("Labels changed");
                final Gmail client = gmail();
                changes.submit(() -> {
                    try {
                        client.relabel(id, add, remove);
                    } catch (Exception e) {
                        final String message = message(e);
                        runOnUiThread(() -> say("Couldn't change the labels: " + message));
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ---- Writing ----

    private void write(Outgoing out, int from, String status) {
        stop();
        writing = out;
        writingStart = Outgoing.parse(out.toJson());
        writeFrom = from;
        screen = WRITE;
        sending = false;
        toField.setText(out.to);
        ccField.setText(out.cc);
        subjectField.setText(out.subject);
        bodyField.setText(out.text);
        ccRow.setVisibility(out.cc.length() > 0 ? View.VISIBLE : View.GONE);
        EditText focus = out.to.length() == 0 ? toField : out.subject.length() == 0 ? subjectField : bodyField;
        focus.requestFocus();
        focus.setSelection(focus == bodyField && !out.kind.equals(Outgoing.DRAFT) ? 0 : focus.length());
        refresh();
        say(status);
    }

    private void compose(String to) {
        write(Outgoing.compose(to), screen, "");
    }

    private void reply(boolean all) {
        Message target = replyTarget();
        if (target == null || me == null) return;
        write(Outgoing.reply(target, me, all, TimeZone.getDefault()), READ, "");
    }

    private void forward() {
        Message target = replyTarget();
        if (target == null || me == null) return;
        Outgoing out = Outgoing.forward(target, me, TimeZone.getDefault());
        write(out, READ, out.attachments.isEmpty() ? "" : "With " + files(out.attachments.size()) + ".");
    }

    private static String files(int count) {
        return count == 1 ? "1 attachment" : count + " attachments";
    }

    /** A draft, opened to carry on with: Gmail's id for it is looked up first, so sending sends it. */
    private void editDraft(Conversation conversation) {
        final String id = conversation.id;
        final Gmail client = gmail();
        final int from = screen;
        final Conversation known = whole.get(id);
        start("Opening the draft…", () -> {
            Conversation full = known != null ? known : client.conversation(id);
            final Conversation loaded = full;
            final Message draft = loaded.draft();
            if (draft == null) throw new IOException("That draft was sent or deleted.");
            final String draftId = client.draftId(draft.id);
            if (draftId == null) throw new IOException("That draft was sent or deleted.");
            return () -> {
                whole.put(id, loaded);
                Outgoing out = Outgoing.draft(draft, draftId);
                write(out, from, out.attachments.isEmpty() ? "" : "With " + files(out.attachments.size()) + ".");
            };
        });
    }

    /** The fields into the message being written. */
    private void collect() {
        writing.to = toField.getText().toString().trim();
        writing.cc = ccField.getText().toString().trim();
        writing.subject = subjectField.getText().toString();
        writing.text = bodyField.getText().toString();
    }

    private void send() {
        if (sending) return;
        collect();
        final List<Address> to;
        try {
            to = writing.recipients();
        } catch (IllegalArgumentException e) {
            say(e.getMessage());
            return;
        }
        final Outgoing out = writing;
        final Gmail client = gmail();
        sending = true;
        start("Sending…", () -> {
            try {
                client.send(out);
            } catch (IOException | RuntimeException e) {
                final String message = message(e);
                return () -> {
                    sending = false;
                    say(message);
                };
            }
            return () -> {
                sending = false;
                people.wrote(to);
                prefs().edit().putString("people", people.toJson()).apply();
                toast("Sent");
                finishWriting(true);
            };
        });
    }

    private void saveDraft() {
        if (sending) return;
        collect();
        final Outgoing out = writing;
        final Gmail client = gmail();
        sending = true;
        start("Saving the draft…", () -> {
            try {
                client.save(out);
            } catch (IOException | RuntimeException e) {
                final String message = message(e);
                return () -> {
                    sending = false;
                    say(message);
                };
            }
            return () -> {
                sending = false;
                toast("Saved to Drafts");
                finishWriting(true);
            };
        });
    }

    /** Throws it away; a draft saved in Gmail goes too, once confirmed. */
    private void discard() {
        if (sending) return;
        final String draftId = writing.draftId;
        if (draftId.length() == 0) {
            toast("Discarded");
            finishWriting(false);
            return;
        }
        new AlertDialog.Builder(this)
            .setMessage("Delete this draft from Gmail too?")
            .setPositiveButton("Delete", (dialog, which) -> {
                final Gmail client = gmail();
                changes.submit(() -> {
                    try {
                        client.discard(draftId);
                    } catch (Exception e) {
                        final String message = message(e);
                        runOnUiThread(() -> say("Couldn't delete the draft: " + message));
                    }
                });
                toast("Draft deleted");
                finishWriting(true);
            })
            .setNegativeButton("Keep it", (dialog, which) -> finishWriting(false))
            .show();
    }

    /** Back while writing: straight out when nothing was typed, else save, discard or carry on. */
    private void leaveWriting() {
        if (sending) return;
        collect();
        if (writing.untouched(writingStart)) {
            finishWriting(false);
            return;
        }
        new AlertDialog.Builder(this)
            .setItems(new String[] {"Save draft", "Discard", "Keep writing"}, (dialog, which) -> {
                if (which == 0) saveDraft();
                else if (which == 1) discard();
            })
            .show();
    }

    /** Back to where writing began; what it changed (a reply, a draft) is fetched again. */
    private void finishWriting(boolean changedMail) {
        prefs().edit().remove("compose").apply();
        String thread = writing.threadId;
        writing = null;
        View focus = getCurrentFocus();
        if (focus != null) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
        if (changedMail && thread.length() > 0) whole.remove(thread);
        if (writeFrom == READ && reading != null) {
            if (changedMail) {
                // Open it again, whole, with the reply in it.
                whole.remove(reading.id);
                show(reading);
            } else {
                screen = READ;
                say("");
                refresh();
            }
            return;
        }
        screen = LIST;
        say("");
        refresh();
        if (changedMail || items.isEmpty()) load();
    }

    /** Someone known, into To or Cc (whichever has the cursor), matching what is typed after the last comma. */
    private void addRecipient() {
        final EditText field = ccField.hasFocus() ? ccField : toField;
        final String text = field.getText().toString();
        int comma = text.lastIndexOf(',');
        final String head = comma < 0 ? "" : text.substring(0, comma + 1) + " ";
        String typed = comma < 0 ? text : text.substring(comma + 1);
        if (typed.contains("@") && typed.trim().matches("\\S+@\\S+\\.\\S+")) typed = "";
        List<Address> found = people.matching(typed);
        if (found.isEmpty() && typed.trim().length() > 0) {
            toast("Nobody matches \"" + typed.trim() + "\"; showing everyone.");
            found = people.matching("");
        }
        if (found.isEmpty()) {
            toast("No addresses yet: they are learned from your mail.");
            return;
        }
        final List<Address> choices = found.size() > 100 ? found.subList(0, 100) : found;
        final String keep = typed.length() == 0 ? text.trim() : head.trim();
        List<String> shown = new ArrayList<String>();
        for (Address address : choices) shown.add(address.name.length() > 0 ? address.name + " <" + address.email + ">" : address.email);
        listDialog(field == ccField ? "Copy to" : "To", shown, which -> {
            String before = keep.length() == 0 ? "" : keep.endsWith(",") ? keep + " " : keep + ", ";
            field.setText(before + choices.get(which).email + ", ");
            field.requestFocus();
            field.setSelection(field.length());
        }, null);
    }

    // ---- Files and links ----

    private void chooseAttachment() {
        if (reading == null) return;
        final List<Attachment> all = new ArrayList<Attachment>();
        for (Message message : reading.messages) all.addAll(message.content().attachments);
        if (all.isEmpty()) return;
        List<String> shown = new ArrayList<String>();
        for (Attachment attachment : all) shown.add(attachment.label());
        listDialog("Save to Download", shown, which -> saveAttachment(all.get(which)), null);
    }

    private void saveAttachment(final Attachment attachment) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSave = attachment;
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, ASK_STORAGE);
            return;
        }
        final Gmail client = gmail();
        start("Saving " + attachment.name + "…", () -> {
            byte[] data = client.attachment(attachment);
            File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Couldn't open the Download folder.");
            final File file = unused(folder, Mime.safeName(attachment.name));
            OutputStream out = new FileOutputStream(file);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            MediaScannerConnection.scanFile(this, new String[] {file.getPath()}, null, null);
            return () -> {
                say("");
                toast("Saved to Download/" + file.getName());
            };
        });
    }

    /** `name`, or "name (2).ext" and so on when that is taken. */
    private static File unused(File folder, String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        File file = new File(folder, name);
        for (int i = 2; file.exists(); i++) file = new File(folder, stem + " (" + i + ")" + ext);
        return file;
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != ASK_STORAGE) return;
        Attachment attachment = pendingSave;
        pendingSave = null;
        if (attachment == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) saveAttachment(attachment);
        else toast("Mail needs storage access to save files");
    }

    /** The message in view's links: a web one opens in the browser, an address starts a message to it. */
    private void chooseLink() {
        Message target = replyTarget();
        if (target == null) return;
        final List<String[]> links = target.content().links();
        if (links.isEmpty()) {
            toast("No links in this message.");
            return;
        }
        List<String> shown = new ArrayList<String>();
        for (String[] link : links) shown.add(link[0].length() > 80 ? link[0].substring(0, 80) + "…" : link[0]);
        listDialog("Links", shown, which -> {
            String url = links.get(which)[1];
            if (url.toLowerCase(Locale.US).startsWith("mailto:")) {
                String address = Uri.decode(url.substring(7));
                int query = address.indexOf('?');
                compose(query >= 0 ? address.substring(0, query) : address);
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException e) {
                toast("Nothing on this phone can open that.");
            }
        }, null);
    }

    // ---- Keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        if (key == KeyEvent.KEYCODE_MENU) {
            if (!down) menu();
            return true;
        }
        if (screen == WRITE) return writeKey(event, key, down, first);
        if (screen == READ) return readKey(event, key, down, first);
        return listKey(event, key, down, first);
    }

    private boolean listKey(KeyEvent event, int key, boolean down, boolean first) {
        if (Ui.isOk(key)) {
            if (first) open();
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) movePick(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (first) stepFolder(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (busy()) {
                    if (!down) {
                        stop();
                        say("Stopped.");
                    }
                    return true;
                }
                if (!folder.is(Folder.INBOX)) {
                    if (!down) setFolder(Folder.INBOX);
                    return true;
                }
                return super.dispatchKeyEvent(event);
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private boolean readKey(KeyEvent event, int key, boolean down, boolean first) {
        if (Ui.isOk(key)) {
            if (first) menu();
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) reader.smoothScrollBy(0, (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * dp(60));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (first) stepMessage(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) closeReader();
                return true;
            default:
                return event.isPrintingKey() || super.dispatchKeyEvent(event);
        }
    }

    /**
     * OK moves on from To, Cc and the subject, and starts a new line in the
     * message. It acts on the press: with T9 composing a word, the keyboard
     * takes the first release to finish it.
     */
    private boolean writeKey(KeyEvent event, int key, boolean down, boolean first) {
        if (key == KeyEvent.KEYCODE_BACK) {
            if (!down && !event.isCanceled()) leaveWriting();
            return true;
        }
        if (Ui.isOk(key)) {
            if (!down) return true;
            View focus = getCurrentFocus();
            if (focus == bodyField) {
                int start = Math.max(0, bodyField.getSelectionStart());
                int end = Math.max(0, bodyField.getSelectionEnd());
                bodyField.getText().replace(Math.min(start, end), Math.max(start, end), "\n");
            } else if (first) {
                EditText to = focus == toField ? (ccRow.getVisibility() == View.VISIBLE ? ccField : subjectField)
                    : focus == ccField ? subjectField : bodyField;
                to.requestFocus();
                to.setSelection(to == bodyField ? 0 : to.length());
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- Menus ----

    private void menu() {
        final List<String> names = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();
        if (screen == WRITE) {
            if (sending) return;
            add(names, actions, "Send", this::send);
            add(names, actions, "Add recipient…", this::addRecipient);
            if (ccRow.getVisibility() != View.VISIBLE) {
                add(names, actions, "Add Cc", () -> {
                    ccRow.setVisibility(View.VISIBLE);
                    ccField.requestFocus();
                });
            }
            add(names, actions, "Save draft", this::saveDraft);
            add(names, actions, "Discard", () -> {
                collect();
                discard();
            });
        } else if (screen == READ) {
            final Conversation conversation = reading != null ? reading : current();
            if (busy()) {
                add(names, actions, "Stop", () -> {
                    stop();
                    say("");
                });
            }
            if (reading != null && me != null) {
                add(names, actions, "Reply", () -> reply(false));
                Message target = replyTarget();
                if (target != null && target.to().size() + target.cc().size() > 1) {
                    add(names, actions, "Reply all", () -> reply(true));
                }
                add(names, actions, "Forward", this::forward);
                if (reading.draft() != null) add(names, actions, "Edit draft", () -> editDraft(reading));
            }
            if (conversation != null) conversationItems(names, actions, conversation);
            if (reading != null) {
                boolean files = false;
                for (Message message : reading.messages) files |= !message.content().attachments.isEmpty();
                if (files) add(names, actions, "Save attachment…", this::chooseAttachment);
                add(names, actions, "Open link…", this::chooseLink);
                add(names, actions, showQuoted ? "Hide quoted text" : "Show quoted text", () -> {
                    showQuoted = !showQuoted;
                    int at = messageInView();
                    render(reading, false);
                    readerText.post(() -> scrollToMessage(at));
                });
            }
            add(names, actions, "Back to " + folder.name, this::closeReader);
        } else {
            if (busy()) {
                add(names, actions, "Stop", () -> {
                    stop();
                    say("Stopped.");
                });
            }
            add(names, actions, "Write", () -> compose(""));
            Conversation conversation = current();
            if (conversation != null) conversationItems(names, actions, conversation);
            add(names, actions, "Search…", this::askSearch);
            add(names, actions, "Folders…", this::chooseFolder);
            add(names, actions, "Refresh", this::load);
            if (next != null) add(names, actions, "More", this::more);
            add(names, actions, "Command center…", this::askLogin);
        }
        new AlertDialog.Builder(this)
            .setItems(names.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
            .show();
    }

    private void conversationItems(List<String> names, List<Runnable> actions, final Conversation conversation) {
        for (final Change change : Change.offered(conversation)) {
            add(names, actions, change.label, () -> change(conversation, change, false));
        }
        if (!labels.isEmpty()) add(names, actions, "Labels…", () -> chooseLabels(conversation));
    }

    private static void add(List<String> names, List<Runnable> actions, String name, Runnable action) {
        names.add(name);
        actions.add(action);
    }

    private void chooseFolder() {
        final List<Folder> all = new ArrayList<Folder>(Folder.SYSTEM);
        all.addAll(labels);
        List<String> names = new ArrayList<String>();
        for (Folder each : all) names.add(each.name);
        listDialog("Folders", names, which -> setFolder(all.get(which)), null);
    }

    private void askSearch() {
        LinearLayout box = vertical();
        int pad = dp(12);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Words, from:jane, has:attachment…");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        if (folder.kind.equals("search")) {
            field.setText(folder.query);
            field.setSelection(field.length());
        }
        box.addView(field);
        new AlertDialog.Builder(this)
            .setTitle("Search mail")
            .setView(box)
            .setPositiveButton("Search", (dialog, which) -> {
                String query = field.getText().toString().trim();
                if (query.length() > 0) setFolder(Folder.search(query));
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** The fallback for a build without a bundled login. */
    private void askLogin() {
        LinearLayout box = vertical();
        int pad = dp(12);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText server = new EditText(this);
        server.setHint("https://…");
        server.setSingleLine(true);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(login.server() == null ? "" : login.server());
        final EditText token = new EditText(this);
        token.setHint(login.ready() ? "Token (blank keeps it)" : "Delta token");
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
                    if (gmail != null) gmail.close();
                    gmail = null;
                    me = null;
                    labelsLoaded = false;
                    whole.clear();
                    prefs().edit().putString("login", next.toJson()).apply();
                    say("Saved: " + next.host());
                    if (screen == LIST) load();
                } catch (IllegalArgumentException e) {
                    say(e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** A conversation: who, and when; the subject; the start of the latest message. Unread ones in bold. */
    private final class ConversationAdapter extends BaseAdapter {

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
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                int padH = getResources().getDimensionPixelSize(R.dimen.pad_h);
                int padV = dp(7);
                row = vertical();
                row.setBackgroundResource(com.xp3.common.ui.R.drawable.family_pick);
                row.setPadding(padH, padV, padH, padV);
                LinearLayout top = new LinearLayout(MainActivity.this);
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setGravity(Gravity.CENTER_VERTICAL);
                TextView who = line(R.dimen.text_name, R.color.text);
                top.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView when = line(R.dimen.text_meta, R.color.text_muted);
                when.setPadding(dp(6), 0, 0, 0);
                when.setCompoundDrawablePadding(dp(4));
                top.addView(when);
                row.addView(top);
                TextView subject = line(R.dimen.text_subject, R.color.text);
                subject.setPadding(0, dp(3), 0, 0);
                row.addView(subject);
                TextView snippet = line(R.dimen.text_meta, R.color.text_muted);
                snippet.setPadding(0, dp(2), 0, 0);
                row.addView(snippet);
            }
            Conversation conversation = items.get(position);
            boolean unread = conversation.unread();
            LinearLayout top = (LinearLayout) row.getChildAt(0);
            TextView who = (TextView) top.getChildAt(0);
            String people = conversation.people(me, folder == Folder.SENT);
            who.setText(people.length() == 0 ? "(nobody)" : people);
            who.setTypeface(unread ? Family.MEDIUM : Typeface.DEFAULT);
            who.setTextColor(color(conversation.onlyDrafts() ? R.color.draft : R.color.text));
            // What is new carries the accent: a dot, and the time beside it.
            TextView when = (TextView) top.getChildAt(1);
            when.setText(shortDate(conversation.time()));
            when.setTextColor(color(unread ? R.color.focus_rule : R.color.text_muted));
            when.setTypeface(unread ? Family.MEDIUM : Typeface.DEFAULT);
            when.setCompoundDrawablesRelativeWithIntrinsicBounds(unread ? getDrawable(R.drawable.unread_dot) : null, null, null, null);

            TextView subject = (TextView) row.getChildAt(1);
            SpannableStringBuilder line = new SpannableStringBuilder();
            if (conversation.starred()) span(line, "★ ", new ForegroundColorSpan(color(R.color.star)));
            line.append(conversation.subject());
            subject.setText(line);
            subject.setTypeface(unread ? Family.MEDIUM : Typeface.DEFAULT);

            TextView snippet = (TextView) row.getChildAt(2);
            snippet.setText(conversation.snippet());
            snippet.setVisibility(conversation.snippet().length() == 0 ? View.GONE : View.VISIBLE);
            row.setActivated(position == picked);
            return row;
        }

        private TextView line(int size, int textColor) {
            TextView view = new TextView(MainActivity.this);
            view.setTextColor(color(textColor));
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(size));
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setIncludeFontPadding(false);
            return view;
        }
    }
}
