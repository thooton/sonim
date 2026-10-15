package com.list.xp3;

import com.list.xp3.data.Checklist;
import com.list.xp3.data.Item;
import com.list.xp3.data.Store;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Everything with a disk behind it, in one place.
 *
 * The checklist lives here too, so the screen and the save worker share one
 * object.
 */
final class Backend {

    private final Store store;
    private final Checklist checklist;

    Backend(File directory) {
        store = new Store(directory);
        // Versions with the chat assistant kept a Claude login and the
        // conversation here; neither is used any more.
        store.forgetChat();
        checklist = new Checklist(store.list());
    }

    Checklist checklist() {
        return checklist;
    }

    /** Writes the list down. Worker thread; callers pass a snapshot. */
    void saveList(List<Item> items) {
        try {
            store.saveList(items);
        } catch (IOException e) {
            // The list is still on screen and in memory; the next change will
            // try again.
        }
    }
}
