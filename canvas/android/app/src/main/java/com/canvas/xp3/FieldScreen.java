package com.canvas.xp3;

import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * One value to type: a name for a piece of work, or a Canvas setting.
 *
 * OK saves. Paste is in Options, because the settings are the fields nobody
 * types on a keypad: a service token is forty random characters, and the
 * intended route is a message to the phone, then copy and paste.
 */
final class FieldScreen extends Screen {

    interface Saved {
        void value(String text);
    }

    private final String title;
    private final String start;
    private final String label;
    private final Saved saved;
    private int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    private String help;
    private EditText field;

    FieldScreen(MainActivity app, String title, String start, String label, Saved saved) {
        super(app);
        this.title = title;
        this.start = start;
        this.label = label;
        this.saved = saved;
    }

    /** For an address or a credential: no capitals and no corrections, which would silently break it. */
    FieldScreen exact(boolean address) {
        type = InputType.TYPE_CLASS_TEXT | (address ? InputType.TYPE_TEXT_VARIATION_URI
            : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        return this;
    }

    FieldScreen help(String text) {
        help = text;
        return this;
    }

    @Override
    String left() {
        return "Options";
    }

    @Override
    String center() {
        return "Save";
    }

    @Override
    boolean globalOptions() {
        return false;
    }

    @Override
    List<Sheet.Choice> options() {
        List<Sheet.Choice> out = new ArrayList<>();
        final ClipboardManager clipboard = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            out.add(new Sheet.Choice("Paste", R.drawable.ic_doc, null, () -> {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(app);
                if (text == null) return;
                field.setText(text.toString().trim());
                field.setSelection(field.getText().length());
            }));
        }
        out.add(new Sheet.Choice("Clear", R.drawable.ic_undo, null, () -> field.setText("")));
        out.add(new Sheet.Choice("Cancel", R.drawable.ic_undo, null, app::pop));
        return out;
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        root.addView(parts.nav("Back", (String) null), Views.wide());
        TextView heading = v.screenTitle(title);
        v.pad(heading, 12, 0, 12, 0);
        root.addView(heading, Views.wide());
        TextView name = v.label(label);
        v.pad(name, 12, 8, 12, 3);
        root.addView(name, Views.wide());
        field = Fields.box(app, v, field == null ? start : field.getText().toString(), null, type);
        field.setSingleLine(true);
        root.addView(field, v.margins(Views.wide(), 12, 0, 12, 0));
        if (help != null) {
            TextView note = v.text(help, 9, look.regular, look.muted);
            note.setLineSpacing(0, 1.25f);
            v.pad(note, 12, 5, 12, 0);
            root.addView(note, Views.wide());
        }
        return root;
    }

    @Override
    void shown() {
        field.requestFocus();
        app.refreshKeys();
    }

    @Override
    boolean onKey(KeyEvent event) {
        if (com.xp3.common.ui.Ui.isOk(event.getKeyCode())) {
            // Acting on the release: dismissing on the press would hand the
            // release to the screen underneath, which reads it as OK again.
            if (event.getAction() == KeyEvent.ACTION_UP) {
                String value = field.getText().toString();
                app.pop();
                saved.value(value);
            }
            return true;
        }
        return false;
    }
}
