package com.delta.xp3;

import com.delta.xp3.data.Attached;
import com.delta.xp3.data.Carry;
import com.delta.xp3.data.Chat;
import com.delta.xp3.data.Folders;
import com.delta.xp3.data.Queued;
import com.delta.xp3.data.RemoteFile;
import com.delta.xp3.data.Setup;
import com.delta.xp3.data.Store;
import com.delta.xp3.data.Transcript;
import com.delta.xp3.net.Center;
import com.delta.xp3.net.CloudAuth;
import com.delta.xp3.net.Cloud;
import com.delta.xp3.net.Config;
import com.xp3.common.Http;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything with a network or a disk behind it, in one place.
 *
 * The command center client is rebuilt whenever its address or token is
 * changed from the Files tab. The cloud accounts come from it too: their
 * logins stay there, and each account gets a cloud client of its own that
 * fetches its tokens from there. A session belongs to the account it was
 * started on. Which one that is comes from the session lists, is kept with
 * the saved chats and transcripts, and is looked up again when it is not
 * known.
 *
 * New sessions go to the first account, in the command center's order, that
 * is not known to be out of usage, after its usage report confirms it has
 * room. An account found to be out, here or by a session's stream, is skipped
 * until its limit lifts. A session on it can move to another (`canMove`,
 * `move`), and a message written to it meanwhile waits on the phone until
 * the limit lifts (`enqueue`, `due`).
 */
final class Backend {

    static final String NO_LOGIN = "No command center set. Add one from the Files tab's menu.";
    static final String NO_CENTER = "No command center set.";
    static final String NO_ACCOUNTS =
        "No cloud accounts on the command center yet. Add one with delta accounts login on a computer.";

    /** Transcripts kept on the phone besides the open one. */
    private static final int KEEP_SESSIONS = 20;

    private final Store store;
    private final File downloads;
    private final Http http = new Http();
    /** Guarded by this: both halves are rewritten from different threads. */
    private Config config;
    /** Guarded by this. Null without a command center. */
    private Center center;
    /** The accounts as last listed, in order; null before the first listing. Guarded by this. */
    private List<Center.Account> accounts;
    /** A cloud client per account id, all for the current command center. Guarded by this. */
    private final Map<String, Cloud> clouds = new HashMap<String, Cloud>();
    /** The account each known session runs on. Guarded by this. */
    private final Map<String, String> owners = new HashMap<String, String>();
    /** Until when accounts are known to be out of usage, in epoch milliseconds, by id. Guarded by this. */
    private final Map<String, Long> limits;
    /** Whose sessions (an account's, or the backup ones) could not be listed last time, and why; null if all could. Guarded by this. */
    private String listProblem;
    /** Held while an old login is handed over, so it is handed over once. */
    private final Object handing = new Object();
    /** Messages waiting for their account's limit to lift. Guarded by this. */
    private final List<Queued> queue;
    /** The backup sessions as last fetched, for when the command center can't list them. Guarded by this. */
    private List<Chat> lastBackup = new ArrayList<Chat>();

    Backend(File directory, File downloads) {
        store = new Store(directory);
        this.downloads = downloads;
        config = login();
        center = config.centerReady() ? new Center(http, config.server(), config.token()) : null;
        limits = store.limits();
        queue = store.queue();
        for (Chat chat : store.chats()) {
            if (chat.account != null) owners.put(chat.id, chat.account);
        }
    }

    /**
     * The saved login, except where this APK carries a bundled command center
     * it has not adopted yet. The revision marker is what stops the same APK
     * putting it back after it has been changed from the Files tab.
     */
    private Config login() {
        Config saved = store.settings();
        Config bundled;
        try {
            bundled = Config.blank().withCenter(Bundled.SERVER, Bundled.TOKEN);
        } catch (RuntimeException e) {
            return saved;
        }
        String revision = Bundled.REVISION;
        if (!bundled.centerReady() || revision.equals(store.bundledRevision(Store.CENTER))) return saved;
        Config next = saved.withCenterOf(bundled);
        try {
            store.saveSettings(next);
            store.saveBundledRevision(Store.CENTER, revision);
        } catch (IOException e) {
            // Still good for this run; the next launch adopts it again.
        }
        return next;
    }

    /** Whether there is somewhere to get the cloud accounts from. */
    boolean ready() {
        return centerReady();
    }

    synchronized boolean centerReady() {
        return center != null;
    }

