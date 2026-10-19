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

    /**
     * Everyone whose name or address has `typed` in it, in order: first those
     * where it starts a word of the name or the address, as Gmail suggests.
     */
    public List<Address> matching(String typed) {
        return matching(known, typed);
    }

    /** Those among `people` matching `typed`, ranked as {@link #matching(String)} ranks them. */
    public static List<Address> matching(List<Address> people, String typed) {
        String want = typed == null ? "" : typed.trim().toLowerCase(Locale.US);
        List<Address> starts = new ArrayList<Address>();
        List<Address> inside = new ArrayList<Address>();
        for (Address address : people) {
            int rank = rank(address, want);
            if (rank == 0) starts.add(address);
            else if (rank == 1) inside.add(address);
        }
        starts.addAll(inside);
        return starts;
    }

    /**
     * What to offer as `typed` is written: the known people matching it,
     * then those found elsewhere (the user's organisation) not already among
     * them, at most `max`.
     */
    public List<Address> suggest(String typed, List<Address> found, int max) {
        List<Address> out = new ArrayList<Address>();
        for (Address address : matching(typed)) {
            if (out.size() >= max) return out;
            out.add(address);
        }
        for (Address address : found) {
            if (out.size() >= max) break;
            if (Address.contains(out, address.email)) continue;
            out.add(address);
        }
        return out;
    }

    /** 0 when `want` starts a word of the name or the address, 1 when it is inside one, 2 when neither. */
    private static int rank(Address address, String want) {
        if (want.length() == 0) return 0;
        String name = address.name.toLowerCase(Locale.US);
        String email = address.email.toLowerCase(Locale.US);
        if (email.startsWith(want) || name.startsWith(want)) return 0;
        for (String word : name.split("[\\s,.'\"()-]+")) if (word.startsWith(want)) return 0;
        for (String word : email.split("[@._+-]+")) if (word.startsWith(want)) return 0;
        return email.contains(want) || name.contains(want) ? 1 : 2;
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
