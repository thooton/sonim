package com.print.xp3;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.print.xp3.ipp.Capabilities;
import com.xp3.common.ui.Ui;

/**
 * Printing (screens 09, 10 and 11) for one job: how sending is going, then
 * that the printer has it, or what went wrong and what to do about it.
 * Hide (or Back) leaves it printing; the job stays in Print Queue.
 */
final class PrintingScreen extends Screen implements Queue.Listener {

    private static final int PROGRESS = 0;
    private static final int DONE = 1;
    private static final int PROBLEM = 2;
    private static final int FAILED = 3;
    private static final int CANCELED = 4;

    private Queue.Entry entry;
    private FrameLayout frame;
    private int showing = -1;
    private String problemShown;
    /** A problem Retry was chosen for: not shown again until the printer says something else. */
    private String dismissed;
    private TextView headline;
    private Parts.Bar bar;
    private ObjectAnimator feed;

    PrintingScreen(Queue.Entry entry) {
        this.entry = entry;
    }

    @Override
    View build() {
        frame = new FrameLayout(app);
        frame.setBackgroundColor(Look.WHITE);
        return Parts.page(app, Parts.nav(app, "Printing", false, null), frame);
    }

    @Override
    void shown() {
        Queue.listen(this);
        update();
    }

    @Override
    void hidden() {
        Queue.unlisten(this);
    }

    @Override
    void closed() {
        if (feed != null) feed.cancel();
    }

    @Override
    public void changed(Queue.Entry changed) {
        if (changed == entry) update();
    }

    private int state() {
        Job.Status status = entry.status;
        switch (entry.state) {
            case Queue.FAILED:
                return FAILED;
            case Queue.CANCELED:
                return CANCELED;
            case Queue.DONE:
                return DONE;
            default:
                break;
        }
        if (status != null && status.problem && !status.text.equals(dismissed)) return PROBLEM;
        if (status != null && !status.problem) dismissed = null;
        return entry.accepted ? DONE : PROGRESS;
    }

    private void update() {
        int state = state();
        String text = entry.status == null ? "" : entry.status.text;
        if (state == showing && (state != PROBLEM || text.equals(problemShown))) {
            if (state == PROGRESS) progress();
            return;
        }
        showing = state;
        problemShown = state == PROBLEM ? text : null;
        if (feed != null) feed.cancel();
        frame.removeAllViews();
        View focus;
        switch (state) {
            case PROGRESS:
                focus = buildProgress();
                break;
            case DONE:
                focus = buildDone();
                break;
            case CANCELED:
                focus = buildCanceled();
                break;
            default:
                focus = buildProblem(state == FAILED);
                break;
        }
        if (focus != null) focus.requestFocus();
    }

    // Sending.

