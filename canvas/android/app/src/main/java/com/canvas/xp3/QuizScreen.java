package com.canvas.xp3;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Quiz;
import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A quiz, one question per screen (E3).
 *
 * A bar of equal steps shows where the student is, never how long they
 * have. The softkeys move between questions and every answer is saved to
 * Canvas as it is chosen, so leaving and coming back loses nothing. A time
 * limit, if the teacher set one, is stated once before starting, as a fact.
 */
final class QuizScreen extends Screen {

    private final Assignment work;
    private Map<String, Object> info;
    private Quiz.Attempt attempt;
    private List<Quiz.Question> questions;
    private int index;
    private String problem;
    private boolean saving;
    private EditText typed;

    QuizScreen(MainActivity app, Assignment work) {
        super(app);
        this.work = work;
        load();
    }

    private void load() {
        app.background(() -> {
            info = app.backend.quiz(work);
            attempt = app.backend.openAttempt(work);
            if (attempt != null) questions = app.backend.questions(attempt);
            return Boolean.TRUE;
        }, (ok, failure) -> {
            problem = failure;
            refresh();
            if (ok != null && attempt == null) offerStart();
        });
    }

    /** The one confirmation: starting uses an attempt, and says what the limits are. */
    private void offerStart() {
        StringBuilder said = new StringBuilder();
        Double count = Json.number(info, "question_count");
        if (count != null) said.append(Assignment.number(count.doubleValue())).append(" questions. ");
        Double limit = Json.number(info, "time_limit");
        if (limit != null && limit.doubleValue() > 0) {
            said.append("Time limit: ").append(Assignment.number(limit.doubleValue())).append(" minutes, from when you start. ");
        }
        Double allowed = Json.number(info, "allowed_attempts");
        if (allowed != null && allowed.doubleValue() > 0) {
            said.append("This uses one of your ").append(Assignment.number(allowed.doubleValue())).append(" tries.");
        }
        if (Json.flag(info, "has_access_code")) {
            Sheet.ask(app, "This quiz has an access code", "Ask your teacher, then take it on a computer.", "OK",
                "Close", app::pop);
            return;
        }
        Sheet.ask(app, "Start the quiz?", said.toString().trim(), "Start", "Not now", () -> app.background(() -> {
            attempt = app.backend.startQuiz(work);
            questions = app.backend.questions(attempt);
            return Boolean.TRUE;
        }, (ok, failure) -> {
            problem = failure;
            refresh();
        }));
    }

    @Override
    String left() {
        return questions == null || index == 0 ? "Back" : "Previous";
    }

    @Override
    String center() {
        return "Select";
    }

    @Override
    String right() {
        if (questions == null) return null;
        return index == questions.size() - 1 ? "Turn in" : "Next";
    }

    @Override
    void onRight() {
        if (questions == null) return;
        saveTyped();
        if (index == questions.size() - 1) finish();
        else {
            index++;
            app.redraw();
        }
    }

