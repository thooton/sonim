package com.delta.xp3;

import com.delta.xp3.data.Attached;
import com.delta.xp3.data.Chat;
import com.delta.xp3.data.Folders;
import com.delta.xp3.data.RemoteFile;
import com.delta.xp3.data.Setup;
import com.delta.xp3.data.Store;
import com.delta.xp3.data.Transcript;
import com.delta.xp3.net.Center;
import com.delta.xp3.net.CloudAuth;
import com.delta.xp3.net.Cloud;
import com.delta.xp3.net.Config;
import com.delta.xp3.net.Http;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything with a network or a disk behind it, in one place.
 *
 * The cloud client is built once, from the cloud login; a rotated login is
 * written back so the next launch starts from the live pair. The command
 * center client is rebuilt whenever its address or token is changed from the
 * Files tab.
 */
final class Backend {

    static final String NO_LOGIN = "This build has no cloud login.";
    static final String NO_CENTER = "No command center set.";

    /** Transcripts kept on the phone besides the open one. */
    private static final int KEEP_SESSIONS = 20;

    private final Store store;
    private final File downloads;
    private final Http http = new Http();
    /** Null when this build has no cloud login. */
    private final Cloud cloud;
    /** Guarded by this: both halves are rewritten from different threads. */
    private Config config;
    /** Guarded by this. Null without a command center. */
    private Center center;
    /** Each side's sessions as last fetched, for when it can't be reached. Lists thread only. */
    private List<Chat> lastCloud = new ArrayList<Chat>();
    private List<Chat> lastBackup = new ArrayList<Chat>();

    Backend(File directory, File downloads) {
        store = new Store(directory);
        this.downloads = downloads;
        config = login();
        center = config.centerReady() ? new Center(http, config.server(), config.token()) : null;
        if (!config.ready()) {
            cloud = null;
            return;
        }
        CloudAuth credentials = new CloudAuth(http, config.accessToken(),
            config.refreshToken(), config.expiresAt(), new CloudAuth.Rotated() {
                @Override
                public void store(String access, String refresh, long expiresAt) {
                    rotated(access, refresh, expiresAt);
                }
            });
        cloud = new Cloud(http, credentials);
    }

    /**
     * The logins to use: the saved ones, except where this APK carries a
     * bundled login it has not adopted yet. Each is adopted on its own, by its
     * own revision marker, which is what stops the stale copy of the cloud
     * login in the APK replacing one that has rotated since it was installed,
     * just because the command center changed.
     */
    private Config login() {
        Config saved = store.settings();
        Config bundled;
        try {
            bundled = Defaults.config();
        } catch (RuntimeException e) {
            return saved;
        }
        Config next = saved;
        String cloudRevision = Defaults.revision(Store.CLOUD);
        boolean adoptCloud = bundled.ready() && !cloudRevision.equals(store.bundledRevision(Store.CLOUD));
        if (adoptCloud) next = next.withCloudOf(bundled);
        String centerRevision = Defaults.revision(Store.CENTER);
        boolean adoptCenter = bundled.centerReady() && !centerRevision.equals(store.bundledRevision(Store.CENTER));
        if (adoptCenter) next = next.withCenterOf(bundled);
        if (adoptCloud || adoptCenter) {
            try {
                store.saveSettings(next);
                if (adoptCloud) store.saveBundledRevision(Store.CLOUD, cloudRevision);
                if (adoptCenter) store.saveBundledRevision(Store.CENTER, centerRevision);
            } catch (IOException e) {
                // Still good for this run; the next launch adopts it again.
            }
        }
        return next;
    }

    /**
     * Writes down a rotated login. Called on a network worker in the middle of
     * a request; the old refresh token no longer works, so losing this copy
     * would leave the next launch with a dead login.
     */
    private void rotated(String access, String refresh, long expiresAt) {
        try {
            Config next;
            synchronized (this) {
                config = config.withCloud(access, refresh, expiresAt);
                next = config;
            }
            store.saveSettings(next);
        } catch (IOException e) {
            // The renewed login remains usable in memory for this run.
        } catch (RuntimeException e) {
            // Bookkeeping must not break a request.
        }
    }

    boolean ready() {
        return cloud != null;
    }

    synchronized boolean centerReady() {
        return center != null;
    }

    /** The command center's address, for showing; null without one. */
    synchronized String server() {
        return center == null ? null : config.server();
    }

