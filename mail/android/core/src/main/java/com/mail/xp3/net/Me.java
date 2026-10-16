package com.mail.xp3.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The user: the account's address and every address Gmail lets them send as, the default first. */
public final class Me {

    public final String email;
    public final List<Address> addresses;

    public Me(String email, List<Address> sendAs) {
        this.email = email;
        List<Address> all = new ArrayList<Address>(sendAs);
        if (all.isEmpty()) all.add(new Address("", email));
        addresses = Collections.unmodifiableList(all);
    }

    public boolean is(String address) {
        if (address == null) return false;
        if (address.trim().equalsIgnoreCase(email)) return true;
        return Address.contains(addresses, address);
    }

    /**
     * Whom to send as: the address the original was sent to when that is one
     * of the user's others, else the default, as `delta gmail` picks.
     */
    public Address from(Message original) {
        Address pick = addresses.get(0);
        if (original == null) return pick;
        List<Address> to = new ArrayList<Address>(original.to());
        to.addAll(original.cc());
        to.addAll(Address.parse(original.header("Delivered-To")));
        for (Address address : to) {
            if (pick.is(address.email)) return pick;
        }
        for (Address address : to) {
            for (Address mine : addresses) if (mine.is(address.email)) return mine;
        }
        return pick;
    }
}
