package com.canvas.xp3.data;

import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A classic quiz attempt, one question per screen.
 *
 * Only the question kinds a keypad can answer well are answered here:
 * choosing one option (multiple choice, true or false), choosing several,
 * and typing a short answer, a number or an essay. Anything else (matching,
 * dropdowns, fill in several blanks, file upload) is shown with its text and
 * marked as needing a computer, and the student can move past it.
 *
 * New Quizzes have no API a student can use, so those are not here at all.
 */
public final class Quiz {

    public static final String ONE = "one";
    public static final String MANY = "many";
    public static final String TYPED = "typed";
    public static final String NUMBER = "number";
    public static final String OTHER = "other";

    /** One option of a choice question. */
    public static final class Option {
        public final String id;
        public final String text;

        public Option(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    public static final class Question {
        public final String id;
        /** ONE, MANY, TYPED, NUMBER or OTHER. */
        public final String kind;
        /** The question as Markdown. */
        public final String text;
        public final List<Option> options;
        /** The saved answer: option ids for a choice, the text for a typed one. */
        public final List<String> chosen;
        public final String typed;

        public Question(String id, String kind, String text, List<Option> options, List<String> chosen,
                        String typed) {
            this.id = id;
            this.kind = kind;
            this.text = text;
            this.options = options;
            this.chosen = chosen;
            this.typed = typed;
        }

        public boolean answered() {
            return !chosen.isEmpty() || (typed != null && typed.trim().length() > 0);
        }

        /** The answer as Canvas takes it for this kind of question. */
        public Object answer() {
            if (ONE.equals(kind)) return chosen.isEmpty() ? null : Long.valueOf(chosen.get(0));
            if (MANY.equals(kind)) {
                List<Object> ids = new ArrayList<>();
                for (String id : chosen) ids.add(Long.valueOf(id));
                return ids;
            }
            return typed == null ? "" : typed;
        }

        public Question choose(String option) {
            List<String> next = new ArrayList<>();
            if (MANY.equals(kind)) {
                next.addAll(chosen);
                if (!next.remove(option)) next.add(option);
            } else {
                next.add(option);
            }
            return new Question(id, kind, text, options, next, typed);
        }

        public Question type(String value) {
            return new Question(id, kind, text, options, chosen, value);
        }
    }

    /** The attempt in progress: what Canvas needs back with every answer. */
    public static final class Attempt {
        public final String id;
        public final long number;
        public final String token;

        public Attempt(String id, long number, String token) {
            this.id = id;
            this.number = number;
            this.token = token;
        }

        public static Attempt from(Map<String, Object> record) {
            Object id = record.get("id");
            Double number = Json.number(record, "attempt");
            String token = Json.text(record, "validation_token");
            if (!(id instanceof Number) || number == null || token == null) return null;
            return new Attempt(Long.toString(((Number) id).longValue()), (long) number.doubleValue(), token);
        }
    }

    private Quiz() {}

    /** The attempt still open, if the student left one part way through. */
    public static Attempt open(List<Object> attempts) {
        for (Object entry : attempts) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> record = Json.object(entry);
            if ("untaken".equals(Json.text(record, "workflow_state"))) return Attempt.from(record);
        }
        return null;
    }

    public static List<Question> questions(List<Object> records) {
        List<Question> out = new ArrayList<>();
        for (Object entry : records) {
            if (!(entry instanceof Map)) continue;
            Map<String, Object> record = Json.object(entry);
            Object id = record.get("id");
            if (!(id instanceof Number)) continue;
            String type = String.valueOf(Json.text(record, "question_type"));
            if ("text_only_question".equals(type)) continue;
            String kind;
            if ("multiple_choice_question".equals(type) || "true_false_question".equals(type)) kind = ONE;
            else if ("multiple_answers_question".equals(type)) kind = MANY;
            else if ("short_answer_question".equals(type) || "essay_question".equals(type)) kind = TYPED;
            else if ("numerical_question".equals(type)) kind = NUMBER;
            else kind = OTHER;
            List<Option> options = new ArrayList<>();
            for (Object raw : Json.children(record, "answers")) {
                if (!(raw instanceof Map)) continue;
                Map<String, Object> answer = Json.object(raw);
                Object optionId = answer.get("id");
                if (!(optionId instanceof Number)) continue;
                String text = Json.text(answer, "text");
                if (text == null || text.trim().length() == 0) {
                    text = Html.toMarkdown(Json.text(answer, "html")).trim();
                }
                options.add(new Option(Long.toString(((Number) optionId).longValue()), text));
            }
            List<String> chosen = new ArrayList<>();
            String typed = null;
            Object saved = record.get("answer");
            if (saved instanceof Number) {
                if (NUMBER.equals(kind)) typed = Assignment.number(((Number) saved).doubleValue());
                else chosen.add(Long.toString(((Number) saved).longValue()));
            } else if (saved instanceof List) {
                for (Object one : Json.array(saved)) {
                    if (one instanceof Number) chosen.add(Long.toString(((Number) one).longValue()));
                    else if (one instanceof String) chosen.add((String) one);
                }
            } else if (saved instanceof String) {
                if (ONE.equals(kind)) chosen.add((String) saved);
                else typed = Html.toMarkdown((String) saved).trim();
            }
            out.add(new Question(Long.toString(((Number) id).longValue()), kind,
                Html.toMarkdown(Json.text(record, "question_text")).trim(), options, chosen, typed));
        }
        return out;
    }
}
