package com.teslacharge.xp3;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.teslacharge.xp3.data.Normalizer;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class HistoryAdapter extends RowAdapter {
    private static final SimpleDateFormat WHEN = new SimpleDateFormat("MMM d, h:mm a", Locale.US);
    private static final int SESSION_ROW = 0, FOOTER_ROW = 1;

    private String footer;
    private boolean footerBusy;

    HistoryAdapter(Context context) {
        super(context);
    }

    /**
     * What sits under the last loaded session: null once the history has been
     * read to its end, otherwise the state of paging. Held here rather than as
     * a row in the list so that the sessions the caller handed over stay the
     * only things in it.
     */
    void setFooter(String text, boolean busy) {
        if (footerBusy == busy && (text == null ? footer == null : text.equals(footer))) return;
        boolean wasOnFooter = isFooter(getSelectedPosition());
        footer = text;
        footerBusy = busy;
        // A page that arrives empty retires the footer without adding rows, so
        // a selection sitting on it would be left pointing past the end.
        if (wasOnFooter && footer == null && super.getCount() > 0) {
            setSelectedPosition(super.getCount() - 1);
        }
        notifyDataSetChanged();
    }

    /** The one position that is the footer rather than a session. Callers ask
     *  before treating a position as something openable. */
    boolean isFooter(int position) {
        return footer != null && position == super.getCount();
    }

    @Override public int getCount() { return super.getCount() + (footer == null ? 0 : 1); }

    @Override public JSONObject getItem(int position) {
        return isFooter(position) ? null : super.getItem(position);
    }

    // The footer is a different layout, so it needs a recycling bin of its own:
    // handed a session row to convert, it would look for views that are not in
    // it.
    @Override public int getViewTypeCount() { return 2; }

    @Override public int getItemViewType(int position) {
        return isFooter(position) ? FOOTER_ROW : SESSION_ROW;
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        if (!isFooter(position)) return super.getView(position, convertView, parent);
        View row = convertView != null
            ? convertView
            : inflater().inflate(R.layout.row_history_footer, parent, false);
        row.setSelected(position == getSelectedPosition());
        ((TextView) row.findViewById(R.id.footer_text)).setText(footer);
        row.findViewById(R.id.footer_progress).setVisibility(footerBusy ? View.VISIBLE : View.GONE);
        return row;
    }

    @Override void bind(View row, JSONObject session) {
        // Lead with the street address, exactly as the site lists do, once one
        // has been resolved for this place. Tesla's history payload has no
        // address of its own, so until then the row leads with its label.
        String street = Normalizer.addressStreet(session);
        ((TextView) row.findViewById(R.id.site_name))
            .setText(street.length() > 0 ? street : session.optString("siteName", "Charging session"));

        // When it happened gets a line to itself, and what it came to gets the
        // next one: duration, effective price and energy are three numbers read
        // together, and reading them wants them on one line under the date.
        String when = when(session);
        if (!session.optBoolean("paid")) when += " · Unpaid";
        ((TextView) row.findViewById(R.id.site_address)).setText(when);
        row.findViewById(R.id.site_address).setVisibility(View.VISIBLE);
        ((TextView) row.findViewById(R.id.site_status)).setText(measures(session));

        // Same value column the site lists give the price: what a session cost
        // is the number history is scanned for. There is no unit to hang under
        // a total.
        SiteAdapter.setValue(row, SiteAdapter.money(session.optDouble("total", 0), session.optString("currency", "USD")), null);
    }

    /** "1h 23m · $0.28/kWh · 62.5 kWh", dropping whichever parts this session
     *  did not record. Measures ~146px of the ~152px this row's left column
     *  has at 11sp, so it is deliberately not given a fourth clause. */
    private static String measures(JSONObject session) {
        StringBuilder result = new StringBuilder();
        if (!session.isNull("durationSecs")) result.append(duration(session.optLong("durationSecs")));
        if (!session.isNull("pricePerKwh")) {
            if (result.length() > 0) result.append(" · ");
            result.append(SiteAdapter.money(session.optDouble("pricePerKwh"), session.optString("currency", "USD"))).append("/kWh");
        }
        double energy = session.optDouble("energyKwh", 0);
        if (energy > 0) {
            if (result.length() > 0) result.append(" · ");
            result.append(String.format(Locale.US, "%.1f kWh", energy));
        }
        return result.toString();
    }

    static String when(JSONObject session) {
        long at = session.optLong("startedAt", session.optLong("endedAt", 0));
        return at > 0 ? at(at) : "";
    }

    /** Formatted on the main thread only, which is what lets the one formatter
     *  be shared. */
    static String at(long millis) {
        return WHEN.format(new Date(millis));
    }

    /** "1h 23m", or "23m" below the hour. Minutes are the unit a charge is
     *  actually discussed in; seconds are noise at both ends. */
    static String duration(long seconds) {
        long hours = seconds / 3600, minutes = (seconds % 3600) / 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }
}
