package com.serve.xp3.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Where other devices can reach this one: its IPv4 addresses, Wi-Fi (and hotspot) first. */
public final class Addresses {

    private Addresses() {}

    public static List<String> local() {
        List<String> wifi = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all == null) return wifi;
            for (NetworkInterface each : Collections.list(all)) {
                if (!each.isUp() || each.isLoopback()) continue;
                String name = each.getName();
                boolean isWifi = name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan");
                for (InetAddress address : Collections.list(each.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    (isWifi ? wifi : other).add(address.getHostAddress());
                }
            }
        } catch (SocketException e) {
            // No interfaces to ask about.
        }
        wifi.addAll(other);
        return wifi;
    }
}
