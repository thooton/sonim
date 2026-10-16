package com.mail.xp3.net;

import com.xp3.common.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whom the user writes to and hears from, so an address can be picked
 * rather than typed on a keypad. Those written to come first, most recent
 * at the top; those only seen in mail follow.
 */
public final class People {

    static final int MAX = 300;

    private final List<Address> known = new ArrayList<Address>();

    public List<Address> all() {
        return new ArrayList<Address>(known);
    }

    /** Addresses seen in mail: added at the end, unless already known (a name fills in a bare address). */
    public boolean seen(List<Address> addresses, Me me) {
        boolean changed = false;
        for (Address address : addresses) {
            if (me != null && me.is(address.email) || noReply(address.email)) continue;
            int at = find(address);
            if (at < 0) {
                if (known.size() >= MAX) continue;
                known.add(address);
                changed = true;
            } else if (known.get(at).name.length() == 0 && address.name.length() > 0) {
                known.set(at, new Address(address.name, known.get(at).email));
                changed = true;
            }
        }
        return changed;
    }

    /** Addresses just written to: to the top. */
    public void wrote(List<Address> addresses) {
        for (int i = addresses.size() - 1; i >= 0; i--) {
            Address address = addresses.get(i);
            int at = find(address);
            if (at >= 0) {
                Address old = known.remove(at);
                if (address.name.length() == 0) address = old;
            }
            known.add(0, address);
        }
        while (known.size() > MAX) known.remove(known.size() - 1);
    }

    /** Everyone whose name or address has `typed` in it, in order. */
    public List<Address> matching(String typed) {
        String want = typed == null ? "" : typed.trim().toLowerCase(Locale.US);
        List<Address> out = new ArrayList<Address>();
        for (Address address : known) {
            if (want.length() == 0 || address.email.toLowerCase(Locale.US).contains(want)
                || address.name.toLowerCase(Locale.US).contains(want)) {
                out.add(address);
            }
        }
        return out;
    }

    private int find(Address address) {
        for (int i = 0; i < known.size(); i++) if (known.get(i).is(address.email)) return i;
        return -1;
    }

    private static boolean noReply(String email) {
        String local = email.substring(0, Math.max(0, email.indexOf('@'))).toLowerCase(Locale.US).replaceAll("[-_.]", "");
        return local.startsWith("noreply") || local.startsWith("donotreply") || local.equals("mailerdaemon");
    }

    public String toJson() {
        List<Object> list = new ArrayList<Object>();
        for (Address address : known) list.add(Json.map("name", address.name, "email", address.email));
        return Json.write(list);
    }

    public static People parse(String json) {
        People people = new People();
        if (json == null || json.length() == 0) return people;
        try {
            for (Object item : Json.array(Json.parse(json))) {
                Map<String, Object> entry = Json.object(item);
                people.known.add(new Address(Json.text(entry, "name"), Json.string(entry, "email")));
            }
        } catch (RuntimeException e) {
            people.known.clear();
        }
        return people;
    }
}
