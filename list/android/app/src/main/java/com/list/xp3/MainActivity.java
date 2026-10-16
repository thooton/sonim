package com.list.xp3;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.list.xp3.data.Checklist;
import com.list.xp3.data.Item;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Family;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * The checklist and the keys that edit it.
 *
 * The screen is built in code rather than inflated because it is a single
 * list. The thing that repeats, an item row, is the thing that has a layout.
 *
 * Everything with a disk behind it is behind Backend. The list is saved on
 * its own single thread so writes land in the order the edits were made.
 */
public final class MainActivity extends BaseActivity {

    /** Menu item ids. The menu is rebuilt on every press of Menu. */
    private static final int MENU_INSERT_ABOVE = 1;
    private static final int MENU_INSERT_BELOW = 2;
    private static final int MENU_EDIT = 3;
    private static final int MENU_DELETE = 4;
    private static final int MENU_ADD = 5;
    private static final int MENU_MOVE_UP = 6;
    private static final int MENU_MOVE_DOWN = 7;

    private Backend backend;
    private FrameLayout content;
    private TextView countView;

    private final ExecutorService saves = Ui.worker("list-save");

    // ------------------------------------------------------------------
    // List state
    // ------------------------------------------------------------------

    private ListView listView;
    private ItemAdapter listAdapter;
    /** The empty-list message, which holds focus when there are no rows. */
    private View emptyView;
    /** The highlighted row, kept across redraws. */
    private int listSelection;

    /**
     * The press of OK that confirmed a dialog.
     *
     * The text dialogs keep T9 prediction on, so they act on the press of OK
     * rather than its release (the keyboard can swallow the first release
     * while it commits a word). When the release does arrive, it arrives at
     * the list underneath, which would read it as OK and tick the item. Its
     * down time is the press's, so it can be recognised and ignored exactly.
     */
    private long consumedOkDownTime = -1;

    /**
     * What the open menu is about, captured when it was opened.
     *
     * The item is kept as well as its position, so an edit lands on the item
     * that was chosen even if the list has changed under the open menu.
     */
    private int menuPosition = -1;
    private Item menuItem;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        backend = new Backend(getFilesDir());

        content = findViewById(R.id.content);
        // Every context menu is answered here; see showMenu.
        registerForContextMenu(content);
        countView = findViewById(R.id.count);
        renderList(true);
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    private void renderList(boolean focus) {
        List<Item> items = backend.checklist().items();
        LinearLayout root = vertical();
        showCount(items);

        if (items.isEmpty()) {
            listAdapter = null;
            listView = null;
            TextView empty = Family.empty(this, "Nothing on the list", "Press Menu to add an item");
            // Focusable so the screen still has somewhere for keys to land,
            // and so Menu's popup has something to sit beside.
            empty.setFocusable(true);
            empty.setFocusableInTouchMode(true);
            root.addView(empty, Ui.weighted());
            Ui.fill(content, root);
            emptyView = empty;
            listSelection = 0;
            if (focus) empty.requestFocus();
            return;
        }

        emptyView = null;
        final ItemAdapter adapter = new ItemAdapter(this);
        adapter.replace(items);
        ListView list = listView(adapter);
        root.addView(list, Ui.weighted());
        wireList(list, adapter);
        Ui.fill(content, root);
        listAdapter = adapter;
        listView = list;

        int start = Math.max(0, Math.min(listSelection, items.size() - 1));
        listSelection = start;
        adapter.setSelectedPosition(start);
        list.setSelection(start);
        if (focus) list.requestFocus();
    }

