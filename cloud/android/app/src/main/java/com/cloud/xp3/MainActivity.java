package com.cloud.xp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.cloud.xp3.data.Entry;
import com.cloud.xp3.data.Store;
import com.cloud.xp3.data.Transcript;
import com.cloud.xp3.net.Cloud;
import com.cloud.xp3.net.Sse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * One screen: the conversation with a Claude Code session in the cloud, and a
 * box to add to it.
 *
 * There is only ever one session. The first message creates it; after that
 * every message goes to it, until New session forgets it (the session itself
 * stays on claude.ai/code). What is on screen is built from the session's
 * event stream, which stays open while the app is visible.
 *
 * Nothing on this side of the app touches a network. Requests run in order on
 * one worker, the stream on its link's thread, and both come back through the
 * main thread's handler. A generation number stops anything that was in
 * flight for a forgotten session from landing in the new one.
 *
 * Pictures come in from the menu the way the phone's Messages app takes
 * them: the stock camera writing through a content:// address this app
 * serves (CaptureProvider), or the stock Gallery's picker. They wait above the
 * box, shrunk and saved, until the next message carries them.
 */
public final class MainActivity extends Activity {

    private static final int FOCUS_NONE = 0;
    private static final int FOCUS_LIST = 1;
    private static final int FOCUS_INPUT = 2;

    /** Menu item ids. The menu is rebuilt on every opening. */
    private static final int MENU_COPY = 1;
    private static final int MENU_ALLOW = 2;
    private static final int MENU_DENY = 3;
    private static final int MENU_INTERRUPT = 4;
    private static final int MENU_PASTE = 5;
    private static final int MENU_LINK = 6;
    private static final int MENU_NEW = 7;
    private static final int MENU_TAKE = 8;
    private static final int MENU_PICK = 9;
    private static final int MENU_REMOVE = 10;

    /** Activity results. */
    private static final int TAKE_PICTURE = 1;
    private static final int PICK_PICTURE = 2;

    private static final int MAX_TITLE = 60;
    private static final long SAVE_DELAY_MS = 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService saves = pool("cloud-save");
    private final ExecutorService requests = pool("cloud-request");
    private final ExecutorService picturesWork = pool("cloud-pictures");

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

    private Backend backend;
    private Pictures pictures;
    /** Pictures still being shrunk; a message waits for them. */
    private int preparing;
    private FrameLayout content;
    private TextView titleView;
    private TextView stateView;

    // ------------------------------------------------------------------
    // The session
    // ------------------------------------------------------------------

    /** Null until the first message has created a session. */
    private String sessionId;
    private String title;
    private Transcript transcript;
    /** The first message is on its way and the session does not exist yet. */
    private boolean creating;
    private Link link;
    private boolean online;
    /** Bumped by New session; anything older that comes back is dropped. */
    private int generation;
    private boolean savePending;

    // ------------------------------------------------------------------
    // The screen
    // ------------------------------------------------------------------

    private String draft = "";
    private ChatListView chatList;
    private ChatAdapter chatAdapter;
    /** -1 means no restore; MAX_VALUE means the transcript was at its end. */
    private int savedScrollPosition = AdapterView.INVALID_POSITION;

    private int menuPosition = -1;
    private Entry menuEntry;
    private boolean okHeld;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        backend = new Backend(getFilesDir());
        pictures = new Pictures(getFilesDir());
        // The camera can cost this activity its life on a phone this small.
        if (state != null) draft = state.getString("draft", "");
        Store.Saved saved = backend.session();
        sessionId = saved.id;
        title = saved.title;
        transcript = saved.transcript;