    /** The command center's address, for showing; null without one. */
    synchronized String server() {
        return center == null ? null : config.server();
    }

    /** The first message of a new session: the Delta setup, then what was typed. */
    String firstMessage(String typed) {
        return Setup.wrap(typed);
    }

    // ------------------------------------------------------------------
    // Saved sessions
    // ------------------------------------------------------------------

    String openSession() {
        return store.openSession();
    }

    Store.Saved session(String id) {
        Store.Saved saved = store.session(id);
        if (saved.account != null) {
            synchronized (this) {
                if (!owners.containsKey(id)) owners.put(id, saved.account);
            }
        }
        return saved;
    }

    List<Chat> savedChats() {
        return store.chats();
    }

    /** Worker thread; callers pass a snapshot, never the live transcript. */
    void save(String id, String title, Transcript snapshot) {
        try {
            store.saveSession(id, title, owner(id), snapshot);
            store.saveOpenSession(id);
        } catch (IOException e) {
            // Still on screen and in memory; the next change tries again.
        }
    }

    /** Worker thread. */
    void saveChats(List<Chat> chats) {
        List<Chat> known = new ArrayList<Chat>();
        for (Chat chat : chats) {
            String account = chat.account == null ? owner(chat.id) : chat.account;
            known.add(account == null ? chat : chat.on(account));
        }
        try {
            store.saveChats(known);
        } catch (IOException e) {
            // Only a cache; the next refresh writes it again.
        }
    }

    /** Worker thread. The ids of messages whose pictures are still shown somewhere. */
    Set<String> prune(String open) {
        return store.prune(KEEP_SESSIONS, open);
    }

    // ------------------------------------------------------------------
    // The cloud accounts
    // ------------------------------------------------------------------

    /**
     * The accounts, listed afresh, after handing the command center any
     * login this phone kept from before it held them. Worker thread.
     */
    List<Center.Account> accounts() throws IOException, InterruptedException {
        Center center = center();
        handOver(center);
        List<Center.Account> listed = center.accounts();
        synchronized (this) {
            if (center == this.center) accounts = listed;
        }
        return listed;
    }

    /** Lists the accounts once in the background, so `canMove` knows them. Worker thread. */
    void warm() {
        try {
            synchronized (this) {
                if (accounts != null || center == null) return;
            }
            accounts();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The next listing tries again.
        }
    }

    /**
     * Versions before the command center kept the logins held the cloud login
     * here. It is handed over once; the command center checks it, and from
     * then on renews it. One the provider no longer takes is dropped too.
     */
    private void handOver(Center center) throws InterruptedException {
        synchronized (handing) {
            handOverOnce(center);
        }
    }

    private void handOverOnce(Center center) throws InterruptedException {
        Config held;
        synchronized (this) {
            held = config;
        }
        if (!held.hasCloud()) return;
        try {
            center.addAccount(held.accessToken(), held.refreshToken(), held.expiresAt());
        } catch (Center.Refused e) {
            // Dead already; there is nothing to keep.
        } catch (IOException e) {
            return; // Kept for the next try.
        }
        synchronized (this) {
            config = config.withoutCloud();
            held = config;
        }
        try {
            store.saveSettings(held);
        } catch (IOException e) {
            // Handed over again next launch, which replaces it in place.
        }
    }

    private synchronized Cloud cloud(String account) throws IOException {
        if (center == null) throw new IOException(NO_CENTER);
        Cloud cloud = clouds.get(account);
        if (cloud == null) {
            cloud = new Cloud(http, CloudAuth.of(center, account), config.server(), config.token());
            clouds.put(account, cloud);
        }
        return cloud;
    }

    private synchronized String owner(String session) {
        return session == null ? null : owners.get(session);
    }

    /** The client for the account a session runs on, finding out which that is if need be. */
    private Cloud cloudFor(String session) throws IOException, InterruptedException {
        String account = owner(session);
        if (account == null) {
            List<Center.Account> listed = accounts();
            if (listed.size() == 1) {
                account = listed.get(0).id;
            } else {
                sessions();
                account = owner(session);
            }
            if (account == null) throw new IOException("None of the cloud accounts has this session any more.");
            synchronized (this) {
                owners.put(session, account);
            }
        }
        return cloud(account);
    }