    /**
     * Puts the highlight on `position` and repaints the rows in place.
     *
     * Used after an edit, so the row just added, edited or ticked is the one
     * under the cursor.
     */
    private void redrawList(int position) {
        listSelection = position;
        boolean focus =
            (listView != null && listView.hasFocus()) ||
            (emptyView != null && emptyView.hasFocus()) ||
            getCurrentFocus() == null;
        List<Item> items = backend.checklist().items();
        if (listAdapter == null || listView == null || items.isEmpty()) {
            renderList(true);
            return;
        }
        int at = Math.max(0, Math.min(position, items.size() - 1));
        listSelection = at;
        showCount(items);
        listAdapter.replace(items);
        listAdapter.setSelectedPosition(at);
        listView.setSelection(at);
        if (focus) listView.requestFocus();
    }

    private void wireList(final ListView list, final ItemAdapter adapter) {
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        Ui.onSelect(list, position -> {
            adapter.trackSelectedPosition(position);
            listSelection = position;
        });
        list.setOnItemClickListener((parent, view, position, id) -> toggle(position));
        // Sonim firmware variants report the physical OK key as either
        // DPAD_CENTER or ENTER. Handle activation only; arrows stay with
        // ListView's native selection and scrolling implementation.
        list.setOnKeyListener((view, keyCode, event) -> {
            if (!Ui.isOk(keyCode)) return false;
            if (heldForMenu(event)) return true;
            if (event.getAction() != KeyEvent.ACTION_UP) return true;
            if (event.getDownTime() == consumedOkDownTime) return true;
            int position = adapter.getSelectedPosition();
            if (position < 0 || position >= adapter.getCount()) return false;
            toggle(position);
            return true;
        });
    }

