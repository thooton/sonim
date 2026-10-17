package com.source.xp3.data;

import com.xp3.common.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The last few searches, newest first, each with what it searched for. */
public final class Recents {

    public static final int LIMIT = 4;

    /** One search: Music.SONG, Music.ALBUM or "book", and what was typed. */
    public static final class Search {
        public final String mode;
        public final String query;

        public Search(String mode, String query) {
            this.mode = mode;
            this.query = query;
        }
    }

    private final List<Search> searches = new ArrayList<Search>();

    public List<Search> searches() {
        return Collections.unmodifiableList(searches);
    }

    /** Puts a search first; the same words searched again move up rather than repeat. */
    public void add(String mode, String query) {
        String trimmed = query.trim();
        if (trimmed.length() == 0) return;
        String key = trimmed.toLowerCase(Locale.ROOT);
        for (int i = searches.size() - 1; i >= 0; i--) {
            Search old = searches.get(i);
            if (old.mode.equals(mode) && old.query.toLowerCase(Locale.ROOT).equals(key)) searches.remove(i);
        }
        searches.add(0, new Search(mode, trimmed));
        while (searches.size() > LIMIT) searches.remove(searches.size() - 1);
    }

    public void clear() {
        searches.clear();
    }

    public String toJson() {
        List<Object> list = new ArrayList<Object>();
        for (Search search : searches) list.add(Json.map("mode", search.mode, "query", search.query));
        return Json.write(list);
    }

    public static Recents parse(String json) {
        Recents recents = new Recents();
        if (json == null || json.length() == 0) return recents;
        try {
            for (Object value : Json.array(Json.parse(json))) {
                if (!(value instanceof Map)) continue;
                Map<String, Object> map = Json.object(value);
                String mode = Json.text(map, "mode");
                String query = Json.text(map, "query");
                if (mode != null && query != null && query.trim().length() > 0 && recents.searches.size() < LIMIT) {
                    recents.searches.add(new Search(mode, query.trim()));
                }
            }
        } catch (RuntimeException e) {
            return new Recents();
        }
        return recents;
    }
}