    private View buildProgress() {
        Context c = app;
        LinearLayout page = Parts.column(c);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(0, Ui.dp(c, 12), 0, 0);
        FrameLayout art = new FrameLayout(c);
        ImageView printer = new ImageView(c);
        printer.setImageResource(Readiness.picture(entry.printer));
        art.addView(printer, new FrameLayout.LayoutParams(Ui.dp(c, 140), Ui.dp(c, 98)));
        LinearLayout sheet = Parts.column(c);
        sheet.setBackground(Look.outlined(Look.WHITE, 0, 1, 0xFFDCDEE3, c));
        sheet.setPadding(Ui.dp(c, 6), Ui.dp(c, 5), Ui.dp(c, 6), 0);
        for (int i = 0; i < 3; i++) {
            View rule = new View(c);
            rule.setBackgroundColor(0xFFC7CBD4);
            LinearLayout.LayoutParams lp = Parts.line(i == 1 ? Ui.dp(c, 32) : Parts.FILL, Ui.dp(c, 2));
            lp.bottomMargin = Ui.dp(c, 3);
            sheet.addView(rule, lp);
        }
        FrameLayout.LayoutParams sheetAt = new FrameLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 34));
        sheetAt.leftMargin = Ui.dp(c, 44);
        sheetAt.topMargin = Ui.dp(c, 73);
        art.addView(sheet, sheetAt);
        page.addView(art, Parts.line(Ui.dp(c, 140), Ui.dp(c, 112)));
        // A page feeding out, over and over.
        feed = ObjectAnimator.ofFloat(sheet, "translationY", -Ui.dp(c, 10), 0);
        feed.setDuration(1800);
        feed.setRepeatCount(ValueAnimator.INFINITE);
        feed.start();

        headline = Look.text(c, "", 15, Look.BOLD, Look.INK);
        headline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = Parts.line(Parts.FILL, Parts.WRAP);
        lp.topMargin = Ui.dp(c, 6);
        page.addView(headline, lp);
        bar = new Parts.Bar(c);
        LinearLayout.LayoutParams barAt = Parts.line(Parts.FILL, Ui.dp(c, 6));
        barAt.setMargins(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), 0);
        page.addView(bar, barAt);
        TextView about = Look.text(c, entry.title + " · " + Parts.count(entry.options.copies, "copy", "copies")
            + "\nto " + entry.printer.shown(), 10.5f, Look.REGULAR, Look.MUTE);
        about.setGravity(Gravity.CENTER);
        about.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams aboutAt = Parts.line(Parts.FILL, Parts.WRAP);
        aboutAt.setMargins(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 16), 0);
        page.addView(about, aboutAt);
        TextView hide = Parts.button(c, "Hide", Parts.PRIMARY, app::home);
        TextView cancel = Parts.button(c, "Cancel print", Parts.DANGER, this::askCancel);
        LinearLayout.LayoutParams buttonsAt = Parts.line(Parts.FILL, Parts.WRAP);
        buttonsAt.topMargin = Ui.dp(c, 10);
        page.addView(Parts.pair(c, hide, cancel), buttonsAt);
        frame.addView(page);
        progress();
        return hide;
    }


    private void progress() {
        Job.Status status = entry.status;
        if (headline == null) return;
        if (entry.state == Queue.WAITING || status == null) {
            headline.setText("Waiting to start");
            bar.set(-1);
            return;
        }
        headline.setText(status.text.isEmpty() ? "Sending" : status.text);
        bar.set(status.phase == Job.SENDING || status.phase == Job.PREPARING ? Math.max(0, status.percent) : -1);
    }

    private void askCancel() {
        app.ask("Cancel this print job?", null, "Cancel print", Parts.DANGER, () -> Queue.cancel(entry), "Keep printing");
    }

    // The printer has it.

    private View buildDone() {
        Context c = app;
        int pages = entry.sheetsOfPaper();
        String where = entry.printer.shownLocation();
        String message = pages + (pages == 1 ? " page is" : " pages are") + " printing on\n" + entry.printer.shown()
            + (where.isEmpty() ? "." : " in the " + where + ".");
        if (entry.state == Queue.DONE && entry.status != null && entry.status.text.equals("Printed")) {
            message = Parts.count(pages, "page", "pages") + " printed on\n" + entry.printer.shown() + ".";
        }
        return over(Parts.badge(c, R.drawable.ic_check, 0xFFE3F5EB, Look.OK), "Sent to the printer", message);
    }

    private View buildCanceled() {
        String text = entry.status == null ? "" : entry.status.text;
        return over(Parts.badge(app, R.drawable.ic_cross, Look.GREY, Look.MUTE),
            text.equals("Canceled at the printer") ? "Canceled at the printer" : "Print canceled",
            entry.title + " won't print.");
    }

    /** The job is over here: a status page with Done (focused, and returned) and Print another PDF. */
    private View over(View badge, String headline, String message) {
        Context c = app;
        TextView done = Parts.button(c, "Done", Parts.PRIMARY, app::home);
        TextView another = Parts.button(c, "Print another PDF", Parts.SECONDARY, () -> {
            app.home();
            app.allowed(() -> app.open(new LocationsScreen()));
        });
        frame.addView(Parts.status(c, badge, 56, headline, message, null, Parts.buttons(c, done, another), 34));
        return done;
    }

    // Something wrong.

    private View buildProblem(boolean failed) {
        Context c = app;
        String said = entry.status == null ? "The printer couldn't print it" : entry.status.text;
        Problem problem = Problem.of(c, said, entry, failed);
        TextView first;
        TextView second;
        switch (problem.action) {
            case Problem.BLACK_AND_WHITE:
                first = Parts.button(c, "Print in black & white", Parts.PRIMARY, () -> {
                    Queue.cancel(entry);
                    resend(entry.printer, false);
                });
                second = Parts.button(c, "Cancel print", Parts.DANGER, () -> {
                    Queue.cancel(entry);
                    app.home();
                });
                break;
            case Problem.ANOTHER:
                first = Parts.button(c, "Use another printer", Parts.PRIMARY, this::another);
                second = Parts.button(c, failed ? "Done" : "Cancel print", failed ? Parts.SECONDARY : Parts.DANGER, () -> {
                    if (!failed) Queue.cancel(entry);
                    app.home();
                });
                break;
            default:
                first = Parts.button(c, "Retry", Parts.PRIMARY, () -> retry(failed));
                second = Parts.button(c, "Use another printer", Parts.SECONDARY, this::another);
                break;
        }
        View pill = problem.pill == null ? null : Parts.pill(c, problem.pill, 0xFFFFF1D6, 0xFF8A5200);
        boolean error = failed && problem.action != Problem.RETRY;
        frame.addView(Parts.status(c, Parts.badge(c, R.drawable.ic_alert, error ? 0xFFFBE4E4 : 0xFFFFF1D6, error ? Look.ERR : Look.WARN), 56,
            problem.headline, problem.body, pill, Parts.buttons(c, first, second), 22));
        return first;
    }

    private void retry(boolean failed) {
        if (failed) {
            resend(entry.printer, entry.options.color);
            return;
        }
        // The printer carries on by itself once it's seen to; look again.
        dismissed = entry.status == null ? null : entry.status.text;
        update();
    }

    /** Stops this job and sends it again, after a printer is picked. */
    private void another() {
        app.open(new PrintersScreen(chosen -> {
            if (entry.active()) Queue.cancel(entry);
            resend(chosen, entry.options.color);
        }));
    }

    private void resend(Printer printer, boolean color) {
        Queue.unlisten(this);
        entry = Queue.again(app, entry, printer, color);
        showing = -1;
        dismissed = null;
        Queue.listen(this);
        update();
    }

    /** What went wrong, in the proposal's words (section 7), and what to offer. */
    static final class Problem {
        static final int RETRY = 0;
        static final int ANOTHER = 1;
        static final int BLACK_AND_WHITE = 2;

        final String headline;
        final String body;
        final String pill;
        final int action;

        Problem(String headline, String body, String pill, int action) {
            this.headline = headline;
            this.body = body;
            this.pill = pill;
            this.action = action;
        }

        static Problem of(Context context, String said, Queue.Entry entry, boolean failed) {
            String printer = entry.printer.shown();
            String paper = Media.shown(entry.options.media);
            String pill = failed ? null : "Paused at the printer";
            switch (said) {
                case "Out of paper":
                    return new Problem("Printer is out of paper",
                        "Load " + paper + " paper in the main tray, then choose Retry.", pill, RETRY);
                case "Paper jam":
                    return new Problem("Paper is jammed",
                        "Open the printer and remove the jammed sheet, then choose Retry.", pill, RETRY);
                case "Out of ink":
                case "Out of toner": {
                    String what = empty(entry);
                    return new Problem(what + " is empty",
                        "Replace the cartridge, or print in black & white instead.", pill,
                        entry.options.color ? BLACK_AND_WHITE : RETRY);
                }
                case "A cover is open":
                    return new Problem("A cover is open", "Close the printer's covers, then choose Retry.", pill, RETRY);
                case "A paper tray is out":
                    return new Problem("A paper tray is out", "Put the paper tray back in, then choose Retry.", pill, RETRY);
                case "The output tray is full":
                    return new Problem("The output tray is full", "Take the printed pages out, then choose Retry.", pill, RETRY);
                case "The waste ink tank is full":
                    return new Problem("The waste ink tank is full",
                        "The printer needs servicing before it can print. Choose another printer for now.", pill, ANOTHER);
                case "Paused at the printer":
                    return new Problem("Printer is paused", "Resume printing on the printer, then choose Retry.", pill, RETRY);
                case "The printer is offline":
                case "The printer can't be reached":
                case "The printer can't be found":
                case "The printer couldn't be reached":
                case "The printer stopped answering":
                    return new Problem("Can't reach the printer",
                        "Check that " + printer + " is on and connected to " + Wifi.name(context) + ".", null, RETRY);
                case "The printer is busy":
                    return new Problem("The printer is busy", "It will take the job when it's free. Retry to look again.", pill, RETRY);
                case "The printer wants a password":
                    return new Problem("The printer wants a password",
                        "This phone can't sign in to printers. Choose another printer.", null, ANOTHER);
                case "No way found to print to this printer":
                case "The printer doesn't take this kind of document":
                    return new Problem("This printer can't take the job",
                        "It doesn't read any format this phone can send. Choose another printer.", null, ANOTHER);
                case "This PDF is protected with a password":
                case "This PDF can't be opened":
                    return new Problem("Can't open this file", said + ".", null, ANOTHER);
                default:
                    break;
            }
            if (failed) return new Problem("Printing stopped", said.endsWith(".") ? said : said + ".", null, RETRY);
            return new Problem(said, "See to it at the printer, then choose Retry.", pill, RETRY);
        }

        /** "Black ink", "Cyan toner": the empty supply, from what the printer last said; "Ink" when it didn't say. */
        private static String empty(Queue.Entry entry) {
            Capabilities caps = entry.printer.ipp;
            if (caps != null) {
                for (Capabilities.Marker marker : caps.markers) {
                    if (marker.level == 0) return Readiness.supply(marker);
                }
            }
            return "Ink";
        }
    }
}