    /**
     * Picks the account a new session starts on: the first in order that is
     * not known to be out, once its usage report says it has room. A report
     * that can't be had doesn't rule an account out; a login that no longer
     * works does.
     */
    private String pick() throws IOException, InterruptedException {
        List<Center.Account> listed = accounts();
        if (listed.isEmpty()) throw new IOException(NO_ACCOUNTS);
        long now = System.currentTimeMillis();
        long soonest = Long.MAX_VALUE;
        IOException refused = null;
        for (Center.Account account : listed) {
            long known = limitOf(account.id);
            if (known > now) {
                soonest = Math.min(soonest, known);
                continue;
            }
            long until;
            try {
                until = center().limitedUntil(account.id);
            } catch (Center.Refused e) {
                refused = e;
                continue;
            } catch (IOException e) {
                until = 0;
            }
            if (until > now) {
                if (limit(account.id, until)) saveLimits();
                soonest = Math.min(soonest, until);
                continue;
            }
            return account.id;
        }
        if (soonest == Long.MAX_VALUE && refused != null) throw refused;
        throw new IOException((listed.size() == 1 ? "The cloud account has" : "Every cloud account has")
            + " reached its usage limit. " + (listed.size() == 1 ? "It is" : "The first is")
            + " free again " + when(soonest) + ".");
    }

    private synchronized long limitOf(String account) {
        Long until = limits.get(account);
        return until == null ? 0 : until.longValue();
    }

    private synchronized boolean limit(String account, long until) {
        if (limitOf(account) >= until) return false;
        limits.put(account, Long.valueOf(until));
        return true;
    }

    /**
     * A session's stream said its account is out until `until`. Returns
     * whether that is news, in which case the caller saves the limits.
     */
    synchronized boolean limited(String session, long until) {
        String account = owners.get(session);
        if (account == null || until <= System.currentTimeMillis()) return false;
        return limit(account, until);
    }

    /** Worker thread. */
    void saveLimits() {
        Map<String, Long> snapshot = new LinkedHashMap<String, Long>();
        long now = System.currentTimeMillis();
        synchronized (this) {
            for (Map.Entry<String, Long> entry : limits.entrySet()) {
                if (entry.getValue().longValue() > now) snapshot.put(entry.getKey(), entry.getValue());
            }
        }
        try {
            store.saveLimits(snapshot);
        } catch (IOException e) {
            // Known for this run; a usage check finds it again otherwise.
        }
    }

    /**
     * Whether a session's account is out of usage while another account is
     * not known to be, so the conversation can go on there. Main thread; it
     * only looks at what is already known.
     */
    synchronized boolean canMove(String session) {
        String account = owners.get(session);
        long now = System.currentTimeMillis();
        if (account == null || limitOf(account) <= now || accounts == null) return false;
        for (Center.Account other : accounts) {
            if (!other.id.equals(account) && limitOf(other.id) <= now) return true;
        }
        return false;
    }

    /** When a session's account is free again, or 0 if it is not known to be out. */
    synchronized long limitedUntil(String session) {
        String account = owners.get(session);
        long until = account == null ? 0 : limitOf(account);
        return until > System.currentTimeMillis() ? until : 0;
    }

    // ------------------------------------------------------------------
    // Messages waiting for a limit to lift
    // ------------------------------------------------------------------

    /** How long after a limit lifts a waiting message goes, so it doesn't arrive a moment early. */
    static final long QUEUE_GRACE_MS = 60L * 1000L;

    /**
     * Holds a message for a session whose account is out of usage, to go out
     * once the limit has lifted. Main thread; the caller saves the queue.
     */
    synchronized void enqueue(String session, String uuid, String text, List<String> files, int pictures) {
        long at = Math.max(limitedUntil(session), System.currentTimeMillis()) + QUEUE_GRACE_MS;
        queue.add(new Queued(session, uuid, text, files, pictures, at, 0));
    }

    /** Puts back a message whose send failed, to try again in a minute. */
    synchronized void retry(Queued message) {
        queue.add(message.failed(System.currentTimeMillis() + QUEUE_GRACE_MS));
    }

    /**
     * Takes out the messages that are due, oldest first. One whose account
     * turns out to be out for longer (a weekly limit after a 5-hour one, say)
     * waits on for the new time instead.
     */
    synchronized List<Queued> due() {
        long now = System.currentTimeMillis();
        List<Queued> ready = new ArrayList<Queued>();
        for (int i = 0; i < queue.size(); i++) {
            Queued message = queue.get(i);
            if (message.at > now) continue;
            long until = limitedUntil(message.session);
            if (until > 0) {
                queue.set(i, message.at(until + QUEUE_GRACE_MS));
                continue;
            }
            ready.add(message);
        }
        queue.removeAll(ready);
        return ready;
    }

