package com.list.xp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.list.xp3.data.ChatHistory;
import com.list.xp3.data.ChatTurn;
import com.list.xp3.data.Checklist;
import com.list.xp3.data.Item;
import com.list.xp3.net.Agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Two tabs, the checklist and the chat, and the keys that move between them.
 *
 * Views are built in code rather than inflated per screen because each screen
 * is a list plus at most a compose field. The things that repeat, an item row
 * and a chat bubble, are the things that do have layouts.
 *
 * Nothing on this side of the app touches a network. Everything that does is
 * behind Backend, runs on the chat executor, and comes back through the main
 * thread's handler. The list is saved on its own single thread so writes land
 * in the order the edits were made.
 */
public final class MainActivity extends Activity {

    private static final int LIST = 0;
    private static final int CHAT = 1;
    private static final int TABS = 2;

    /** Where a redraw of the Chat tab should leave the cursor. */
    private static final int CHAT_FOCUS_NONE = 0;
    private static final int CHAT_FOCUS_LIST = 1;
    private static final int CHAT_FOCUS_INPUT = 2;

    /** Menu item ids. The menu is rebuilt on every press of Menu. */
    private static final int MENU_INSERT_ABOVE = 1;
    private static final int MENU_INSERT_BELOW = 2;
    private static final int MENU_EDIT = 3;
    private static final int MENU_DELETE = 4;
    private static final int MENU_ADD = 5;
    private static final int MENU_MOVE_UP = 6;
    private static final int MENU_MOVE_DOWN = 7;
    private static final int MENU_COPY = 10;
    private static final int MENU_REWIND = 11;
    private static final int MENU_STOP = 20;
    private static final int MENU_PASTE = 21;
    private static final int MENU_COMPACT = 22;
    private static final int MENU_CLEAR = 23;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Backend backend;
    private FrameLayout content;
    private Button[] tabs;
    private int currentTab = LIST;

    private final ExecutorService saves = pool("list-save");
    private final ExecutorService chatWork = pool("list-chat");

