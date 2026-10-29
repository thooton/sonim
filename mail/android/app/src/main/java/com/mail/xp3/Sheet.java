package com.mail.xp3;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * A screen's actions, each with its icon and, when it has one, the key that
 * does it without the menu: shown as a menu (Thunderbird's, with its
 * shortcuts beside each item), and looked up by key.
 */
final class Sheet {

    static final class Action {
        final int icon;
        final String label;
        /** '0' to '9', '*', '#', or 0 for none. */
        final char key;
        final Runnable run;

        Action(int icon, String label, char key, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.key = key;
            this.run = run;
        }
    }

    final List<Action> actions = new ArrayList<Action>();

    Sheet add(int icon, String label, char key, Runnable run) {
        actions.add(new Action(icon, label, key, run));
        return this;
    }

    Sheet add(int icon, String label, Runnable run) {
        return add(icon, label, (char) 0, run);
    }

    /** Runs the action for this key; false when there is none. */
    boolean press(char key) {
        for (Action action : actions) {
            if (action.key == key) {
                action.run.run();
                return true;
            }
        }
        return false;
    }

    void show(final Activity activity) {
        if (actions.isEmpty()) return;
        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return actions.size();
            }

            @Override
            public Object getItem(int position) {
                return actions.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                View row = convert != null ? convert : activity.getLayoutInflater().inflate(R.layout.row_action, parent, false);
                Action action = actions.get(position);
                ((ImageView) row.findViewById(R.id.icon)).setImageResource(action.icon);
                ((TextView) row.findViewById(R.id.label)).setText(action.label);
                TextView key = row.findViewById(R.id.key);
                key.setText(action.key == 0 ? "" : String.valueOf(action.key));
                key.setVisibility(action.key == 0 ? View.GONE : View.VISIBLE);
                return row;
            }
        };
        new AlertDialog.Builder(activity)
            .setAdapter(adapter, (dialog, which) -> actions.get(which).run.run())
            .show();
    }
}