        content = findViewById(R.id.content);
        titleView = findViewById(R.id.title);
        stateView = findViewById(R.id.state);
        // Every context menu is answered here; see showMenu.
        registerForContextMenu(content);
        render(FOCUS_INPUT);
    }

    @Override
    protected void onStart() {
        super.onStart();
        connect();
    }

    @Override
    protected void onStop() {
        disconnect();
        saveNow();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("draft", draft);
    }

    @Override
    protected void onPause() {
        saveScrollPosition();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
        // Let the last save land; a request in flight is not worth holding
        // the process for once the screen is gone.
        saves.shutdown();
        picturesWork.shutdown();
        requests.shutdownNow();
    }

    // ------------------------------------------------------------------
    // The stream
    // ------------------------------------------------------------------

    private void connect() {
        if (link != null || sessionId == null) return;
        final int forGeneration = generation;
        link = backend.link(sessionId, transcript.sequence(), new Link.Listener() {
            @Override
            public void frame(final Sse.Frame frame) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        received(frame, forGeneration);
                    }
                });
            }

            @Override
            public void online(final boolean up) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (forGeneration != generation) return;
                        online = up;
                        updateHeader();
                    }
                });
            }
        });
        if (link != null) link.start();
        updateHeader();
    }

    private void disconnect() {
        if (link == null) return;
        link.stop();
        link = null;
        online = false;
        updateHeader();
    }

    private void received(Sse.Frame frame, int forGeneration) {
        if (forGeneration != generation || isFinishing()) return;
        long before = transcript.sequence();
        if (transcript.apply(frame.event, frame.id, frame.data)) {
            updateChat();
            updateHeader();
        }
        if (transcript.sequence() != before) scheduleSave();
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    private void send(String text) {
        final String message = text.trim();
        if (message.length() == 0 && pictures.draft().isEmpty()) return;
        if (preparing > 0) {
            toast("Still adding the picture");
            return;
        }
        if (!backend.ready()) {
            transcript.note(Backend.NO_LOGIN);
            render(FOCUS_INPUT);
            return;
        }
        if (creating) {
            toast("Still starting the session");
            return;
        }
        final String uuid = UUID.randomUUID().toString();
        final int forGeneration = generation;
        final List<File> attached = pictures.commit(uuid);
        transcript.sent(uuid, message, attached.size());
        draft = "";
        if (sessionId == null) {
            creating = true;
            title = message.length() > 0 ? titleFor(message) : attached.size() == 1 ? "Picture" : "Pictures";
            final String sessionTitle = title;
            requests.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final String id = backend.create(sessionTitle, uuid, message, attached);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                created(id, forGeneration);
                            }
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        failed("Could not start the session: " + reason(e), forGeneration);
                    }
                }
            });
        } else {
            final String session = sessionId;
            requests.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        backend.send(session, uuid, message, attached);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        failed("Not sent: " + reason(e), forGeneration);
                    }
                }
            });
        }
        saveNow();
        render(FOCUS_INPUT);
    }

    private void created(String id, int forGeneration) {
        if (forGeneration != generation || isFinishing()) return;
        creating = false;
        sessionId = id;
        saveNow();
        connect();
    }

    /** Called on the request worker. */
    private void failed(final String problem, final int forGeneration) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (forGeneration != generation || isFinishing()) return;
                if (creating) {
                    creating = false;
                    title = null;
                }
                transcript.note(problem);
                transcript.idle();
                saveNow();
                updateChat();
                updateHeader();
            }
        });
    }

    /** The first line of the first message, which is what Claude Code titles a session with too. */
    private static String titleFor(String message) {
        String line = message.split("\n", 2)[0].trim();
        return line.length() <= MAX_TITLE ? line : line.substring(0, MAX_TITLE - 1) + "…";
    }

    private void interrupt() {
        if (sessionId == null) return;
        final String session = sessionId;
        requests.execute(new Runnable() {
            @Override
            public void run() {
                String problem = null;
                try {
                    backend.interrupt(session);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    problem = reason(e);
                }
                final String said = problem == null ? "Interrupt sent" : "Interrupt not sent: " + problem;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(said);
                    }
                });
            }
        });
    }

    /**
     * Answers the permission request Claude is waiting on. Auto mode settles
     * most calls itself; this is for the ones it still asks a person about.
     */
    private void answer(final boolean allow) {
        final Transcript.Permission pending = transcript.pending();
        if (pending == null || sessionId == null) return;
        final String session = sessionId;
        final int forGeneration = generation;
        transcript.answered();
        transcript.note((allow ? "Allowed " : "Denied ") + pending.tool + ".");
        scheduleSave();
        updateChat();
        requests.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    backend.answer(session, pending.requestId, allow, pending.input);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    failed("Answer not sent: " + reason(e), forGeneration);
                }
            }
        });
    }

    private void confirmNewSession() {
        if (sessionId == null && transcript.size() == 0) return;
        new AlertDialog.Builder(this)
            .setTitle("New session")
            .setMessage("Start over? This session stays on claude.ai/code.")
            .setPositiveButton("Start over", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    newSession();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void newSession() {
        generation++;
        disconnect();
        sessionId = null;
        title = null;
        creating = false;
        transcript = new Transcript();
        saveNow();
        // The forgotten session's pictures are not coming back on screen.
        picturesWork.execute(new Runnable() {
            @Override
            public void run() {
                pictures.forgetSent();
            }
        });
        render(FOCUS_INPUT);
    }

    // ------------------------------------------------------------------
    // Pictures
    // ------------------------------------------------------------------

    /**
     * The stock camera, told to write its full-size shot to CaptureProvider,
     * as Messages' "Capture picture" does. The grant is for this one intent.
     */
    private void takePicture() {
        File target = Pictures.capture(getFilesDir());
        target.delete();
        Uri output = CaptureProvider.uri();
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, output);
        intent.setClipData(ClipData.newRawUri("", output));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launch(intent, TAKE_PICTURE, "No camera app on this phone");
    }

    /**
     * The stock Gallery's picker, as Messages' "Attach photos/videos" opens
     * it, or whatever picks images when that Gallery is not installed.
     */
    private void pickPicture() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        Intent gallery = new Intent(intent)
            .setComponent(new ComponentName("com.android.gallery3d", "com.android.gallery3d.app.Gallery"))
            .putExtra("multipick", true);
        if (gallery.resolveActivity(getPackageManager()) != null) {
            launch(gallery, PICK_PICTURE, "No gallery on this phone");
        } else {
            launch(Intent.createChooser(intent, "Attach picture"), PICK_PICTURE, "No gallery on this phone");
        }
    }

    private void launch(Intent intent, int request, String missing) {
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(missing);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK) return;
        if (request == TAKE_PICTURE) {
            final File shot = Pictures.capture(getFilesDir());
            if (shot.isFile() && shot.length() > 0) {
                prepare(new Pictures.Source() {
                    @Override
                    public InputStream open() throws IOException {
                        return new FileInputStream(shot);
                    }
                }, shot);
                return;
            }
            // A camera that ignored EXTRA_OUTPUT hands back only a thumbnail.
            Object thumbnail = data == null || data.getExtras() == null ? null : data.getExtras().get("data");
            if (thumbnail instanceof Bitmap) prepareBitmap((Bitmap) thumbnail);
            else toast("The camera did not return a picture");
        } else if (request == PICK_PICTURE && data != null) {
            List<Uri> chosen = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    if (clip.getItemAt(i).getUri() != null) chosen.add(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                chosen.add(data.getData());
            }
            int room = Pictures.MAX_DRAFT - pictures.draft().size() - preparing;
            if (chosen.size() > room) toast("Only " + Pictures.MAX_DRAFT + " pictures fit in one message");
            for (int i = 0; i < chosen.size() && i < room; i++) {
                final Uri uri = chosen.get(i);
                prepare(new Pictures.Source() {
                    @Override
                    public InputStream open() throws IOException {
                        InputStream in = getContentResolver().openInputStream(uri);
                        if (in == null) throw new IOException("That picture could not be opened");
                        return in;
                    }
                }, null);
            }
        }
    }

    /** Shrinks a picture off the main thread and adds it to the draft. `consumed` is deleted after. */
    private void prepare(final Pictures.Source source, final File consumed) {
        preparing++;
        picturesWork.execute(new Runnable() {
            @Override
            public void run() {
                String problem = null;
                try {
                    pictures.addToDraft(Pictures.prepare(source));
                } catch (IOException e) {
                    problem = reason(e);
                } catch (SecurityException e) {
                    problem = "That picture could not be opened";
                } finally {
                    if (consumed != null) consumed.delete();
                }
                prepared(problem);
            }
        });
        toast("Adding picture…");
    }

    private void prepareBitmap(final Bitmap bitmap) {
        preparing++;
        picturesWork.execute(new Runnable() {
            @Override
            public void run() {
                String problem = null;
                try {
                    pictures.addToDraft(Pictures.prepare(bitmap));
                } catch (IOException e) {
                    problem = reason(e);
                }
                prepared(problem);
            }
        });
    }

    /** Called on the pictures worker. */
    private void prepared(final String problem) {
        main.post(new Runnable() {
            @Override
            public void run() {
                preparing--;
                if (isFinishing()) return;
                if (problem != null) toast(problem);
                saveScrollPosition();
                render(FOCUS_INPUT);
            }
        });
    }

    private void removePictures() {
        preparing++;
        picturesWork.execute(new Runnable() {
            @Override
            public void run() {
                pictures.clearDraft();
                prepared(null);
            }
        });
    }

    /**
     * The pictures waiting for the next message, as a row of small thumbnails
     * above the box. Not focusable: Menu is how they are removed.
     */
    private View draftStrip() {
        List<File> waiting = pictures.draft();
        if (waiting.isEmpty()) return null;
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(6), dp(4), dp(6), 0);
        strip.setBackgroundColor(color(R.color.chat_page));
        for (File file : waiting) {
            Bitmap bitmap = pictures.thumbnail(file, dp(64));
            if (bitmap == null) continue;
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(36));
            params.rightMargin = dp(4);
            strip.addView(image, params);
        }
        TextView label = new TextView(this);
        label.setText(waiting.size() == 1 ? "1 picture" : waiting.size() + " pictures");
        label.setTextSize(11);
        label.setTextColor(color(R.color.text_muted));
        label.setPadding(dp(2), 0, 0, 0);
        strip.addView(label);
        return strip;
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    /**
     * Writes the session down after a burst of frames has settled, rather than
     * once per frame: a reconnect can replay hundreds of them at once.
     */
    private void scheduleSave() {
        if (savePending) return;
        savePending = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (savePending) saveNow();
            }
        }, SAVE_DELAY_MS);
    }

    /** Saves a snapshot, in order, off the main thread. */
    private void saveNow() {
        savePending = false;
        final String id = sessionId;
        final String name = title;
        final Transcript snapshot = new Transcript(transcript.entries(), transcript.sequence(), transcript.working());
        saves.execute(new Runnable() {
            @Override
            public void run() {
                backend.save(id, name, snapshot);
            }
        });
    }

    // ------------------------------------------------------------------
    // The screen
    // ------------------------------------------------------------------

    private void updateHeader() {
        titleView.setText(title != null ? title : getString(R.string.new_session));
        String state;
        if (sessionId == null) state = creating ? "Starting" : "";
        else if (!online) state = "Offline";
        else if (transcript.pending() != null) state = "Waiting";
        else if (transcript.working()) state = "Working";
        else state = "Ready";
        stateView.setText(state);
    }

    /** The working line under the transcript, or null when nothing is running. */
    private String workingLine() {
        if (creating) return "Starting the session…";
        if (!transcript.working() || transcript.pending() != null) return null;
        return transcript.status();
    }

    /**
     * Repaints the rows without rebuilding the screen, so the cursor, the
     * draft and the T9 keyboard's half-finished word survive a new event.
     */
    private void updateChat() {
        if (chatAdapter == null || chatList == null) return;
        boolean follow = chatList.isPinnedToBottom() || isAtBottom(chatList, chatAdapter);
        chatAdapter.replace(transcript.entries(), workingLine());
        if (follow) chatList.pinToBottomOnce();
    }

    private void render(int focus) {
        updateHeader();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        final ChatAdapter adapter = new ChatAdapter(this, pictures);
        adapter.replace(transcript.entries(), workingLine());
        final ChatListView list = new ChatListView(this);
        list.setAdapter(adapter);
        // Rows paint their own focus state edge to edge; a transparent
        // selector stops the platform drawing a second highlight under them.
        list.setSelector(new ColorDrawable(0x00000000));
        list.setCacheColorHint(0x00000000);
        list.setItemsCanFocus(false);
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
        chatAdapter = adapter;
        chatList = list;
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        /* A compact three-line compose field leaves the transcript most of the
         * screen. OK sends, so the 240px-wide layout needs no separate button. */
        final EditText input = new EditText(this);
        input.setTextSize(16);
        input.setTextColor(color(R.color.input_text));
        input.setHintTextColor(color(R.color.input_hint));
        input.setBackgroundResource(R.drawable.edittext_border);
        input.setPadding(dp(8), dp(8), dp(8), dp(8));
        input.setHint(getString(R.string.chat_hint));
        input.setText(draft);
        input.setSelection(draft.length());
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
                draft = text.toString();
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
        int listId = View.generateViewId();
        int inputId = View.generateViewId();
        list.setId(listId);
        input.setId(inputId);
        list.setNextFocusDownId(inputId);
        input.setNextFocusUpId(listId);
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                // Reaching the composer proves the transcript was read to its
                // end. Keep the last row's bottom fixed while typing.
                list.setPinnedToBottom(hasFocus);
            }
        });
        /*
         * OK sends, on the press rather than the release: with the T9 keyboard
         * up, the first ENTER release never reaches the app because the
         * keyboard takes it to finish the word it is composing. Both halves
         * are swallowed so ENTER cannot also drop a newline into the field.
         */
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                // Up out of the box once there is no line of your own above
                // the cursor, without the platform's extra dead press.
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    if (!onFirstLine((EditText) view)) return false;
                    if (list.getCount() == 0) return false;
                    list.requestFocus();
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
        View strip = draftStrip();
        if (strip != null) root.addView(strip);
        root.addView(input, inputParams);

        wireList(list, adapter);
        content.removeAllViews();
        content.addView(root, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        restoreScrollPosition(list, adapter);
        if (focus == FOCUS_INPUT) input.requestFocus();
        else if (focus == FOCUS_LIST && adapter.getCount() > 0) list.requestFocus();
        else if (focus == FOCUS_LIST) input.requestFocus();
    }

    /**
     * Stepping through the transcript. Up out of the box puts the highlight
     * on the newest row; down off the newest one puts it back in the box.
     * The highlight is dropped whenever the list loses focus.
     */
    private void wireList(final ChatListView list, final ChatAdapter adapter) {
        list.setFocusable(true);
        list.setFocusableInTouchMode(true);
        list.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (adapter.getCount() == 0) return;
                if (hasFocus) {
                    list.setPinnedToBottom(false);
                    if (adapter.getSelectedPosition() == AdapterView.INVALID_POSITION) {
                        int selected = list.getSelectedItemPosition();
                        if (selected == AdapterView.INVALID_POSITION) selected = list.getLastVisiblePosition();
                        adapter.trackSelectedPosition(selected);
                        View selectedView = list.getSelectedView();
                        if (selectedView != null) selectedView.setSelected(true);
                    }
                } else {
                    View selectedView = list.getSelectedView();
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
                // OK on a row does nothing; holding it opens the menu.
                if (!isOk(keyCode)) return false;
                heldForMenu(event);
                return true;
            }
        });
    }

    /**
     * Records intent rather than a pixel offset. MAX_VALUE means "the end", so
     * returning after the transcript grows resolves against its new count.
     */
    private void saveScrollPosition() {
        if (chatList == null || chatAdapter == null || chatAdapter.getCount() == 0) return;
        savedScrollPosition = chatList.getLastVisiblePosition() >= chatAdapter.getCount() - 1
            ? Integer.MAX_VALUE
            : chatList.getFirstVisiblePosition();
    }

    private void restoreScrollPosition(ChatListView list, ChatAdapter adapter) {
        int count = adapter.getCount();
        int saved = savedScrollPosition;
        savedScrollPosition = AdapterView.INVALID_POSITION;
        if (count == 0) return;
        int position = saved >= 0 && saved != Integer.MAX_VALUE ? Math.min(saved, count - 1) : count - 1;
        list.setSelection(position);
        if (saved == Integer.MAX_VALUE || saved == AdapterView.INVALID_POSITION) list.pinToBottomOnce();
    }

    /** Whether the final row is fully visible inside the padded viewport. */
    private static boolean isAtBottom(ListView list, ChatAdapter adapter) {
        int target = adapter.getCount() - 1;
        int last = list.getLastVisiblePosition();
        if (target < 0 || last != target) return false;
        View row = list.getChildAt(last - list.getFirstVisiblePosition());
        return row != null && row.getBottom() <= list.getHeight() - list.getPaddingBottom();
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /**
     * Holding OK opens the menu, as a long press does in the phone's own
     * lists. Every OK press is claimed here so ListView's own long press,
     * which would open a menu as a centred dialog, never runs.
     */
    private void heldForMenu(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) okHeld = false;
            else if (!okHeld) {
                okHeld = true;
                showMenu();
            }
            return;
        }
        okHeld = false;
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** Whether the cursor sits on the first line of what has been typed. */
    private static boolean onFirstLine(EditText field) {
        Layout laid = field.getLayout();
        if (laid == null) return true;
        return laid.getLineForOffset(field.getSelectionStart()) == 0;
    }

    /*
     * Menu opens on its release, as the platform's own menus do. Opened on
     * the press, the release would arrive at the popup once it had taken
     * focus, and the popup closes on a Menu release.
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
            if (event.isTracking() && !event.isCanceled()) showMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------------
    // The Menu key
    // ------------------------------------------------------------------

    /*
     * Menu, or holding OK, opens the platform's own context menu at the thing
     * it acts on: the highlighted row, or the foot of the transcript. Opening
     * it at a point inside the ListView (never on the row, whose parent would
     * be scrolled to fit it) is what makes Android draw the stock popup beside
     * that point rather than a dialog in the middle of the screen.
     */

    private final List<Integer> menuIds = new ArrayList<>();
    private final List<String> menuTitles = new ArrayList<>();

    private void showMenu() {
        if (chatList == null || chatAdapter == null) return;
        menuPosition = -1;
        menuEntry = null;
        menuIds.clear();
        menuTitles.clear();

        View anchor = chatList;
        float y = chatList.getHeight();
        if (chatList.hasFocus()) {
            int position = chatAdapter.getSelectedPosition();
            if (chatAdapter.isEntry(position)) {
                menuPosition = position;
                menuEntry = chatAdapter.getItem(position);
                add(MENU_COPY, "Copy text");
                View row = chatList.getChildAt(position - chatList.getFirstVisiblePosition());
                if (row != null) y = Math.max(0, Math.min(row.getBottom(), chatList.getHeight()));
            }
        }
        Transcript.Permission pending = transcript.pending();
        if (pending != null && sessionId != null) {
            add(MENU_ALLOW, "Allow " + pending.tool);
            add(MENU_DENY, "Deny " + pending.tool);
        }
        int attached = pictures.draft().size() + preparing;
        if (attached < Pictures.MAX_DRAFT) {
            add(MENU_TAKE, "Take picture");
            add(MENU_PICK, "Attach picture");
        }
        if (attached > 0) add(MENU_REMOVE, attached == 1 ? "Remove picture" : "Remove pictures");
        if (sessionId != null && transcript.working()) add(MENU_INTERRUPT, "Interrupt");
        if (clipboardText() != null) add(MENU_PASTE, "Paste");
        if (sessionId != null) add(MENU_LINK, "Copy session link");
        add(MENU_NEW, "New session");

        anchor.showContextMenu(Math.min(dp(32), anchor.getWidth() / 2f), y);
    }

    private void add(int id, String title) {
        menuIds.add(Integer.valueOf(id));
        menuTitles.add(title);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo info) {
        for (int i = 0; i < menuIds.size(); i++) {
            menu.add(Menu.NONE, menuIds.get(i).intValue(), i, menuTitles.get(i));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_COPY:
                if (menuEntry != null) copy(menuEntry.text, "Copied");
                break;
            case MENU_ALLOW:
                answer(true);
                break;
            case MENU_DENY:
                answer(false);
                break;
            case MENU_INTERRUPT:
                interrupt();
                break;
            case MENU_PASTE:
                paste();
                break;
            case MENU_LINK:
                if (sessionId != null) copy(Cloud.link(sessionId), "Link copied");
                break;
            case MENU_NEW:
                confirmNewSession();
                break;
            case MENU_TAKE:
                takePicture();
                break;
            case MENU_PICK:
                pickPicture();
                break;
            case MENU_REMOVE:
                removePictures();
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // A long press the platform handles itself gets no menu.
        menuIds.clear();
        menuTitles.clear();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void copy(String text, String said) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Cloud", text));
        toast(said);
    }

    /** What is on the clipboard as text, or null when there is nothing usable. */
    private CharSequence clipboardText() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()
            || clipboard.getPrimaryClip().getItemCount() == 0) return null;
        CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        return pasted == null || pasted.length() == 0 ? null : pasted;
    }

    private void paste() {
        CharSequence pasted = clipboardText();
        if (pasted == null) return;
        draft = draft + pasted.toString();
        saveScrollPosition();
        render(FOCUS_INPUT);
    }

    /** An exception as a sentence, never empty. */
    private static String reason(Exception e) {
        if (e instanceof RuntimeException) return "the cloud's answer could not be read.";
        String message = e.getMessage();
        return message == null || message.length() == 0 ? "the network could not be reached." : message;
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