    /** The first message of a new session: the Delta setup, then what was typed. */
    synchronized String firstMessage(String typed) {
        return center == null ? Setup.wrap(typed, null, null) : Setup.wrap(typed, config.server(), config.token());
    }

    // ------------------------------------------------------------------
    // Saved sessions
    // ------------------------------------------------------------------

    String openSession() {
        return store.openSession();
    }

    Store.Saved session(String id) {
        return store.session(id);
    }

    List<Chat> savedChats() {
        return store.chats();
    }

    /** Worker thread; callers pass a snapshot, never the live transcript. */
    void save(String id, String title, Transcript snapshot) {
        try {
            store.saveSession(id, title, snapshot);
            store.saveOpenSession(id);
        } catch (IOException e) {
            // Still on screen and in memory; the next change tries again.
        }
    }

    /** Worker thread. */
    void saveChats(List<Chat> chats) {
        try {
            store.saveChats(chats);
        } catch (IOException e) {
            // Only a cache; the next refresh writes it again.
        }
    }

    /** Worker thread. The ids of messages whose pictures are still shown somewhere. */
    Set<String> prune(String open) {
        return store.prune(KEEP_SESSIONS, open);
    }

    // ------------------------------------------------------------------
    // The cloud. All blocking; call from a worker.
    // ------------------------------------------------------------------

    /**
     * Starts a session, in the cloud or (`backup`) on the command center,
     * which writes a backup session's setup itself. `files` are names on the
     * command center that go with the message.
     */
    String create(boolean backup, String title, String uuid, String text, List<File> pictures,
        List<String> files) throws IOException, InterruptedException {
        if (backup) return center().create(title, uuid, Attached.wrap(text, files), read(pictures));
        Cloud cloud = cloud();
        return cloud.create(title, uuid, firstMessage(Attached.wrap(text, files)), read(pictures));
    }

    void send(String session, String uuid, String text, List<File> pictures, List<String> files)
        throws IOException, InterruptedException {
        if (Chat.backup(session)) {
            center().send(session, uuid, Attached.wrap(text, files), read(pictures));
            return;
        }
        Cloud cloud = cloud();
        cloud.send(session, uuid, Attached.wrap(text, files), read(pictures));
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
        else cloud().interrupt(session);
    }

    void answer(String session, String requestId, boolean allow, Map<String, Object> input)
        throws IOException, InterruptedException {
        cloud().answer(session, requestId, allow, input);
    }

    /**
     * Every session, cloud and backup, most recently active first. When one
     * side can't be reached its sessions from the last refresh stand in, and
     * only when neither can is it an error.
     */
    List<Chat> sessions() throws IOException, InterruptedException {
        Center center;
        synchronized (this) {
            center = this.center;
        }
        IOException problem = null;
        boolean fetched = false;
        if (cloud != null) {
            try {
                lastCloud = cloud.sessions();
                fetched = true;
            } catch (IOException e) {
                problem = e;
            }
        }
        if (center != null) {
            try {
                lastBackup = center.sessions();
                fetched = true;
            } catch (IOException e) {
                if (problem == null) problem = e;
            }
        } else {
            lastBackup = new ArrayList<Chat>();
        }
        if (!fetched && problem != null) throw problem;
        List<Chat> all = new ArrayList<Chat>(lastCloud);
        all.addAll(lastBackup);
        Collections.sort(all, new Comparator<Chat>() {
            @Override
            public int compare(Chat a, Chat b) {
                return a.updated == b.updated ? 0 : a.updated > b.updated ? -1 : 1;
            }
        });
        return all;
    }

    /** Archives a cloud session; a backup session is deleted, with its computer. */
    void archive(String session) throws IOException, InterruptedException {
        if (Chat.backup(session)) center().deleteSession(session);
        else cloud().archive(session);
    }

    /** A link for the session, not yet started. Null without the login it needs. */
    Link link(String session, long after, Link.Listener listener) {
        if (Chat.backup(session)) {
            Center center;
            synchronized (this) {
                center = this.center;
            }
            return center == null ? null : new Link(center, session, after, listener);
        }
        return cloud == null ? null : new Link(cloud, session, after, listener);
    }

    private Cloud cloud() throws IOException {
        if (cloud == null) throw new IOException(NO_LOGIN);
        return cloud;
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
