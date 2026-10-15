package com.delta.xp3.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A message held on the phone because its session's account is out of usage.
 *
 * Sent now, it would only run into the same limit, so it waits and goes out
 * once the limit has lifted. Its pictures are the ones saved when it was
 * written, under its uuid; the files it names are already on the command
 * center.
 */
public final class Queued {

    public final String session;
    public final String uuid;
    public final String text;
    public final List<String> files;
    public final int pictures;
    /** When to send it, in epoch milliseconds. */
    public final long at;
    /** Sends that have failed so far. */
    public final int tries;

    public Queued(String session, String uuid, String text, List<String> files, int pictures, long at, int tries) {
        if (!Chat.validId(session)) throw new IllegalArgumentException("Not a session id");
        if (uuid == null || uuid.length() == 0) throw new IllegalArgumentException("No uuid");
        this.session = session;
        this.uuid = uuid;
        this.text = text == null ? "" : text;
        this.files = Collections.unmodifiableList(new ArrayList<String>(files == null ? new ArrayList<String>() : files));
        this.pictures = Math.max(0, pictures);
        this.at = at;
        this.tries = Math.max(0, tries);
    }

    public Queued at(long next) {
        return new Queued(session, uuid, text, files, pictures, next, tries);
    }

    public Queued failed(long next) {
        return new Queued(session, uuid, text, files, pictures, next, tries + 1);
    }
}
