package com.teslacharge.xp3;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import com.teslacharge.xp3.data.Normalizer;

import org.json.JSONObject;

import java.util.Locale;

final class SiteAdapter extends RowAdapter {
    SiteAdapter(Context context) {
        super(context);
    }

    @Override void bind(View row, JSONObject site) {
        // Lead with the street address rather than Tesla's "Phoenix, AZ - West
        // Happy Valley Rd" label: a house number places a charger on your
        // mental map of the city in a way a site name does not.
        String street = Normalizer.addressStreet(site);
        ((TextView) row.findViewById(R.id.site_name))
            .setText(street.length() > 0 ? street : site.optString("name", "Charger"));

        TextView locality = row.findViewById(R.id.site_address);
        String city = Normalizer.addressLocality(site);
        locality.setText(city);
        locality.setVisibility(city.length() > 0 ? View.VISIBLE : View.GONE);

        // Distance moves in beside stall availability. Both describe getting to
        // a charger; the price column is what one charger is actually chosen
        // over another on, so it takes the space distance used to hold.
        String stalls;
        if (!site.isNull("availableStalls")) {
            stalls = site.optInt("availableStalls") + "/" + site.optInt("totalStalls") + " open";
        } else {
            stalls = site.optInt("totalStalls") > 0 ? site.optInt("totalStalls") + " stalls" : "Availability unknown";
        }
        String status = site.isNull("distanceMiles") ? stalls
            : String.format(Locale.US, "%.1f mi", site.optDouble("distanceMiles")) + " · " + stalls;
        SpannableString styledStatus = new SpannableString(status);
        if (!site.isNull("availableStalls") && site.optInt("totalStalls") > 0) {
            int available = site.optInt("availableStalls");
            int total = site.optInt("totalStalls");
            int availabilityColor = 0;
            if (available == 0) {
                availabilityColor = row.getResources().getColor(R.color.warning);
            } else if ((long) available * 4 <= total) {
                availabilityColor = row.getResources().getColor(R.color.capacity_low);
            }
            if (availabilityColor != 0) {
                styledStatus.setSpan(
                    new ForegroundColorSpan(availabilityColor),
                    status.length() - stalls.length(),
                    status.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
        }
        ((TextView) row.findViewById(R.id.site_status)).setText(styledStatus);

        // Detail responses include the authoritative time-of-use price for the
        // site's current local hour. Prefer it over the pricebook's first rate,
        // which may describe a different hour.
        JSONObject schedule = site.optJSONObject("schedule");
        JSONObject rate = site.optJSONObject("rate");
        boolean hasCurrentPrice = schedule != null && !schedule.isNull("currentPrice");
        JSONObject currentRate = hasCurrentPrice ? schedule : rate;
        String priceField = hasCurrentPrice ? "currentPrice" : "price";
        boolean priced = currentRate != null && !currentRate.isNull(priceField);
        setValue(row,
            priced ? money(currentRate.optDouble(priceField), currentRate.optString("currency", "USD")) : null,
            priced ? "/" + unit(currentRate.optString("uom", "kWh")) : null);
    }

    /** Fills the right-hand value column. A missing amount still holds the
     *  column with a dash: a blank right edge reads as a row that is a
     *  different shape rather than as a price nobody published. */
    static void setValue(View row, String amount, String unitLabel) {
        TextView value = row.findViewById(R.id.site_price);
        value.setText(amount == null ? "-" : amount);
        value.setTextColor(row.getResources().getColor(amount == null ? R.color.text_muted : R.color.text));

        TextView unitLine = row.findViewById(R.id.site_price_unit);
        unitLine.setText(unitLabel == null ? "" : unitLabel);
        unitLine.setVisibility(unitLabel == null ? View.GONE : View.VISIBLE);
    }

    static String money(double amount, String currency) {
        return ("USD".equalsIgnoreCase(currency) ? "$" : currency + " ") + String.format(Locale.US, "%.2f", amount);
    }

    /** Tesla sends "kwh"; upper-casing it gives "KWH", which is not how the
     *  unit is written. Known units get their conventional casing and anything
     *  unrecognised is passed through untouched. */
    static String unit(String uom) {
        if (uom == null || uom.length() == 0) return "kWh";
        if ("kwh".equalsIgnoreCase(uom)) return "kWh";
        if ("kw".equalsIgnoreCase(uom)) return "kW";
        if ("min".equalsIgnoreCase(uom) || "minute".equalsIgnoreCase(uom)) return "min";
        if ("hr".equalsIgnoreCase(uom) || "hour".equalsIgnoreCase(uom)) return "hr";
        return uom;
    }

    /** "$0.28/kWh": a price is meaningless without the unit it is per. */
    static String pricePerUnit(double amount, String currency, String uom) {
        return money(amount, currency) + "/" + unit(uom);
    }
}
