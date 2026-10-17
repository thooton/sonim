package com.canvas.xp3;

import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Draft;

import java.util.ArrayList;
import java.util.List;

/**
 * Write an answer (D2): a full-screen editor on the phone's own keypad input.
 *
 * It saves to the phone a moment after every pause, so a dropped call or a
 * flat battery costs a sentence at most, and the footer says so: a running
 * word count on the left, "Saved on phone" on the right. Insert adds a
 * numbered line or a symbol, the things a keypad makes slow.
 */
final class EditorScreen extends Screen {

    private final Assignment work;
    private Draft.Piece piece;
    private EditText field;
    private TextView count;
    private TextView saved;
    private final Runnable save = this::saveNow;

    EditorScreen(MainActivity app, Assignment work, Draft.Piece piece) {
        super(app);
        this.work = work;
        Draft draft = app.backend.draft(work.id);
        if (piece == null) {
            int answers = 0;
            for (Draft.Piece p : draft.pieces) if (Draft.TEXT.equals(p.kind)) answers++;
            String name = answers == 0 ? "My answer" : "My answer " + (answers + 1);
            piece = Draft.Piece.text("t" + System.currentTimeMillis(), name, "");
        }
        this.piece = piece;
    }

    @Override
    String left() {
        return "Insert";
    }

    @Override
    String center() {
        return "Done";
    }

    @Override
    String right() {
        return "Clear";
    }

    @Override
    void onRight() {
        // Clear takes back the last word: one key per word is what makes a
        // T9 slip cheap to undo, without the risk of losing the whole answer.
        int end = field.getSelectionStart();
        if (field.getSelectionEnd() != end) {
            field.getText().delete(end, field.getSelectionEnd());
            return;
        }
        String text = field.getText().toString();
        int start = end;
        while (start > 0 && Character.isWhitespace(text.charAt(start - 1))) start--;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        field.getText().delete(start, end);
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        root.addView(parts.nav("Your work", (String) null), Views.wide());
        TextView title = v.text(piece.name, 14, look.heavy, look.ink);
        title.setLetterSpacing(-0.01f);
        v.pad(title, 12, 0, 12, 0);
        root.addView(title, Views.wide());
        field = Fields.box(app, v, piece.text == null ? "" : piece.text, "Type your answer",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setBackground(Fields.background(v, look.mark, 3));
        field.setOnFocusChangeListener(null);
        field.setPadding(v.dp(9), v.dp(8), v.dp(9), v.dp(8));
        field.setVerticalScrollBarEnabled(true);
        root.addView(field, v.margins(Views.tall(), 10, 4, 10, 0));
        LinearLayout foot = v.row();
        v.pad(foot, 12, 4, 12, 5);
        count = v.text("", 9, look.semibold, look.muted);
        foot.addView(count, Views.fill());
        saved = v.text("", 9, look.semibold, look.muted);
        saved.setGravity(Gravity.RIGHT);
        foot.addView(saved);
        root.addView(foot, Views.wide());
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int n, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int n) {}

            @Override
            public void afterTextChanged(Editable s) {
                counted();
                saved.setText("");
                app.main.removeCallbacks(save);
                app.main.postDelayed(save, 2000);
            }
        });
        counted();
        saved.setText(piece.text != null && piece.text.length() > 0 ? "✓ Saved on phone" : "");
        return root;
    }

    @Override
    void shown() {
        field.requestFocus();
        field.setSelection(field.getText().length());
        app.refreshKeys();
    }

    private void counted() {
        String text = field.getText().toString().trim();
        int words = text.length() == 0 ? 0 : text.split("\\s+").length;
        count.setText(words + (words == 1 ? " word" : " words"));
    }

    private void saveNow() {
        app.main.removeCallbacks(save);
        String text = field.getText().toString();
        piece = piece.withText(text);
        if (text.trim().length() == 0) return;
        Draft draft = app.backend.draft(work.id);
        try {
            app.backend.saveDraft(draft.with(piece, System.currentTimeMillis()));
            if (saved != null) saved.setText("✓ Saved on phone");
        } catch (java.io.IOException e) {
            if (saved != null) saved.setText("Not saved");
        }
    }

    @Override
    boolean onKey(KeyEvent event) {
        // OK is Done: the text field would otherwise take it as a new line,
        // which the keypad already gives with its own key.
        if (com.xp3.common.ui.Ui.isOk(event.getKeyCode()) && app.getCurrentFocus() == field) {
            if (event.getAction() == KeyEvent.ACTION_UP) done();
            return true;
        }
        return false;
    }

    private void done() {
        saveNow();
        if (field.getText().toString().trim().length() == 0) {
            app.pop();
            return;
        }
        app.finishWith(piece);
    }

    @Override
    boolean onBack() {
        saveNow();
        if (field.getText().toString().trim().length() > 0) {
            app.finishWith(piece);
            return true;
        }
        return false;
    }

    @Override
    void closed() {
        app.main.removeCallbacks(save);
    }

    @Override
    boolean globalOptions() {
        return false;
    }

    @Override
    List<Sheet.Choice> options() {
        List<Sheet.Choice> out = new ArrayList<>();
        out.add(new Sheet.Choice("Numbered line", R.drawable.ic_assignment, null, this::numbered));
        out.add(new Sheet.Choice("New line", R.drawable.ic_edit, null, () -> insert("\n")));
        out.add(new Sheet.Choice("Symbol", R.drawable.ic_add, null, this::symbols));
        ClipboardManager clipboard = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            out.add(new Sheet.Choice("Paste", R.drawable.ic_doc, null, () -> {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(app);
                if (text != null) insert(text.toString());
            }));
        }
        out.add(new Sheet.Choice("Rename", R.drawable.ic_edit, null, () -> app.push(new FieldScreen(app, "Rename",
            piece.name, "Name", value -> {
                if (value.trim().length() > 0) piece = piece.renamed(value.trim());
                saveNow();
            }))));
        return out;
    }

    /** Starts a line numbered one past the last numbered line above it. */
    private void numbered() {
        String before = field.getText().toString().substring(0, field.getSelectionStart());
        int next = 1;
        String[] lines = before.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\. ").matcher(lines[i]);
            if (m.find()) {
                next = Integer.parseInt(m.group(1)) + 1;
                break;
            }
        }
        boolean fresh = before.length() == 0 || before.endsWith("\n");
        insert((fresh ? "" : "\n") + next + ". ");
    }

    private void symbols() {
        final String[] marks = { ",", ".", "?", "!", "'", "\"", ":", ";", "-", "(", ")", "&", "@", "/" };
        List<Sheet.Choice> choices = new ArrayList<>();
        for (final String mark : marks) {
            choices.add(new Sheet.Choice(mark, R.drawable.ic_edit, null, () -> insert(mark)));
        }
        Sheet.pick(app, "Insert a symbol", choices, null);
    }

    private void insert(String text) {
        int start = Math.max(0, field.getSelectionStart());
        int end = Math.max(start, field.getSelectionEnd());
        field.getText().replace(start, end, text);
        field.requestFocus();
    }
}
