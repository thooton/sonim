package com.canvas.xp3;

import android.text.InputType;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Draft;
import com.xp3.common.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Add a link (D7). Typing a web address on a keypad is slow, so the common
 * pieces (https://, www., .com and /) sit under the field, one press each.
 */
final class LinkScreen extends Screen {

    private static final String[] PIECES = { "https://", "www.", ".com", "/" };

    private final Assignment work;
    private final Draft.Piece editing;
    private EditText link;
    private EditText name;
    private LinearLayout chips;

    LinkScreen(MainActivity app, Assignment work, Draft.Piece editing) {
        super(app);
        this.work = work;
        this.editing = editing;
    }

    @Override
    List<Sheet.Choice> options() {
        List<Sheet.Choice> out = new ArrayList<>();
        final View focus = app.getCurrentFocus();
        if (focus instanceof EditText) {
            out.add(new Sheet.Choice("Clear", R.drawable.ic_undo, null, () -> ((EditText) focus).setText("")));
        }
        out.add(new Sheet.Choice("Cancel", R.drawable.ic_undo, null, app::pop));
        return out;
    }

    @Override
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (Ui.isOk(code) && app.getCurrentFocus() instanceof EditText) {
            if (event.getAction() == KeyEvent.ACTION_UP) add();
            return true;
        }
        View focus = app.getCurrentFocus();
        boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN;
        // A text field keeps Up and Down for itself (the cursor), so the
        // d-pad would never reach the chips; step between the rows by hand.
        if (focus instanceof EditText && (down || code == KeyEvent.KEYCODE_DPAD_UP) && chips != null
            && chips.getChildCount() > 0 && (focus == link ? down : !down)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) chips.getChildAt(0).requestFocus();
            return true;
        }
        // Up from the first row of chips goes back to the Link field, Down to Title.
        if (focus instanceof Item && chips != null && chips.indexOfChild(focus) >= 0
            && (down || code == KeyEvent.KEYCODE_DPAD_UP)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) (down ? name : link).requestFocus();
            return true;
        }
        if (focus instanceof Item && chips != null && chips.indexOfChild(focus) >= 0
            && code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_0 + PIECES.length) {
            if (event.getAction() == KeyEvent.ACTION_UP) insert(PIECES[code - KeyEvent.KEYCODE_1]);
            return true;
        }
        return false;
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        root.addView(parts.nav("Add work"), Views.wide());
        TextView heading = v.screenTitle("Add a link");
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16 * look.scale);
        v.pad(heading, 12, 0, 12, 0);
        root.addView(heading, Views.wide());

        TextView label = v.label("Link");
        v.pad(label, 12, 8, 12, 3);
        root.addView(label, Views.wide());
        link = Fields.box(app, v, link != null ? link.getText().toString() : editing == null ? "https://" : editing.url,
            null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setSingleLine(true);
        root.addView(link, v.margins(Views.wide(), 12, 0, 12, 0));

        chips = v.row();
        v.pad(chips, 12, 7, 12, 0);
        for (int i = 0; i < PIECES.length; i++) {
            final String piece = PIECES[i];
            Item chip = new Item(app, "chip:" + i, () -> insert(piece));
            LinearLayout inside = v.row();
            v.pad(inside, 6, 2, 6, 2);
            inside.addView(v.text(Integer.toString(i + 1), 9.5f, look.semibold, look.muted));
            TextView word = v.text(piece, 9.5f, look.bold, look.ink);
            inside.addView(word, v.margins(Views.wrap(), 3, 0, 0, 0));
            chip.addView(inside);
            chip.fills(v.round(0, 6, look.line, 1), v.round(look.mark, 6, look.mark, 1), new TextView[] { word },
                new int[] { look.ink });
            chips.addView(chip, v.margins(Views.wrap(), 0, 0, 4, 0));
        }
        root.addView(chips, Views.wide());

        TextView named = v.label("Title (optional)");
        v.pad(named, 12, 10, 12, 3);
        root.addView(named, Views.wide());
        name = Fields.box(app, v, name != null ? name.getText().toString() : editing == null ? "" : editing.name,
            "Name on your work list", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setSingleLine(true);
        root.addView(name, v.margins(Views.wide(), 12, 0, 12, 0));
        return root;
    }

    @Override
    void shown() {
        link.requestFocus();
        link.setSelection(link.getText().length());
    }

    /** The digit keys pick a piece too, while the d-pad is on the chips. */
    private void insert(String piece) {
        int at = Math.max(0, link.getSelectionStart());
        link.getText().insert(at, piece);
        link.requestFocus();
    }

    private void add() {
        String url = link.getText().toString().trim();
        if (url.length() < 8 || !url.matches("(?i)https?://[^\\s]+\\.[^\\s]+")) {
            app.say("Type the whole address, like https://example.com");
            return;
        }
        String title = name.getText().toString().trim();
        if (title.length() == 0) title = url.replaceFirst("(?i)^https?://(www\\.)?", "");
        String id = editing == null ? "l" + System.currentTimeMillis() : editing.id;
        app.finishWith(Draft.Piece.link(id, title, url));
    }

    @Override
    boolean globalOptions() {
        return false;
    }
}