    /** Takes out every message waiting for a session, for it to go with the conversation elsewhere. */
    synchronized List<Queued> takeQueued(String session) {
        List<Queued> taken = new ArrayList<Queued>();
        for (Queued message : queue) if (message.session.equals(session)) taken.add(message);
        queue.removeAll(taken);
        return taken;
    }

    /** Puts messages taken out by `takeQueued` back, after a move that didn't happen. */
    synchronized void requeue(List<Queued> messages) {
        queue.addAll(0, messages);
    }

    /** How many messages wait for a session. */
    synchronized int queuedFor(String session) {
        int count = 0;
        for (Queued message : queue) if (message.session.equals(session)) count++;
        return count;
    }

    /** When the next waiting message is due, or 0 if none waits. */
    synchronized long nextDue() {
        long next = 0;
        for (Queued message : queue) if (next == 0 || message.at < next) next = message.at;
        return next;
    }

    /** Worker thread. */
    void saveQueue() {
        List<Queued> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<Queued>(queue);
        }
        try {
            store.saveQueue(snapshot);
        } catch (IOException e) {
            // Still waiting in memory; the next change writes it again.
        }
    }

    /** "at 3:05 PM", or "on Tue at 3:05 PM" when that is not within the day. */
    static String when(long at) {
        String time = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(at));
        if (at - System.currentTimeMillis() < 20L * 3600L * 1000L) return "at " + time;
        return "on " + new SimpleDateFormat("EEE", Locale.US).format(new Date(at)) + " at " + time;
    }

    /** "3:05 PM", or "Tue 3:05 PM" when that is not within the day. */
    static String clock(long at) {
        String pattern = at - System.currentTimeMillis() < 20L * 3600L * 1000L ? "h:mm a" : "EEE h:mm a";
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(at));
    }

    /** One account on the Accounts tab: its usage, or why that couldn't be had. */
    static final class AccountUsage {
        final Center.Account account;
        /** Null when `problem` says why. */
        final Center.Usage usage;
        final String problem;

        AccountUsage(Center.Account account, Center.Usage usage, String problem) {
            this.account = account;
            this.usage = usage;
            this.problem = problem;
        }
    }

    /**
     * Every account with its usage, in the order new sessions try them. An
     * account found to be full is written down, so the next new session
     * skips it straight away. Worker thread.
     */
    List<AccountUsage> usage() throws IOException, InterruptedException {
        List<Center.Account> listed = accounts();
        Center center = center();
        List<AccountUsage> all = new ArrayList<AccountUsage>();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (Center.Account account : listed) {
            try {
                Center.Usage usage = center.usage(account.id);
                long until = usage.limitedUntil();
                if (until > now && limit(account.id, until)) changed = true;
                all.add(new AccountUsage(account, usage, null));
            } catch (IOException e) {
                all.add(new AccountUsage(account, null, e.getMessage()));
            }
        }
        if (changed) saveLimits();
        return all;
    }

    /** Until when an account is known to be out of usage, or 0. */
    synchronized long accountLimit(String account) {
        long until = limitOf(account);
        return until > System.currentTimeMillis() ? until : 0;
    }

    // ------------------------------------------------------------------
    // The cloud. All blocking; call from a worker.
    // ------------------------------------------------------------------

    /**
     * Starts a session: a backup one on the command center, which writes its
     * setup itself, or a cloud one on the account `pick` chooses. `files` are
     * names on the command center that go with the message.
     */
    String create(boolean backup, String title, String uuid, String text, List<File> pictures,
        List<String> files) throws IOException, InterruptedException {
        if (backup) return center().create(title, uuid, Attached.wrap(text, files), read(pictures));
        String account = pick();
        String id = cloud(account).create(title, uuid, firstMessage(Attached.wrap(text, files)), read(pictures));
        synchronized (this) {
            owners.put(id, account);
        }
        return id;
    }

    /**
     * Carries the conversation of `from`, whose account is out of usage, to a
     * new session on an account with room, and returns the new session's id.
     * The whole conversation is written out (Carry) and stored on the command
     * center; the new session's first message names it for `delta resume`
     * (which saves it to a file for the agent to read),
     * carries the pictures it mentions, and then whatever the person wrote
     * that had not gone out yet.
     */
    String move(String from, String title, String uuid, String text, List<File> pictures, List<String> files)
        throws IOException, InterruptedException {
        List<Map<String, Object>> history = cloudFor(from).history(from);
        String account = pick();
        Carry.Built carried = Carry.build(title, history);
        String stored = center().putConversation(carried.text);
        String block = Carry.block(title, stored);
        String typed = Attached.wrap(text, files);
        String message = typed.length() == 0 ? block : block + "\n\n" + typed;
        List<byte[]> jpegs = new ArrayList<byte[]>(carried.pictures);
        jpegs.addAll(read(pictures));
        String id = cloud(account).create(title, uuid, firstMessage(message), jpegs);
        synchronized (this) {
            owners.put(id, account);
        }
        return id;
    }

    void send(String session, String uuid, String text, List<File> pictures, List<String> files)
        throws IOException, InterruptedException {
        if (Chat.backup(session)) {
            center().send(session, uuid, Attached.wrap(text, files), read(pictures));
            return;
        }
        cloudFor(session).send(session, uuid, Attached.wrap(text, files), read(pictures));
    }

    private static List<byte[]> read(List<File> pictures) throws IOException {
        List<byte[]> jpegs = new ArrayList<byte[]>();
        for (File picture : pictures) {
            try {
                jpegs.add(Pictures.read(picture));
            } catch (IOException e) {
                throw new IOException("A picture could not be read back");
            }
        }
        return jpegs;
    }

    void interrupt(String session) throws IOException, InterruptedException {
        if (Chat.backup(session)) center().interrupt(session);
        else cloudFor(session).interrupt(session);
    }

    void answer(String session, String requestId, boolean allow, Map<String, Object> input)
        throws IOException, InterruptedException {
        cloudFor(session).answer(session, requestId, allow, input);
    }

    /**
     * Every session, every account's and the backup ones, most recently
     * active first. An account whose sessions can't be listed is left out
     * (see `listProblem`); when the backup sessions can't be, the last list
     * of them stands in. Only when nothing can be listed is it an error.
     */
    List<Chat> sessions() throws IOException, InterruptedException {
        List<Center.Account> listed = accounts();
        Center center = center();
        List<Chat> all = new ArrayList<Chat>();
        IOException failure = null;
        String problem = null;
        boolean any = false;
        for (Center.Account account : listed) {
            try {
                for (Chat chat : cloud(account.id).sessions()) all.add(chat.on(account.id));
                any = true;
            } catch (IOException e) {
                if (failure == null) failure = e;
                if (problem == null) problem = account.email + ": " + e.getMessage();
            }
        }
        List<Chat> backups;
        try {
            backups = center.sessions();
            any = true;
            synchronized (this) {
                if (center == this.center) lastBackup = backups;
            }
        } catch (IOException e) {
            if (failure == null) failure = e;
            if (problem == null) problem = "Backup sessions: " + e.getMessage();
            synchronized (this) {
                backups = center == this.center ? lastBackup : new ArrayList<Chat>();
            }
        }
        all.addAll(backups);
        synchronized (this) {
            listProblem = problem;
            for (Chat chat : all) if (chat.account != null) owners.put(chat.id, chat.account);
        }
        if (!any) throw listed.isEmpty() && failure == null ? new IOException(NO_ACCOUNTS) : failure;
        Collections.sort(all, (a, b) -> a.updated == b.updated ? 0 : a.updated > b.updated ? -1 : 1);
        return all;
    }

    /** Which account's sessions the last listing had to leave out, and why; null if none. */
    synchronized String listProblem() {
        return listProblem;
    }

    /** Archives a cloud session; a backup session is deleted, with its computer. */
    void archive(String session) throws IOException, InterruptedException {
        if (Chat.backup(session)) center().deleteSession(session);
        else cloudFor(session).archive(session);
    }

    /** A link for the session, not yet started. Null without a command center. */
    Link link(String session, long after, Link.Listener listener) {
        Center center;
        synchronized (this) {
            center = this.center;
        }
        if (center == null) return null;
        if (Chat.backup(session)) return new Link(center, session, after, listener);
        return new Link((id, from) -> cloudFor(id).open(id, from), session, after, listener);
    }

    // ------------------------------------------------------------------
    // The command center. All blocking; call from a worker.
    // ------------------------------------------------------------------

    /**
     * Checks a new address and token against the server, as `delta set-auth`
     * does, and only keeps them if it accepts them. A blank token keeps the
     * current one. IllegalArgumentException says what is wrong with the input.
     */
    void connectCenter(String server, String token) throws IOException, InterruptedException {
        Config next;
        synchronized (this) {
            String bearer = token == null || token.trim().length() == 0 ? config.token() : token;
            next = config.withCenter(server, bearer);
        }
        new Center(http, next.server(), next.token()).check();
        synchronized (this) {
            config = config.withCenterOf(next);
            next = config;
            center = new Center(http, next.server(), next.token());
            // The accounts and their clients belonged to the old one.
            accounts = null;
            clouds.clear();
            lastBackup = new ArrayList<Chat>();
        }
        store.saveSettings(next);
    }

    List<RemoteFile> files() throws IOException, InterruptedException {
        return center().list();
    }

    void deleteFile(String name) throws IOException, InterruptedException {
        center().delete(name);
    }

    /**
     * Fetches a file into the downloads folder. It is written beside its final
     * name and renamed into place, so a cancelled or broken download never
     * leaves a half file that looks finished.
     */
    void download(RemoteFile file, Center.Progress progress) throws IOException, InterruptedException {
        Center center = center();
        if (!downloads.isDirectory() && !downloads.mkdirs()) throw new IOException("Could not open the app's storage");
        File part = new File(downloads, "." + file.name + ".part");
        try {
            OutputStream out = new FileOutputStream(part);
            try {
                center.download(file.name, out, progress);
                ((FileOutputStream) out).getFD().sync();
            } finally {
                out.close();
            }
            File target = downloaded(file.name);
            target.delete();
            if (!part.renameTo(target)) throw new IOException("Couldn't save the file.");
        } finally {
            part.delete();
        }
    }

    /**
     * Sends a file from the phone to the command center and returns the name
     * it has there: its own, or, when that is taken, the first free
     * "name (2).ext" and so on, since the command center never replaces a
     * file. Worker thread.
     */
    String upload(File source, Center.Progress progress) throws IOException, InterruptedException {
        Center center = center();
        if (!source.isFile()) throw new IOException("That file is gone.");
        Set<String> taken = new HashSet<String>();
        for (RemoteFile file : center.list()) taken.add(file.name);
        for (int n = 1; n <= 100; n++) {
            String name = RemoteFile.numbered(source.getName(), n);
            // Someone else may take a name between the list and the upload;
            // the command center refuses it then, and the next one is tried.
            if (taken.contains(name)) continue;
            InputStream in = new FileInputStream(source);
            try {
                if (center.upload(name, in, source.length(), progress)) return name;
            } finally {
                in.close();
            }
        }
        throw new IOException("The command center has too many files called " + source.getName() + ".");
    }

    /**
     * Moves a downloaded file into one of the phone's shared folders, under a
     * name nothing there has yet, and returns where it went. The app's
     * storage and shared storage are different file systems, so it is copied
     * beside its final name, renamed into place, and only then removed from
     * here. Worker thread.
     */
    File moveOut(String name, File folder) throws IOException {
        File source = downloaded(name);
        if (!source.isFile()) throw new IOException("The phone's copy is gone.");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Couldn't open " + folder.getName() + ".");
        File part = new File(folder, "." + name + ".part");
        try {
            InputStream in = new FileInputStream(source);
            try {
                FileOutputStream out = new FileOutputStream(part);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
                    out.getFD().sync();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            File target = Folders.unused(folder, name);
            if (!part.renameTo(target)) throw new IOException("Couldn't save the file.");
            source.delete();
            return target;
        } finally {
            part.delete();
        }
    }

    /** Where a downloaded file lives, whether or not it is there. */
    File downloaded(String name) {
        return new File(downloads, name);
    }

    Map<String, RemoteFile> downloads() {
        return store.downloads();
    }

    /** Worker thread; callers pass a copy. */
    void saveDownloads(Map<String, RemoteFile> snapshot) {
        try {
            store.saveDownloads(snapshot);
        } catch (IOException e) {
            // The files themselves are still there; the next change tries again.
        }
    }

    private synchronized Center center() throws IOException {
        if (center == null) throw new IOException(NO_CENTER);
        return center;
    }
}
