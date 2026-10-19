package com.print.xp3;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Choose a PDF (screen 02): the places PDFs are kept, each with how many it
 * holds. Places with none are left out; Phone storage is always there, for
 * browsing folders. Focus starts on the place used last.
 */
final class LocationsScreen extends Screen {

    private static final ExecutorService WORKER = com.xp3.common.ui.Ui.worker("scan");
    /** What each place held when last looked, so going back and forth is instant. */
    static final Map<String, List<Library.Pdf>> found = new HashMap<String, List<Library.Pdf>>();

    private LinearLayout list;
    private View focus;
    private boolean scanned;

    @Override
    View build() {
        Context c = app;
        list = Parts.column(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setDefaultFocusHighlightEnabled(false);
        scroll.addView(list);
        list.addView(Parts.header(c, "Locations"));
        list.addView(Parts.foot(c, "Looking for PDFs…"));
        return Parts.page(c, Parts.nav(c, "Choose a PDF", true, null), scroll);
    }

    @Override
    View first() {
        return focus;
    }

    @Override
    void shown() {
        if (scanned) {
            rows();
            return;
        }
        final Context context = app.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            final Map<String, List<Library.Pdf>> all = new HashMap<String, List<Library.Pdf>>();
            for (Library.Location location : Library.locations(context)) {
                if (!location.browse) all.put(location.id, Library.scan(location));
            }
            main.post(() -> {
                found.clear();
                found.putAll(all);
                scanned = true;
                if (app != null && app.top() == this) {
                    rows();
                    if (focus != null) focus.requestFocus();
                }
            });
        });
    }

    private void rows() {
        Context c = app;
        View had = view.findFocus();
        String hadId = had == null ? null : (String) had.getTag();
        list.removeAllViews();
        list.addView(Parts.header(c, "Locations"));
        String last = Store.get(app).lastLocation();
        focus = null;
        List<Parts.Row> rows = new ArrayList<Parts.Row>();

        int recent = recentCount();
        if (recent > 0) {
            Parts.Row row = row(c, Library.RECENT, R.drawable.ic_clock, "Recent",
                recent + (recent == 1 ? " PDF" : " PDFs") + " printed recently");
            row.setOnClickListener(v -> {
                Store.get(app).setLastLocation(Library.RECENT);
                app.open(new RecentScreen());
            });
            rows.add(row);
        }
        for (final Library.Location location : Library.locations(c)) {
            Parts.Row row;
            if (location.browse) {
                row = row(c, location.id, location.icon, location.name, "Browse all folders");
                row.setOnClickListener(v -> {
                    Store.get(app).setLastLocation(location.id);
                    app.open(new FilesScreen(location.name, Library.phone()));
                });
            } else {
                List<Library.Pdf> pdfs = found.get(location.id);
                int count = pdfs == null ? 0 : pdfs.size();
                if (count == 0) continue;
                String what = count + (count == 1 ? " PDF" : " PDFs") + (location.id.equals(Library.BLUETOOTH) ? " received" : "");
                row = row(c, location.id, location.icon, location.name, what);
                row.setOnClickListener(v -> {
                    Store.get(app).setLastLocation(location.id);
                    app.open(new FilesScreen(location, found.get(location.id)));
                });
            }
            rows.add(row);
        }
        for (int i = 0; i < rows.size(); i++) {
            Parts.Row row = rows.get(i);
            if (i == rows.size() - 1) row.noDivider();
            list.addView(row, Parts.line(Parts.FILL, Parts.WRAP));
            String id = (String) row.getTag();
            if (id.equals(hadId) || (hadId == null && id.equals(last))) focus = row;
        }
        if (focus == null) {
            for (Parts.Row row : rows) if (row.getTag().equals(Library.DOWNLOADS)) focus = row;
        }
        if (focus == null && !rows.isEmpty()) focus = rows.get(0);
        if (focus != null) focus.requestFocus();
    }

    private int recentCount() {
        int count = 0;
        for (Store.Print print : Store.get(app).history()) if (print.exists()) count++;
        return count;
    }

    private static Parts.Row row(Context c, String id, int icon, String name, String sub) {
        Parts.Row row = new Parts.Row(c, 40);
        row.setTag(id);
        row.lead(Parts.square(c, icon), Look.dp(c, 26), Look.dp(c, 26));
        row.titled(name, sub);
        row.chevron();
        return row;
    }
}
