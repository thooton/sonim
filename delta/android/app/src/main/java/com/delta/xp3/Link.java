package com.delta.xp3;

import com.delta.xp3.net.EventSource;
import com.xp3.common.Http;
import com.delta.xp3.net.Sse;

import java.io.IOException;

/**
 * Keeps the session's event stream open while the app is on screen.
 *
 * The stream is long-lived and the phone's network is not, so a drop is
 * normal rather than an error: the link waits a little, longer each time, and
 * reopens from the last sequence number it saw, which the cloud (or, for a
 * backup session, the command center) replays from.
 * Frames are handed over on this link's own thread; the listener hops to the
 * main thread itself.
 */
final class Link implements Runnable {

    interface Listener {
        void frame(Sse.Frame frame);

        /** Whether the stream is open. Called on every change. */
        void online(boolean up);
    }

    private static final long FIRST_RETRY_MS = 2000;
    private static final long LAST_RETRY_MS = 30000;

    private final EventSource source;
    private final String session;
    private final Listener listener;
    private final Thread thread;
    private volatile long after;
    private volatile boolean stopped;
    private volatile Http.Stream current;

    Link(EventSource source, String session, long after, Listener listener) {
        this.source = source;
        this.session = session;
        this.after = after;
        this.listener = listener;
        thread = new Thread(this, "cloud-link");
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /**
     * Stops for good. Closing the stream is what unblocks a read in progress;
     * it is done on a thread of its own, because closing a connection can
     * touch the network and this is called from the main thread.
     */
    void stop() {
        stopped = true;
        final Http.Stream stream = current;
        thread.interrupt();
        if (stream == null) return;
        Thread closer = new Thread(() -> stream.close(), "cloud-link-close");
        closer.setDaemon(true);
        closer.start();
    }

    @Override
    public void run() {
        long wait = FIRST_RETRY_MS;
        while (!stopped) {
            try {
                Http.Stream stream = source.open(session, after);
                current = stream;
                if (stopped) break;
                listener.online(true);
                wait = FIRST_RETRY_MS;
                Sse sse = new Sse(stream.stream());
                Sse.Frame frame;
                while (!stopped && (frame = sse.next()) != null) {
                    long id = sequence(frame.id);
                    if (id > after) after = id;
                    listener.frame(frame);
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                // Dropped, timed out, or refused: all of them mean try again.
            } catch (RuntimeException e) {
                // A frame the listener could not take is not worth the link.
            } finally {
                Http.Stream stream = current;
                current = null;
                if (stream != null) stream.close();
            }
            if (stopped) break;
            listener.online(false);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                break;
            }
            wait = Math.min(wait * 2, LAST_RETRY_MS);
        }
    }

    private static long sequence(String id) {
        try {
            return id == null || id.length() == 0 ? 0 : Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
