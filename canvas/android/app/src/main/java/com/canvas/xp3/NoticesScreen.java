package com.canvas.xp3;

import android.view.View;
import android.widget.LinearLayout;

import com.canvas.xp3.data.Assignment;
import com.canvas.xp3.data.Course;
import com.canvas.xp3.data.Dates;
import com.canvas.xp3.data.Notice;

import java.util.List;

/**
 * Notifications (H1): short, factual lines grouped by day. Work returned
 * with its grade, new work posted, replies, messages. No line hurries.
 */
final class NoticesScreen extends Screen {

    private List<Notice> notices;
    private boolean loading;
    private String problem;

    NoticesScreen(MainActivity app) {
        super(app);
        notices = app.backend.cachedNotices();
        app.savePrefsQuietly(app.prefs.withNoticesSeen(System.currentTimeMillis()));
        if (app.backend.canvasReady()) {
            loading = true;
            final com.canvas.xp3.data.Sync.Result known = app.snapshot;
            app.background(() -> app.backend.notices(known), (fresh, failure) -> {
                loading = false;
                if (fresh != null) notices = fresh;
                problem = fresh == null ? failure : null;
                refresh();
            });
        }
    }

    @Override
    View build() {
        Parts parts = new Parts(app);
        LinearLayout root = v.column();
        android.widget.TextView heading = v.screenTitle("Notifications");
        v.pad(heading, 12, 4, 12, 6);
        root.addView(heading, Views.wide());
        if (notices.isEmpty() && loading) return parts.spinner();
        LinearLayout list = parts.page(this, root);
        if (notices.isEmpty() && !app.backend.canvasReady()) {
            list.addView(parts.empty(0, "Canvas isn't set up", "Sign in under Settings, then Account."),
                v.margins(Views.wide(), 0, 60, 0, 0));
            return root;
        }
        if (notices.isEmpty() && problem != null) {
            list.addView(parts.empty(0, "Couldn't reach Canvas", problem),
                v.margins(Views.wide(), 0, 60, 0, 0));
            return root;
        }
        if (notices.isEmpty()) {
            list.addView(parts.empty(0, "Nothing new", null),
                v.margins(Views.wide(), 0, 60, 0, 0));
            return root;
        }
        long now = System.currentTimeMillis();
        String day = null;
        for (int i = 0; i < notices.size(); i++) {
            final Notice notice = notices.get(i);
            int ago = Dates.daysBetween(notice.at, now);
            String label = ago == 0 ? "Today" : ago == 1 ? "Yesterday" : Dates.longDay(notice.at);
            if (!label.equals(day)) {
                list.addView(parts.section(label, null), Views.wide());
                day = label;
            }
            int icon = Notice.RETURNED.equals(notice.kind) ? R.drawable.ic_check
                : Notice.POSTED.equals(notice.kind) ? R.drawable.ic_assignment
                : Notice.COMMENT.equals(notice.kind) ? R.drawable.ic_comment
                : Notice.REMINDER.equals(notice.kind) ? R.drawable.ic_bell : R.drawable.ic_forum;
            String meta = notice.detail + " · " + Dates.time(notice.at);
            list.addView(parts.tiled("n:" + i, "Open", icon, look.paper, look.ink, notice.title, meta, null,
                () -> open(notice)), Views.wide());
        }
        return root;
    }

    private void open(Notice notice) {
        if (notice.courseId != null && notice.assignmentId != null) {
            Assignment work = app.assignment(notice.courseId, notice.assignmentId);
            if (work != null) {
                if (Notice.COMMENT.equals(notice.kind)) {
                    app.push(new CommentsScreen(app, work, app.backend.cachedDetail(work)));
                } else {
                    app.push(new AssignmentScreen(app, work, "Notifications"));
                }
                return;
            }
        }
        if (notice.courseId != null) {
            Course course = app.course(notice.courseId);
            if (course != null) {
                app.push(new ClassScreen(app, course));
                return;
            }
        }
        app.say("This one opens on a computer.");
    }
}
