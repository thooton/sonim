package com.canvas.xp3.net;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Several Canvas requests at once, with their results in the order asked.
 *
 * Each request spends nearly all its time waiting on the network, and a sync
 * is one request per class, so one after another a term of five classes is
 * five round trips back to back on a slow phone connection. A few at a time
 * is most of the gain without asking Canvas to throttle the session.
 *
 * Each task succeeds or fails on its own, so one unreadable class is still a
 * partial result rather than none. An interrupt stops them all.
 */
public final class Parallel {

    /** At most this many requests in flight. */
    public static final int WIDTH = 6;

    public interface Task<T> {
        T run() throws Exception;
    }

    public static final class Outcome<T> {
        public final T value;
        public final Exception error;

        Outcome(T value, Exception error) {
            this.value = value;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    private Parallel() {}

    public static <T> List<Outcome<T>> all(List<Task<T>> tasks) throws InterruptedException {
        List<Outcome<T>> out = new ArrayList<>();
        if (tasks.isEmpty()) return out;
        if (tasks.size() == 1) {
            out.add(once(tasks.get(0)));
            return out;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(WIDTH, tasks.size()), runnable -> {
            Thread thread = new Thread(runnable, "canvas-request");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (final Task<T> task : tasks) futures.add(pool.submit(task::run));
            for (Future<T> future : futures) {
                try {
                    out.add(new Outcome<T>(future.get(), null));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InterruptedException) throw (InterruptedException) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    out.add(new Outcome<T>(null, (Exception) cause));
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> Outcome<T> once(Task<T> task) throws InterruptedException {
        try {
            return new Outcome<T>(task.run(), null);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new Outcome<T>(null, e);
        }
    }
}