    /**
     * Holding OK opens the menu, as a long press does in the phone's own
     * lists. Every OK press is claimed here so ListView's own long press,
     * which would open the same menu as a centred dialog, never runs; and the
     * release that ends a hold is swallowed so it does not also tick the item.
     */
    private boolean heldForMenu(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) okHeld = false;
            else if (!okHeld) {
                okHeld = true;
                showMenu();
            }
            return true;
        }
        if (okHeld) {
            okHeld = false;
            return true;
        }
        return false;
    }

    private boolean okHeld;

    private void toggle(int position) {
        Checklist list = backend.checklist();
        if (position < 0 || position >= list.size()) return;
        list.toggle(position);
        saveList();
        redrawList(position);
    }

    private void itemAction(int action, final int position, final Item chosen) {
        if (action == MENU_ADD) {
            promptText("Add item", "", text -> insertNear(-1, null, 0, text));
            return;
        }
        if (locate(position, chosen) < 0) {
            toast("That item has changed");
            return;
        }
        if (action == MENU_INSERT_ABOVE || action == MENU_INSERT_BELOW) {
            final int offset = action == MENU_INSERT_ABOVE ? 0 : 1;
            promptText(action == MENU_INSERT_ABOVE ? "Insert above" : "Insert below", "", text -> {
                insertNear(position, chosen, offset, text);
            });
        } else if (action == MENU_EDIT) {
            promptText("Edit item", chosen.text, text -> {
                int at = locate(position, chosen);
                if (at < 0) {
                    toast("That item has changed");
                    return;
                }
                backend.checklist().edit(at, text);
                saveList();
                redrawList(at);
            });
        } else if (action == MENU_MOVE_UP || action == MENU_MOVE_DOWN) {
            int at = locate(position, chosen);
            int to = action == MENU_MOVE_UP ? at - 1 : at + 1;
            if (to < 0 || to >= backend.checklist().size()) return;
            backend.checklist().move(at, to);
            saveList();
            redrawList(to);
        } else if (action == MENU_DELETE) {
            int at = locate(position, chosen);
            backend.checklist().remove(at);
            saveList();
            redrawList(Math.min(at, backend.checklist().size() - 1));
        }
    }

    /**
     * Inserts beside the chosen item, wherever it now is. If it has gone in
     * the meantime, the new item still goes in at the position it was opened
     * on rather than being thrown away.
     */
    private void insertNear(int position, Item chosen, int offset, String text) {
        Checklist list = backend.checklist();
        int anchor = chosen == null ? -1 : locate(position, chosen);
        int at = anchor >= 0 ? anchor + offset : Math.max(0, Math.min(position + offset, list.size()));
        try {
            list.insert(at, text);
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
            return;
        }
        saveList();
        redrawList(at);
    }

    /** Where `expected` is now: at `position` if it is still there, else its first match. */
    private int locate(int position, Item expected) {
        if (expected == null) return -1;
        List<Item> items = backend.checklist().items();
        if (position >= 0 && position < items.size() && items.get(position).equals(expected)) {
            return position;
        }
        return items.indexOf(expected);
    }

    private interface TextResult {
        void accept(String text);
    }

    /**
     * Asks for one item's text.
     *
     * Prediction stays on here, unlike a password-style field: this is
     * ordinary writing and T9 is what makes it bearable. OK saves on its
     * press: T9 can swallow the first release while it commits a word.
     */
    private void promptText(String title, String initial, final TextResult result) {
        final EditText field = new EditText(this);
        field.setTextSize(15);
        field.setInputType(
            InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        );
        // Single-line input so ENTER is never a newline, but wrapped so a long
        // item can be read whole while it is being written.
        field.setHorizontallyScrolling(false);
        field.setMaxLines(4);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        field.setText(initial);
        field.setSelection(field.getText().length());

        LinearLayout holder = vertical();
        holder.setPadding(dp(12), dp(6), dp(12), dp(2));
        holder.addView(field, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(holder)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
        final Runnable commit = () -> {
            String text = field.getText().toString().trim();
            if (text.length() == 0) {
                toast("Type something first");
                return;
            }
            dialog.dismiss();
            try {
                result.accept(text);
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
            }
        };
        field.setOnKeyListener((view, keyCode, event) -> {
            if (!Ui.isOk(keyCode)) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                consumedOkDownTime = event.getDownTime();
                commit.run();
            }
            return true;
        });
        field.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            commit.run();
            return true;
        });
        dialog.show();
        // Wired after show() so an empty field keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> commit.run());
        field.requestFocus();
    }

    /** Writes the list down as it stands now, in edit order, off the main thread. */
    private void saveList() {
        final List<Item> snapshot = backend.checklist().items();
        saves.execute(() -> backend.saveList(snapshot));
    }

    // ------------------------------------------------------------------
    // Keys and the menus
    // ------------------------------------------------------------------

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Let a pending list save finish.
        saves.shutdown();
    }

    // ------------------------------------------------------------------
    // The Menu key
    // ------------------------------------------------------------------

    /*
     * Menu, or holding OK, works like a right-click: the platform's own
     * context menu opens at the thing it acts on (the highlighted item, or
     * the middle of the screen when the list is empty).
     *
     * This is how the phone's Music app does it: a standard ContextMenu, built
     * in onCreateContextMenu and answered in onContextItemSelected. Opening it
     * at a point inside a view (View.showContextMenu(x, y), Android 7 and up)
     * is what makes the platform draw it as its stock popup beside that point
     * rather than as a dialog in the middle of the screen; the platform also
     * keeps it on screen. Its rows are built fresh on every opening.
     */

    private final List<Integer> menuIds = new ArrayList<>();
    private final List<String> menuTitles = new ArrayList<>();

    /*
     * Menu opens on its release, as the platform's own menus do. Opened on
     * the press, the release would arrive at the popup once it had taken
     * focus, and the popup closes on a Menu release: it would flash up and
     * vanish whenever the key was let go a moment after it appeared.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // Only a release whose press was seen here: not the end of a
            // press that closed a dialog, nor one the system cancelled.
            if (event.isTracking() && !event.isCanceled()) showMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void showMenu() {
        menuPosition = -1;
        menuItem = null;
        menuIds.clear();
        menuTitles.clear();
        View anchor = listMenu(menuIds, menuTitles);
        if (menuIds.isEmpty() || anchor == null) return;

        // The point the menu opens at, in the anchor's own coordinates: the
        // bottom edge of an item, so it drops down just under it, a little in
        // from the left so the item's start stays readable. A row taller than
        // what is showing of it counts only for the visible part.
        float x = Math.min(dp(32), anchor.getWidth() / 2f);
        float y = anchor.getHeight();
        if (anchor == emptyView) {
            x = anchor.getWidth() / 4f;
            y = anchor.getHeight() / 2f;
        } else if (anchor.getParent() instanceof ListView) {
            // A row is opened from its list, at the row's place in it. When
            // the menu does not fit beside its anchor the platform scrolls
            // the anchor's parent to make room; for a row that is the list,
            // which would move and recycle rows under the open menu. The
            // list's own parent is a plain layout that cannot scroll.
            ListView list = (ListView) anchor.getParent();
            y = Math.max(0, Math.min(anchor.getBottom(), list.getHeight()));
            anchor = list;
        }
        anchor.showContextMenu(x, y);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo info) {
        for (int i = 0; i < menuIds.size(); i++) {
            menu.add(Menu.NONE, menuIds.get(i).intValue(), i, menuTitles.get(i));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        menuAction(item.getItemId());
        return true;
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // Anything that later asks for a context menu without going through
        // showMenu (a long press the platform handles itself) gets none.
        menuIds.clear();
        menuTitles.clear();
    }

    /** Fills the menu and returns the view it belongs beside. */
    private View listMenu(List<Integer> ids, List<String> titles) {
        Checklist list = backend.checklist();
        if (listView == null || listAdapter == null || list.size() == 0) {
            add(ids, titles, MENU_ADD, "Add item");
            return emptyView != null ? emptyView : content;
        }
        menuPosition = Math.max(0, Math.min(listAdapter.getSelectedPosition(), list.size() - 1));
        menuItem = list.get(menuPosition);
        add(ids, titles, MENU_INSERT_ABOVE, "Insert above");
        add(ids, titles, MENU_INSERT_BELOW, "Insert below");
        add(ids, titles, MENU_EDIT, "Edit");
        if (menuPosition > 0) add(ids, titles, MENU_MOVE_UP, "Move up");
        if (menuPosition < list.size() - 1) add(ids, titles, MENU_MOVE_DOWN, "Move down");
        add(ids, titles, MENU_DELETE, "Delete");
        View row = rowView(listView, menuPosition);
        return row != null ? row : listView;
    }

    private static void add(List<Integer> ids, List<String> titles, int id, String title) {
        ids.add(Integer.valueOf(id));
        titles.add(title);
    }

    private static View rowView(ListView list, int position) {
        return list.getChildAt(position - list.getFirstVisiblePosition());
    }

    private void menuAction(int id) {
        switch (id) {
            case MENU_ADD:
            case MENU_INSERT_ABOVE:
            case MENU_INSERT_BELOW:
            case MENU_EDIT:
            case MENU_MOVE_UP:
            case MENU_MOVE_DOWN:
            case MENU_DELETE:
                itemAction(id, menuPosition, menuItem);
                break;
        }
    }

    private ListView listView(ListAdapter adapter) {
        return configureListView(new ListView(this), adapter);
    }

    private <T extends ListView> T configureListView(T list, ListAdapter adapter) {
        list.setAdapter(adapter);
        // Rows paint their own focus card, so Family turns the platform
        // selector off and insets the hairlines to the text edge.
        Family.list(list);
        list.setItemsCanFocus(false);
        return list;
    }

    /** The header's right side: how many items, or how many of them are done. */
    private void showCount(List<Item> items) {
        int done = 0;
        for (Item item : items) if (item.done) done++;
        if (items.isEmpty()) countView.setText("");
        else if (done == 0) countView.setText(items.size() == 1 ? "1 item" : items.size() + " items");
        else countView.setText(done + " of " + items.size() + " done");
    }
}