    @Override
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SOFT_LEFT) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                saveTyped();
                if (questions == null || index == 0) app.pop();
                else {
                    index--;
                    app.redraw();
                }
            }
            return true;
        }
        if (typed != null && app.getCurrentFocus() == typed && com.xp3.common.ui.Ui.isOk(code)) {
            if (event.getAction() == KeyEvent.ACTION_UP) onRight();
            return true;
        }
        return false;
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        typed = null;
        String title = info == null ? work.title : String.valueOf(Json.text(info, "title"));
        root.addView(parts.nav(title, questions == null ? null : (index + 1) + " of " + questions.size()),
            Views.wide());
        if (questions == null) {
            if (problem == null) return parts.spinner();
            root.addView(parts.empty(0, "Couldn't open the quiz", problem), Views.tall());
            return root;
        }
        if (questions.isEmpty()) {
            root.addView(parts.empty(0, "No questions to answer here", "Turn it in with the right softkey."),
                Views.tall());
            return root;
        }
        LinearLayout steps = v.row();
        v.pad(steps, 12, 3, 12, 0);
        for (int i = 0; i < questions.size(); i++) {
            View step = new View(app);
            step.setBackground(v.round(i <= index ? look.ink : look.line, 2, 0, 0));
            steps.addView(step, v.margins(new LinearLayout.LayoutParams(0, v.dp(3), 1), 0, 0, i < questions.size() - 1 ? 3 : 0, 0));
        }
        root.addView(steps, Views.wide());
        LinearLayout page = parts.page(this, root);
        final Quiz.Question question = questions.get(index);
        MarkText text = v.pageTitle(question.text);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14 * look.scale);
        v.pad(text, 12, 8, 12, 0);
        page.addView(text, Views.wide());

        if (Quiz.ONE.equals(question.kind) || Quiz.MANY.equals(question.kind)) {
            for (int i = 0; i < question.options.size(); i++) {
                final Quiz.Option option = question.options.get(i);
                boolean chosen = question.chosen.contains(option.id);
                Item row = Sheet.option(app, v, "opt:" + option.id, option.text, chosen, () -> choose(question, option));
                page.addView(row, v.margins(Views.wide(), 12, i == 0 ? 8 : 5, 12, 0));
            }
        } else if (Quiz.TYPED.equals(question.kind) || Quiz.NUMBER.equals(question.kind)) {
            TextView label = v.label("Your answer");
            v.pad(label, 12, 8, 12, 3);
            page.addView(label, Views.wide());
            typed = Fields.box(app, v, question.typed == null ? "" : question.typed, null,
                Quiz.NUMBER.equals(question.kind)
                    ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            typed.setMinLines(Quiz.NUMBER.equals(question.kind) ? 1 : 3);
            page.addView(typed, v.margins(Views.wide(), 12, 0, 12, 0));
        } else {
            TextView note = v.bodyText("This kind of question can't be answered on the phone. Answer it on a "
                + "computer, or move on with Next.");
            note.setTextColor(look.muted);
            v.pad(note, 12, 8, 12, 0);
            page.addView(note, Views.wide());
        }
        TextView saved = v.text("Your answers save as you go.", 9, look.regular, look.muted);
        v.pad(saved, 12, 8, 12, 0);
        page.addView(saved, Views.wide());
        return root;
    }

    @Override
    String firstFocus() {
        if (questions == null || questions.isEmpty()) return null;
        Quiz.Question question = questions.get(index);
        return question.chosen.isEmpty() ? null : "opt:" + question.chosen.get(0);
    }

    @Override
    void shown() {
        if (typed != null) typed.requestFocus();
        app.refreshKeys();
    }

    private void choose(Quiz.Question question, Quiz.Option option) {
        Quiz.Question next = question.choose(option.id);
        questions.set(index, next);
        save(next);
        app.redraw();
    }

    private void saveTyped() {
        if (typed == null || questions == null) return;
        Quiz.Question question = questions.get(index);
        String value = typed.getText().toString();
        if (value.equals(question.typed == null ? "" : question.typed)) return;
        Quiz.Question next = question.type(value);
        questions.set(index, next);
        save(next);
    }

    private void save(final Quiz.Question question) {
        saving = true;
        app.background(() -> {
            app.backend.answer(attempt, question);
            return Boolean.TRUE;
        }, (ok, failure) -> {
            saving = false;
            if (ok == null) app.say("Not saved yet: " + failure);
        });
    }

    private void finish() {
        int blank = 0;
        for (Quiz.Question question : questions) {
            if (!question.answered() && !Quiz.OTHER.equals(question.kind)) blank++;
        }
        String said = blank == 0 ? "All " + questions.size() + " questions are answered."
            : blank + (blank == 1 ? " question is" : " questions are") + " still blank.";
        Sheet.ask(app, "Turn in the quiz?", said + " You can't change answers afterwards.", "Turn in", "Keep working",
            () -> app.background(() -> {
                app.backend.finishQuiz(work, attempt);
                return Boolean.TRUE;
            }, (ok, failure) -> {
                if (ok == null) {
                    app.tell("Couldn't turn in", failure);
                    return;
                }
                app.turnedIn.put(work.id, Long.valueOf(System.currentTimeMillis()));
                app.refresh(false);
                app.pop();
            }));
    }

    @Override
    boolean globalOptions() {
        return false;
    }
}