    private static ExecutorService pool(final String name) {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    // ------------------------------------------------------------------
    // List state
    // ------------------------------------------------------------------

    private ListView listView;
    private ItemAdapter listAdapter;
    /** The empty-list message, which holds focus when there are no rows. */
    private View emptyView;
    /** The highlighted row, kept across redraws and tab switches. */
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
     * The item is kept as well as its position, because the assistant can
     * rewrite the list while the menu is open and an edit must land on the
     * item that was chosen, not on whatever has since moved into its row.
     */
    private int menuPosition = -1;
    private Item menuItem;
    private ChatTurn menuTurn;

    // ------------------------------------------------------------------
    // Chat state
    // ------------------------------------------------------------------

    private final List<ChatTurn> transcript = new ArrayList<>();
    private String chatDraft = "";
    private boolean agentThinking;
    /** True when the in-flight model turn is producing a hidden context summary. */
    private boolean compacting;
    /** The assistant turn currently arriving; it becomes a saved turn on completion. */
    private final StringBuilder streamingAnswer = new StringBuilder();
    private long streamingAnswerWhen;

    /**
     * Which conversation a turn belongs to.
     *
     * Clearing the chat while an answer is on its way has to stop that answer
     * landing in the transcript that replaced it. The network read cannot be
     * cancelled mid-flight, so it is allowed to finish and then discarded on
     * arrival by comparing this. (Its list writes are stopped earlier, in the
     * agent, by the history reset that goes with every such change.)
     */
    private int chatGeneration;

    private ChatListView chatList;
    private ChatAdapter chatAdapter;
    /** -1 means no restore; MAX_VALUE means the transcript was at its end. */
    private int savedChatScrollPosition = AdapterView.INVALID_POSITION;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        backend = new Backend(getFilesDir(), new Backend.ListListener() {
            @Override
            public void changedByAssistant() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        listChangedByAssistant();
                    }
                });
            }
        });
        transcript.addAll(backend.transcript());
        backend.restore(transcript);

        content = findViewById(R.id.content);
        // Every screen's context menu is answered here; see showMenu.
        registerForContextMenu(content);
        tabs = new Button[] {
            findViewById(R.id.tab_list),
            findViewById(R.id.tab_chat),
        };
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showTab(tab, true);
                }
            });
        }
        showTab(LIST, true);
    }

    @Override
    protected void onPause() {
        if (currentTab == CHAT) saveChatScrollPosition();
        super.onPause();
    }

    private void showTab(int tab, boolean focusContent) {
        if (currentTab == CHAT && tab != CHAT) saveChatScrollPosition();
        currentTab = tab;
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(i == tab);
        if (tab == CHAT) {
            // The first time Chat is drawn, put the cursor in the box: the
            // usual reason to open it is to say something.
            boolean firstChat = chatList == null;
            renderChat(firstChat ? CHAT_FOCUS_INPUT : focusContent ? CHAT_FOCUS_LIST : CHAT_FOCUS_NONE);
        } else {
            renderList(focusContent);
        }
    }

    // ------------------------------------------------------------------
    // The list
    // ------------------------------------------------------------------

    private void renderList(boolean focus) {
        List<Item> items = backend.checklist().items();
        LinearLayout root = vertical();

        if (items.isEmpty()) {
            listAdapter = null;
            listView = null;
            TextView empty = emptyMessage("No list items yet");
            // Focusable so the screen still has somewhere for keys to land,
            // and so Menu's popup has something to sit beside.
            empty.setFocusable(true);
            empty.setFocusableInTouchMode(true);
            root.addView(empty, weighted());
            replaceContent(root);
            emptyView = empty;
            listSelection = 0;
            if (focus) empty.requestFocus();
            return;
        }

        emptyView = null;
        final ItemAdapter adapter = new ItemAdapter(this);
        adapter.replace(items);
        ListView list = listView(adapter);
        root.addView(list, weighted());
        wireList(list, adapter);
        replaceContent(root);
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
        boolean focus = currentTab == LIST && (
            (listView != null && listView.hasFocus()) ||
            (emptyView != null && emptyView.hasFocus()) ||
            getCurrentFocus() == null
        );
        if (currentTab != LIST) return;
        List<Item> items = backend.checklist().items();
        if (listAdapter == null || listView == null || items.isEmpty()) {
            renderList(true);
            return;
        }
        int at = Math.max(0, Math.min(position, items.size() - 1));
        listSelection = at;
        listAdapter.replace(items);
        listAdapter.setSelectedPosition(at);
        listView.setSelection(at);
        if (focus) listView.requestFocus();
    }

    private void wireList(final ListView list, final ItemAdapter adapter) {
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                adapter.trackSelectedPosition(position);
                listSelection = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                toggle(position);
            }
        });
        // Sonim firmware variants report the physical OK key as either
        // DPAD_CENTER or ENTER. Handle activation only; arrows stay with
        // ListView's native selection and scrolling implementation.
        list.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (!isOk(keyCode)) return false;
                if (heldForMenu(event)) return true;
                if (event.getAction() != KeyEvent.ACTION_UP) return true;
                if (event.getDownTime() == consumedOkDownTime) return true;
                int position = adapter.getSelectedPosition();
                if (position < 0 || position >= adapter.getCount()) return false;
                toggle(position);
                return true;
            }
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

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void toggle(int position) {
        Checklist list = backend.checklist();
        if (position < 0 || position >= list.size()) return;
        list.toggle(position);
        saveList();
        redrawList(position);
    }

    private void itemAction(int action, final int position, final Item chosen) {
        if (action == MENU_ADD) {
            promptText("Add item", "", new TextResult() {
                @Override
                public void accept(String text) {
                    insertNear(-1, null, 0, text);
                }
            });
            return;
        }
        if (locate(position, chosen) < 0) {
            toast("That item has changed");
            return;
        }
        if (action == MENU_INSERT_ABOVE || action == MENU_INSERT_BELOW) {
            final int offset = action == MENU_INSERT_ABOVE ? 0 : 1;
            promptText(action == MENU_INSERT_ABOVE ? "Insert above" : "Insert below", "", new TextResult() {
                @Override
                public void accept(String text) {
                    insertNear(position, chosen, offset, text);
                }
            });
        } else if (action == MENU_EDIT) {
            promptText("Edit item", chosen.text, new TextResult() {
                @Override
                public void accept(String text) {
                    int at = locate(position, chosen);
                    if (at < 0) {
                        toast("That item has changed");
                        return;
                    }
                    backend.checklist().edit(at, text);
                    saveList();
                    redrawList(at);
                }
            });
        } else if (action == MENU_MOVE_UP || action == MENU_MOVE_DOWN) {
            int at = locate(position, chosen);
            int to = action == MENU_MOVE_UP ? at - 1 : at + 1;
            // The assistant may have moved it to an end since the menu opened.
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
     * Inserts beside the chosen item, wherever it now is. If the assistant has
     * removed it in the meantime, the new item still goes in at the position
     * it was opened on rather than being thrown away.
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
     * press, for the same reason the chat box sends on it.
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
        final Runnable commit = new Runnable() {
            @Override
            public void run() {
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
            }
        };
        field.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (!isOk(keyCode)) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    consumedOkDownTime = event.getDownTime();
                    commit.run();
                }
                return true;
            }
        });
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                commit.run();
                return true;
            }
        });
        dialog.show();
        // Wired after show() so an empty field keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                commit.run();
            }
        });
        field.requestFocus();
    }

    /** Writes the list down as it stands now, in edit order, off the main thread. */
    private void saveList() {
        final List<Item> snapshot = backend.checklist().items();
        saves.execute(new Runnable() {
            @Override
            public void run() {
                backend.saveList(snapshot);
            }
        });
    }

    /**
     * The assistant rewrote the list. Save it, and repaint it if it is on
     * screen, keeping the highlight on the same row number. A full rewrite
     * has no better notion of "the same item" than that.
     */
    private void listChangedByAssistant() {
        if (isFinishing()) return;
        saveList();
        if (currentTab == LIST) redrawList(listSelection);
        else toast("List updated");
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    /**
     * The transcript, plus the answer being composed.
     *
     * It is built rather than stored: the live bubble is not a turn yet. It
     * must not be saved, and is thrown away whole if the turn fails.
     */
    private List<ChatTurn> chatTurns() {
        List<ChatTurn> turns = new ArrayList<>(transcript);
        if (!agentThinking) return turns;
        if (compacting) {
            turns.add(ChatTurn.compacting(streamingAnswerWhen));
            return turns;
        }
        turns.add(new ChatTurn(
            false,
            streamingAnswer.length() == 0 ? "…" : streamingAnswer.toString(),
            streamingAnswerWhen
        ));
        return turns;
    }

    /**
     * Repaints the rows without rebuilding the screen, so the cursor, the
     * draft and the T9 keyboard's half-finished word survive a streamed word.
     */
    private void updateChat() {
        if (chatAdapter == null || chatList == null || currentTab != CHAT) return;
        boolean follow = chatList.isPinnedToBottom() || chatIsAtBottom(chatList, chatAdapter);
        chatAdapter.replace(chatTurns());
        if (follow) chatList.pinToBottomOnce();
    }

    private void renderChat(int focus) {
        LinearLayout root = vertical();

        final ChatAdapter adapter = new ChatAdapter(this);
        adapter.replace(chatTurns());
        final ChatListView list = chatListView(adapter);
        chatAdapter = adapter;
        chatList = list;
        list.setBackgroundColor(color(R.color.chat_page));
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setStackFromBottom(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
        list.setSmoothScrollbarEnabled(false);
        list.setVerticalFadingEdgeEnabled(false);
        list.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        list.setPadding(0, 0, 0, dp(8));
        list.setClipToPadding(false);
        root.addView(list, weighted());

        /* A compact three-line compose field leaves the transcript most of the
         * screen. OK sends, so the 240px-wide layout needs no separate button. */
        final EditText input = new EditText(this);
        input.setTextSize(16);
        input.setTextColor(color(R.color.input_text));
        input.setHintTextColor(color(R.color.input_hint));
        input.setBackgroundResource(R.drawable.edittext_border);
        input.setPadding(dp(8), dp(8), dp(8), dp(8));
        input.setHint(getString(R.string.chat_hint));
        input.setText(chatDraft);
        input.setSelection(chatDraft.length());
        input.setMaxLines(3);
        // Without AUTO_CORRECT the T9 keyboard drops back to multitap with no
        // suggestions; MULTI_LINE lets a long message wrap instead of
        // scrolling sideways through a 240px slot.
        input.setInputType(
            InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE |
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT |
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        // The draft is kept here rather than read off the view, because a
        // redraw rebuilds this screen and the view holding it goes with it.
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                chatDraft = text.toString();
                if (input.hasFocus()) list.pinToBottomOnce();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (
                    actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null &&
                        event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                        event.getAction() == KeyEvent.ACTION_DOWN)
                ) {
                    send(view.getText().toString());
                    return true;
                }
                return false;
            }
        });
        /*
         * OK sends, on the press rather than the release: with the T9 keyboard
         * up, the first ENTER release never reaches the app because the
         * keyboard takes it to finish the word it is composing. Both halves
         * are swallowed so ENTER cannot also drop a newline into the field.
         */
        final ListView transcriptList = list;
        int transcriptId = View.generateViewId();
        int inputId = View.generateViewId();
        list.setId(transcriptId);
        input.setId(inputId);
        list.setNextFocusDownId(inputId);
        input.setNextFocusUpId(transcriptId);
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                // Reaching the composer proves the transcript was read to its
                // end. Keep the last row's bottom fixed while typing.
                list.setPinnedToBottom(hasFocus);
            }
        });
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                // Up out of the box once there is no line of your own above
                // the cursor, without the platform's extra dead press.
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (!onFirstLine((EditText) view)) return false;
                    if (transcriptList.getCount() == 0) return false;
                    transcriptList.requestFocus();
                    return true;
                }
                if (!isOk(keyCode)) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    send(((EditText) view).getText().toString());
                }
                return true;
            }
        });

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputParams.setMargins(dp(4), dp(2), dp(4), dp(4));
        root.addView(input, inputParams);

        wireChatList(list, adapter);
        replaceContent(root);
        restoreChatScrollPosition(list, adapter);
        if (focus == CHAT_FOCUS_INPUT) input.requestFocus();
        else if (focus == CHAT_FOCUS_LIST && adapter.getCount() > 0) list.requestFocus();
        else if (focus == CHAT_FOCUS_LIST) input.requestFocus();
    }

    /**
     * Records intent rather than a pixel offset. MAX_VALUE means "the end", so
     * returning after the transcript grows resolves against its new count.
     */
    private void saveChatScrollPosition() {
        if (chatList == null || chatAdapter == null || chatAdapter.getCount() == 0) return;
        savedChatScrollPosition =
            chatList.getLastVisiblePosition() >= chatAdapter.getCount() - 1
                ? Integer.MAX_VALUE
                : chatList.getFirstVisiblePosition();
    }

    /** Applies a saved position immediately after the adapter has its data. */
    private void restoreChatScrollPosition(ChatListView list, ChatAdapter adapter) {
        int count = adapter.getCount();
        if (count == 0) {
            savedChatScrollPosition = AdapterView.INVALID_POSITION;
            return;
        }
        int saved = savedChatScrollPosition;
        savedChatScrollPosition = AdapterView.INVALID_POSITION;
        int position = saved == Integer.MAX_VALUE
            ? count - 1
            : saved >= 0 ? Math.min(saved, count - 1) : count - 1;
        list.setSelection(position);
        if (saved == Integer.MAX_VALUE || saved == AdapterView.INVALID_POSITION) {
            list.pinToBottomOnce();
        }
    }

    /** Whether the final row is fully visible inside the padded viewport. */
    private boolean chatIsAtBottom(ListView list, ChatAdapter adapter) {
        int target = adapter.getCount() - 1;
        int last = list.getLastVisiblePosition();
        if (target < 0 || last != target) return false;
        View row = list.getChildAt(last - list.getFirstVisiblePosition());
        return row != null && row.getBottom() <= list.getHeight() - list.getPaddingBottom();
    }

    /**
     * Stepping through the transcript. Up out of the box puts the highlight
     * on the newest message; down off the newest one puts it back in the box.
     * The highlight is dropped whenever the list loses focus.
     */
    private void wireChatList(final ChatListView list, final ChatAdapter adapter) {
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (adapter.getCount() == 0) return;
                if (hasFocus) {
                    list.setPinnedToBottom(false);
                    if (adapter.getSelectedPosition() == AdapterView.INVALID_POSITION) {
                        int selected = ((ListView) view).getSelectedItemPosition();
                        if (selected == AdapterView.INVALID_POSITION) {
                            selected = ((ListView) view).getLastVisiblePosition();
                        }
                        adapter.trackSelectedPosition(selected);
                        View selectedView = ((ListView) view).getSelectedView();
                        if (selectedView != null) selectedView.setSelected(true);
                    }
                } else {
                    View selectedView = ((ListView) view).getSelectedView();
                    if (selectedView != null) selectedView.setSelected(false);
                    adapter.trackSelectedPosition(AdapterView.INVALID_POSITION);
                }
            }
        });
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (parent.hasFocus()) adapter.trackSelectedPosition(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        list.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                // OK on a message does nothing; holding it opens the menu.
                if (!isOk(keyCode)) return false;
                heldForMenu(event);
                return true;
            }
        });
    }

    /**
     * Makes the selected saved message the end of the conversation. A user
     * message is removed too and moved back into the composer. The list
     * itself is not rewound: it is the person's, and what the assistant did to
     * it stays done unless they ask for it back.
     */
    private void rewindChat(int position) {
        if (position < 0 || position >= transcript.size() || !transcript.get(position).isMessage()) {
            return;
        }
        chatGeneration++;
        agentThinking = false;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = 0L;
        String draft = ChatHistory.rewind(transcript, position);
        if (draft != null) chatDraft = draft;
        backend.restore(transcript);
        backend.saveTranscript(transcript);
        renderChat(draft == null ? CHAT_FOCUS_LIST : CHAT_FOCUS_INPUT);
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Message", text));
        toast("Copied");
    }

    /** What is on the clipboard as text, or null when there is nothing usable. */
    private CharSequence clipboardText() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()
            || clipboard.getPrimaryClip().getItemCount() == 0) return null;
        CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        return pasted == null || pasted.length() == 0 ? null : pasted;
    }

    private void pasteIntoInput() {
        CharSequence pasted = clipboardText();
        if (pasted == null) return;
        chatDraft = chatDraft + pasted.toString();
        renderChat(CHAT_FOCUS_INPUT);
    }

    private void send(String text) {
        final String message = text.trim();
        if (message.length() == 0) return;
        if (agentThinking) {
            // One turn at a time. A second message sent into a turn already
            // running would interleave two answers into one transcript.
            toast("Still answering");
            return;
        }
        transcript.add(new ChatTurn(true, message, System.currentTimeMillis()));
        chatDraft = "";

        if (!backend.chatReady()) {
            transcript.add(new ChatTurn(false, Backend.NO_LOGIN, System.currentTimeMillis()));
            backend.saveTranscript(transcript);
            renderChat(CHAT_FOCUS_INPUT);
            return;
        }

        agentThinking = true;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = System.currentTimeMillis();
        backend.saveTranscript(transcript);
        renderChat(CHAT_FOCUS_INPUT);
        ask(message, chatGeneration);
    }

    /**
     * Runs one turn. Everything the agent reports comes back on a background
     * thread, so each callback hops to the main thread first.
     */
    private void ask(final String message, final int generation) {
        final Agent.Activity activity = new Agent.Activity() {
            @Override
            public void text(final String delta) {
                if (delta == null || delta.length() == 0) return;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!agentThinking || generation != chatGeneration) return;
                        streamingAnswer.append(delta);
                        updateChat();
                    }
                });
            }
        };

        chatWork.execute(new Runnable() {
            @Override
            public void run() {
                String reply = null;
                String failure = null;
                try {
                    reply = backend.ask(message, activity);
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.io.IOException e) {
                    failure = reason(e);
                } catch (RuntimeException e) {
                    failure = "That answer came back in a form this app could not read.";
                }
                final String answer = reply;
                final String problem = failure;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        answered(answer, problem, generation);
                    }
                });
            }
        });
    }

    private void answered(String answer, String problem, int generation) {
        if (isFinishing() || generation != chatGeneration) return;
        agentThinking = false;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = 0L;
        if (problem != null) {
            transcript.add(new ChatTurn(false, problem, System.currentTimeMillis()));
        } else if (answer != null && answer.length() > 0) {
            transcript.add(new ChatTurn(false, answer, System.currentTimeMillis()));
        }
        backend.saveTranscript(transcript);
        updateChat();
    }

    /**
     * Gives up on an answer that is taking too long. The network read cannot
     * be taken back, so this stops waiting for it rather than stopping it.
     * Restoring the agent's history is what makes sure the abandoned turn can
     * no longer change the list when it does come back.
     */
    private void stopWaiting() {
        if (!agentThinking) return;
        chatGeneration++;
        agentThinking = false;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = 0L;
        transcript.add(new ChatTurn(false, "Stopped waiting for that one.", System.currentTimeMillis()));
        backend.restore(transcript);
        backend.saveTranscript(transcript);
        renderChat(CHAT_FOCUS_INPUT);
    }

    /**
     * Replaces the model's working history with a summary while leaving the
     * visible transcript intact.
     */
    private void compactChat() {
        if (agentThinking) {
            toast("Still answering");
            return;
        }
        if (transcript.isEmpty()) {
            toast("Nothing to compact");
            return;
        }
        if (!backend.chatReady()) {
            toast("This build has no Claude login");
            return;
        }

        agentThinking = true;
        compacting = true;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = System.currentTimeMillis();
        renderChat(CHAT_FOCUS_INPUT);
        final int generation = chatGeneration;

        chatWork.execute(new Runnable() {
            @Override
            public void run() {
                String reply = null;
                String failure = null;
                try {
                    reply = backend.summarize();
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (java.io.IOException e) {
                    failure = reason(e);
                } catch (RuntimeException e) {
                    failure = "That summary came back in a form this app could not read.";
                }
                final String summary = reply;
                final String problem = failure;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        compacted(summary, problem, generation);
                    }
                });
            }
        });
    }

    private void compacted(String summary, String problem, int generation) {
        if (isFinishing() || generation != chatGeneration) return;
        agentThinking = false;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = 0L;
        if (problem != null) {
            updateChat();
            alert("Could not compact chat", problem);
            return;
        }
        transcript.add(ChatTurn.compaction(summary, System.currentTimeMillis()));
        backend.restore(transcript);
        backend.saveTranscript(transcript);
        updateChat();
    }

    private void clearChat() {
        chatGeneration++;
        agentThinking = false;
        compacting = false;
        streamingAnswer.setLength(0);
        streamingAnswerWhen = 0L;
        transcript.clear();
        backend.restore(transcript);
        backend.saveTranscript(transcript);
        renderChat(CHAT_FOCUS_INPUT);
    }

    // ------------------------------------------------------------------
    // Keys and the menus
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // First press only: auto-repeat from a held d-pad would flip the tabs
        // as fast as the key fires. Not inside typed text either, where the
        // same keys move the cursor.
        if (
            event.getAction() == KeyEvent.ACTION_DOWN &&
            event.getRepeatCount() == 0 &&
            !cursorOwns(event.getKeyCode())
        ) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                showTab((currentTab + TABS - 1) % TABS, true);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                showTab((currentTab + 1) % TABS, true);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Whether the cursor sits on the first line of what has been typed, which
     * is the point at which up stops being a text key and becomes a way out of
     * the field.
     */
    private boolean onFirstLine(EditText field) {
        Layout laid = field.getLayout();
        if (laid == null) return true;
        return laid.getLineForOffset(field.getSelectionStart()) == 0;
    }

    /**
     * Whether left or right belongs to a text cursor rather than to the tabs.
     * Inside typed text it does; at either end of it, it goes back to being a
     * tab key, so a box with something in it is never a room with no door.
     */
    private boolean cursorOwns(int keyCode) {
        View focus = getCurrentFocus();
        if (!(focus instanceof EditText)) return false;
        EditText field = (EditText) focus;
        int length = field.getText().length();
        if (length == 0) return false;
        int start = field.getSelectionStart();
        int end = field.getSelectionEnd();
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return !(start == 0 && end == 0);
        return !(start == length && end == length);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Let a pending list save finish; the chat turn has nothing worth
        // keeping a socket open for once the screen has gone.
        saves.shutdown();
        chatWork.shutdownNow();
    }

    // ------------------------------------------------------------------
    // The Menu key
    // ------------------------------------------------------------------

    /*
     * Menu, or holding OK, works like a right-click: the platform's own
     * context menu opens at the thing it acts on (the highlighted item or
     * message, or above the compose box when no message is highlighted).
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
        menuTurn = null;
        menuIds.clear();
        menuTitles.clear();
        View anchor = currentTab == LIST ? listMenu(menuIds, menuTitles) : chatMenu(menuIds, menuTitles);
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

    /** Fills the List tab's menu and returns the view it belongs beside. */
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

    /** Fills the Chat tab's menu: the highlighted message's actions first. */
    private View chatMenu(List<Integer> ids, List<String> titles) {
        // With no message highlighted the menu opens at the foot of the
        // transcript, just above the compose box.
        View anchor = chatList != null ? chatList : content;
        if (chatList != null && chatAdapter != null && chatList.hasFocus()) {
            int position = chatAdapter.getSelectedPosition();
            if (position >= 0 && position < chatAdapter.getCount()) {
                ChatTurn turn = chatAdapter.getItem(position);
                if (turn.isMessage()) {
                    menuPosition = position;
                    menuTurn = turn;
                    add(ids, titles, MENU_COPY, "Copy text");
                    // The live streaming row can be copied but is not yet a
                    // point the saved conversation can rewind to.
                    if (position < transcript.size() && transcript.get(position) == turn) {
                        add(ids, titles, MENU_REWIND, "Rewind to here");
                    }
                    View row = rowView(chatList, position);
                    if (row != null) anchor = row;
                }
            }
        }
        if (agentThinking && !compacting) add(ids, titles, MENU_STOP, "Stop waiting");
        if (clipboardText() != null) add(ids, titles, MENU_PASTE, "Paste");
        add(ids, titles, MENU_COMPACT, "Compact chat");
        add(ids, titles, MENU_CLEAR, "Clear chat");
        return anchor;
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
            case MENU_COPY:
                if (menuTurn != null) copyToClipboard(menuTurn.text);
                break;
            case MENU_REWIND:
                if (menuTurn != null && menuPosition < transcript.size()
                    && transcript.get(menuPosition) == menuTurn) {
                    rewindChat(menuPosition);
                }
                break;
            case MENU_STOP:
                stopWaiting();
                break;
            case MENU_PASTE:
                pasteIntoInput();
                break;
            case MENU_COMPACT:
                compactChat();
                break;
            default:
                clearChat();
                break;
        }
    }

    // ------------------------------------------------------------------
    // View helpers
    // ------------------------------------------------------------------

    /** An exception as a sentence, never empty. */
    private static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.length() == 0 ? "The network could not be reached." : message;
    }

    private void alert(String title, String message) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message == null ? "Something went wrong." : message)
            .setPositiveButton("OK", null)
            .show();
    }

    private void replaceContent(View view) {
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    }

    private ListView listView(ListAdapter adapter) {
        return configureListView(new ListView(this), adapter);
    }

    private ChatListView chatListView(ListAdapter adapter) {
        return configureListView(new ChatListView(this), adapter);
    }

    private <T extends ListView> T configureListView(T list, ListAdapter adapter) {
        list.setAdapter(adapter);
        // Rows paint their own focus state edge to edge. A transparent
        // selector stops the platform highlight from drawing a second,
        // differently shaped indicator underneath them.
        list.setSelector(new ColorDrawable(0x00000000));
        list.setDivider(new ColorDrawable(color(R.color.divider)));
        list.setDividerHeight(dp(1));
        list.setBackgroundColor(color(R.color.page));
        list.setCacheColorHint(0x00000000);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        list.setItemsCanFocus(false);
        return list;
    }

    /** Centred guidance for a screen that has nothing in it. */
    private TextView emptyMessage(String message) {
        TextView view = new TextView(this);
        view.setText(message);
        view.setTextSize(13);
        view.setTextColor(color(R.color.text_muted));
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        return view;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int color(int resId) {
        return getResources().getColor(resId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
