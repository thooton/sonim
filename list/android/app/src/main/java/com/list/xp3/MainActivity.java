package com.list.xp3;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.list.xp3.data.Checklist;
import com.list.xp3.data.Item;
import com.xp3.common.ui.BaseActivity;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * The checklist and the keys that edit it, dressed as Google Tasks.
 *
 * The screen is the list alone: a single ListView whose rows draw Google
 * Tasks' two cards, To-do and Completed (ItemAdapter); its positions are
 * rows, and each row knows the index of the item it shows.
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
    private static final int MENU_DELETE_COMPLETED = 8;

    private Backend backend;

    private final ExecutorService saves = Ui.worker("list-save");

    // ------------------------------------------------------------------
    // List state
    // ------------------------------------------------------------------

    private ListView listView;
    private ItemAdapter listAdapter;
    /** Whether the Completed card is open. Folded, as Google Tasks starts. */
    private boolean showCompleted;

    /**
     * The press of OK that confirmed the task sheet.
     *
     * The sheet keeps T9 prediction on, so it acts on the press of OK rather
     * than its release (the keyboard can swallow the first release while it
     * commits a word). When the release does arrive, it arrives at the list
     * underneath, which would read it as OK and tick the item. Its down time
     * is the press's, so it can be recognised and ignored exactly.
     */
    private long consumedOkDownTime = -1;

    /**
     * What the open menu is about, captured when it was opened: the row, the
     * item's index and the item itself, so an edit lands on the item that was
     * chosen even if the list has changed under the open menu.
     */
    private int menuRow = -1;
    private int menuIndex = -1;
    private Item menuItem;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        backend = new Backend(getFilesDir());

        listView = findViewById(R.id.list);
        listAdapter = new ItemAdapter(this);
        listView.setAdapter(listAdapter);
        listView.setItemsCanFocus(false);
        wireList(listView, listAdapter);
        // Every context menu is answered here; see showMenu. The list's
        // parent is registered, not the list, so ListView's own long press
        // never opens one.
        registerForContextMenu((View) listView.getParent());

        showRows(0);
        listView.requestFocus();
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    /**
     * Lays the rows out again and puts the highlight on the selectable row
     * at `position`, or the nearest one to it.
     */
    private void showRows(int position) {
        listAdapter.replace(backend.checklist().items(), showCompleted);
        select(listAdapter.nearestSelectable(position));
    }

    /** As showRows, with the highlight on the row showing item `index`. */
    private void showItem(int index) {
        listAdapter.replace(backend.checklist().items(), showCompleted);
        int at = listAdapter.positionOf(index);
        select(at >= 0 ? at : listAdapter.nearestSelectable(0));
    }

    /**
     * Highlights `position` without the list jumping: a row already on screen
     * keeps its place, and the first task keeps the card's heading above it.
     * (A plain setSelection would put the row at the very top, scrolling the
     * heading away.)
     */
    private void select(int position) {
        listAdapter.setSelectedPosition(position);
        if (position < 0) return;
        View row = rowView(position);
        int top = listView.getPaddingTop();
        if (row != null && row.getTop() >= top && row.getBottom() <= listView.getHeight()) {
            listView.setSelectionFromTop(position, row.getTop());
        } else if (position == 1) {
            listView.setSelectionFromTop(position, top + getResources().getDimensionPixelSize(R.dimen.card_header_height));
        } else {
            listView.setSelection(position);
        }
    }

    private void wireList(final ListView list, final ItemAdapter adapter) {
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        Ui.onSelect(list, adapter::trackSelectedPosition);
        list.setOnItemClickListener((parent, view, position, id) -> activate(position));
        // Sonim firmware variants report the physical OK key as either
        // DPAD_CENTER or ENTER. Handle activation only; arrows stay with
        // ListView's native selection and scrolling implementation.
        list.setOnKeyListener((view, keyCode, event) -> {
            if (!Ui.isOk(keyCode)) return false;
            if (heldForMenu(event)) return true;
            if (event.getAction() != KeyEvent.ACTION_UP) return true;
            if (event.getDownTime() == consumedOkDownTime) return true;
            int position = adapter.getSelectedPosition();
            if (position < 0 || position >= adapter.getCount()) return true;
            activate(position);
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

    /**
     * OK on a task ticks or unticks it, and it moves to the other card, as in
     * Google Tasks; the highlight stays where it was, on the task that slid up
     * into its place. OK on "Completed" folds or opens that card.
     */
    private void activate(int position) {
        if (!listAdapter.isEnabled(position)) return;
        ItemAdapter.Row row = listAdapter.getItem(position);
        if (row.type == ItemAdapter.COMPLETED) {
            showCompleted = !showCompleted;
            showRows(position);
            return;
        }
        Checklist list = backend.checklist();
        if (row.index < 0 || row.index >= list.size()) return;
        list.toggle(row.index);
        saveList();
        showRows(position);
    }

    private void itemAction(int action, final int row, final int index, final Item chosen) {
        if (action == MENU_ADD) {
            // New tasks go to the top, as Google Tasks puts them.
            promptText("", text -> insertNear(-1, null, 0, text));
            return;
        }
        if (action == MENU_DELETE_COMPLETED) {
            Checklist list = backend.checklist();
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).done) list.remove(i);
            }
            saveList();
            showRows(row);
            return;
        }
        if (locate(index, chosen) < 0) {
            toast("That task has changed");
            return;
        }
        if (action == MENU_INSERT_ABOVE || action == MENU_INSERT_BELOW) {
            final int offset = action == MENU_INSERT_ABOVE ? 0 : 1;
            promptText("", text -> insertNear(index, chosen, offset, text));
        } else if (action == MENU_EDIT) {
            promptText(chosen.text, text -> {
                int at = locate(index, chosen);
                if (at < 0) {
                    toast("That task has changed");
                    return;
                }
                backend.checklist().edit(at, text);
                saveList();
                showItem(at);
            });
        } else if (action == MENU_MOVE_UP || action == MENU_MOVE_DOWN) {
            // Past the next open task, not the next item: finished ones in
            // between are in the other card and do not count.
            int at = locate(index, chosen);
            int to = neighbour(at, action == MENU_MOVE_UP ? -1 : 1);
            if (to < 0) return;
            backend.checklist().move(at, to);
            saveList();
            showItem(to);
        } else if (action == MENU_DELETE) {
            backend.checklist().remove(locate(index, chosen));
            saveList();
            showRows(row);
        }
    }

    /** The index of the nearest item before (-1) or after (1) `at` that is as done as it is, or -1. */
    private int neighbour(int at, int step) {
        List<Item> items = backend.checklist().items();
        boolean done = items.get(at).done;
        for (int i = at + step; i >= 0 && i < items.size(); i += step) {
            if (items.get(i).done == done) return i;
        }
        return -1;
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
        showItem(at);
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
     * Asks for one task's text, in Google Tasks' sheet: it rises from the
     * bottom with "New task" as the hint and Save on the right.
     *
     * Prediction stays on here, unlike a password-style field: this is
     * ordinary writing and T9 is what makes it bearable. OK saves on its
     * press: T9 can swallow the first release while it commits a word. Back
     * cancels.
     */
    private void promptText(String initial, final TextResult result) {
        final Dialog sheet = new Dialog(this, R.style.TaskSheet);
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheet.setContentView(R.layout.sheet_task);
        Window window = sheet.getWindow();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.BOTTOM);

        final EditText field = sheet.findViewById(R.id.sheet_field);
        final TextView save = sheet.findViewById(R.id.sheet_save);
        // Single-line input so ENTER is never a newline, but wrapped so a long
        // task can be read whole while it is being written.
        field.setHorizontallyScrolling(false);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        field.setText(initial);
        field.setSelection(field.getText().length());

        final Runnable commit = () -> {
            String text = field.getText().toString().trim();
            if (text.length() == 0) return;
            sheet.dismiss();
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
        save.setOnClickListener(view -> commit.run());
        // Save is greyed out while there is nothing to save.
        final Runnable paintSave = () -> {
            boolean ready = field.getText().toString().trim().length() > 0;
            save.setEnabled(ready);
            save.setTextColor(color(ready ? R.color.accent : R.color.save_off));
        };
        paintSave.run();
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                paintSave.run();
            }
        });
        sheet.show();
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
        menuRow = -1;
        menuIndex = -1;
        menuItem = null;
        menuIds.clear();
        menuTitles.clear();
        View row = listMenu(menuIds, menuTitles);
        if (menuIds.isEmpty()) return;

        // The menu is opened from the list, at the row's place in it: the
        // bottom edge of the row, a little in from the left so the task's
        // start stays readable. (When the menu does not fit beside its
        // anchor the platform scrolls the anchor's parent to make room; for
        // a row that is the list, which would move and recycle rows under
        // the open menu. The list's own parent cannot scroll.) With nothing
        // to choose, it opens over the middle of the list card.
        float x = Math.min(dp(32), listView.getWidth() / 2f);
        float y;
        if (row == null) {
            y = listView.getHeight() / 3f;
        } else if (menuRow < 0) {
            x = listView.getWidth() / 4f;
            y = (row.getTop() + row.getBottom()) / 2f;
        } else {
            y = Math.max(0, Math.min(row.getBottom(), listView.getHeight()));
        }
        listView.showContextMenu(x, y);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo info) {
        for (int i = 0; i < menuIds.size(); i++) {
            menu.add(Menu.NONE, menuIds.get(i).intValue(), i, menuTitles.get(i));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        itemAction(item.getItemId(), menuRow, menuIndex, menuItem);
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

    /**
     * Fills the menu for the highlighted row and returns that row's view (or,
     * with nothing selectable, the empty note's), if it is on screen.
     */
    private View listMenu(List<Integer> ids, List<String> titles) {
        add(ids, titles, MENU_ADD, "Add a task");
        int position = listAdapter.getSelectedPosition();
        if (position < 0 || position >= listAdapter.getCount() || !listAdapter.isEnabled(position)) {
            // Only the list's heading and its empty note: the note is row 1.
            return rowView(1);
        }
        menuRow = position;
        ItemAdapter.Row row = listAdapter.getItem(position);
        if (row.type == ItemAdapter.TASK) {
            menuIndex = row.index;
            menuItem = row.item;
            add(ids, titles, MENU_INSERT_ABOVE, "Insert above");
            add(ids, titles, MENU_INSERT_BELOW, "Insert below");
            add(ids, titles, MENU_EDIT, "Edit");
            if (listAdapter.neighbour(position, -1) >= 0) add(ids, titles, MENU_MOVE_UP, "Move up");
            if (listAdapter.neighbour(position, 1) >= 0) add(ids, titles, MENU_MOVE_DOWN, "Move down");
            add(ids, titles, MENU_DELETE, "Delete");
        } else if (row.type == ItemAdapter.DONE) {
            menuIndex = row.index;
            menuItem = row.item;
            add(ids, titles, MENU_EDIT, "Edit");
            add(ids, titles, MENU_DELETE, "Delete");
            add(ids, titles, MENU_DELETE_COMPLETED, "Delete all completed");
        } else {
            add(ids, titles, MENU_DELETE_COMPLETED, "Delete all completed");
        }
        return rowView(position);
    }

    private static void add(List<Integer> ids, List<String> titles, int id, String title) {
        ids.add(Integer.valueOf(id));
        titles.add(title);
    }

    private View rowView(int position) {
        if (position < 0) return null;
        return listView.getChildAt(position - listView.getFirstVisiblePosition());
    }
}
