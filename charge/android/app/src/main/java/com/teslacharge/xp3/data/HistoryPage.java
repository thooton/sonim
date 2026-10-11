package com.teslacharge.xp3.data;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One page of charging history and whether Tesla has another behind it. The
 * flag travels with the rows rather than being asked for separately: the page
 * that answers "what are the next twenty sessions" is the same request that
 * answers "are there any".
 */
public final class HistoryPage {
    public final List<JSONObject> sessions;
    public final boolean hasMore;

    HistoryPage(List<JSONObject> sessions, boolean hasMore) {
        this.sessions = Collections.unmodifiableList(new ArrayList<>(sessions));
        this.hasMore = hasMore;
    }
}
