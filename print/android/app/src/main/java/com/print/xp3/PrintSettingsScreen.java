package com.print.xp3;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.print.xp3.raster.Frame;
import com.xp3.common.ui.Ui;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Print Settings (screen 06): every option for this job on one screen.
 * Copies changes in place with Left and Right; the other rows open their
 * choices. Rows the printer can't use are left out. Back keeps the changes.
 */
final class PrintSettingsScreen extends Screen {

    private final Draft draft;
    private final Runnable print;
    private LinearLayout list;
    private Parts.SettingRow printer;
    private Parts.Row copiesRow;
    private TextView copies;
    private Parts.SettingRow pages;
    private Parts.SettingRow color;
    private Parts.SettingRow sides;
    private Parts.SettingRow paper;
    private TextView foot;

    PrintSettingsScreen(Draft draft, Runnable print) {
        this.draft = draft;
        this.print = print;
    }

    @Override
    View build() {
        Context c = app;
        LinearLayout body = Parts.column(c);
        list = Parts.column(c);

        printer = new Parts.SettingRow(c, "Printer", 33, true);
        printer.row.setOnClickListener(v -> app.open(new PrintersScreen(chosen -> {
            draft.use(chosen);
            refresh();
        })));
        list.addView(printer.row);

        copiesRow = new Parts.Row(c, 33);
        copiesRow.titled("Copies", null);
        LinearLayout stepper = Parts.row(c);
        stepper.addView(step(c, "◀"));
        copies = Look.text(c, "", 13, Look.BOLD, Look.INK);
        copies.setGravity(Gravity.CENTER);
        copies.setMinWidth(Ui.dp(c, 18));
        LinearLayout.LayoutParams mid = Parts.line(Parts.WRAP, Parts.WRAP);
        mid.leftMargin = mid.rightMargin = Ui.dp(c, 6);
        stepper.addView(copies, mid);
        stepper.addView(step(c, "▶"));
        copiesRow.trailing(stepper);
        list.addView(copiesRow);

        pages = new Parts.SettingRow(c, "Pages", 33, true);
        pages.row.setOnClickListener(v -> app.open(new PagesScreen(draft)));
        list.addView(pages.row);

        color = new Parts.SettingRow(c, "Color", 33, true);
        color.row.setOnClickListener(v -> chooseColor());
        list.addView(color.row);

        sides = new Parts.SettingRow(c, "Two-sided", 33, true);
        sides.row.setOnClickListener(v -> chooseSides());
        list.addView(sides.row);

        paper = new Parts.SettingRow(c, "Paper size", 33, true);
        paper.row.setOnClickListener(v -> choosePaper());
        list.addView(paper.row);

        foot = Parts.foot(c, "");
        list.addView(foot);
        list.addView(Parts.buttons(c, Parts.button(c, "Print", Parts.PRIMARY, () -> {
            app.close();
            print.run();
        })));

        body.addView(Parts.scroll(c, list), new LinearLayout.LayoutParams(Parts.FILL, 0, 1));
        refresh();
        return Parts.page(c, Parts.nav(c, "Print Settings", true, null), body);
    }

    private static TextView step(Context c, String arrow) {
        TextView view = Look.text(c, arrow, 8, Look.REGULAR, Look.BLUE);
        view.setGravity(Gravity.CENTER);
        GradientDrawable circle = Look.oval(Look.WHITE);
        circle.setStroke(Math.max(1, Ui.dp(c, 1)), 0xFFBFD0F6);
        view.setBackground(circle);
        view.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16)));
        return view;
    }

    @Override
    View first() {
        return copiesRow;
    }

    @Override
    void shown() {
        refresh();
    }

    private void refresh() {
        printer.value.setText(draft.printer == null ? "Choose" : draft.printer.shown());
        copies.setText(String.valueOf(draft.options.copies));
        pages.value.setText(draft.pagesShown());
        boolean canColor = Choices.color(draft.printer);
        color.row.setVisibility(canColor ? View.VISIBLE : View.GONE);
        color.value.setText(Choices.colorName(draft.options.color));
        boolean twoSided = Choices.sides(draft.printer).size() > 1;
        sides.row.setVisibility(twoSided ? View.VISIBLE : View.GONE);
        sides.value.setText(Choices.sidesName(draft.options.duplex));
        paper.value.setText(Media.shown(draft.options.media));
        int sheets = Choices.sheets(draft.options);
        foot.setText("Uses " + Parts.count(sheets, "sheet", "sheets") + " of " + Media.shown(draft.options.media) + " paper");
    }

    private void chooseColor() {
        app.choose("Color", MainActivity.Choice.plain(Arrays.asList("Color", "Black & white")), draft.options.color ? 0 : 1, null, which -> {
            draft.options.color = which == 0;
            refresh();
        });
    }

    private void chooseSides() {
        final List<Integer> offered = Choices.sides(draft.printer);
        List<MainActivity.Choice> choices = new ArrayList<>();
        for (int duplex : offered) {
            String sub = duplex == Frame.LONG_EDGE ? "Turn pages like a book" : duplex == Frame.SHORT_EDGE ? "Turn pages like a notepad" : "One side of each sheet";
            choices.add(new MainActivity.Choice(Choices.sidesName(duplex), sub));
        }
        app.choose("Two-sided", choices, Math.max(0, offered.indexOf(draft.options.duplex)), null, which -> {
            draft.options.duplex = offered.get(which);
            refresh();
        });
    }

    private void choosePaper() {
        final List<String> offered = Choices.media(draft.printer);
        boolean said = draft.printer != null && draft.printer.ipp != null && !draft.printer.ipp.media.isEmpty();
        app.choose("Paper size", MainActivity.Choice.papers(offered), Math.max(0, offered.indexOf(draft.options.media)),
            said ? "Only sizes this printer supports are listed." : "This printer doesn't say which sizes it takes.",
            which -> {
                draft.options.media = offered.get(which);
                refresh();
            });
    }

    @Override
    boolean key(KeyEvent event) {
        if (!copiesRow.isFocused()) return false;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (down(event)) {
            int by = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            draft.options.copies = Math.max(1, Math.min(99, draft.options.copies + by));
            refresh();
        }
        return true;
    }
}
