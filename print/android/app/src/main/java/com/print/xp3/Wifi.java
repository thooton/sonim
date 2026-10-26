package com.print.xp3;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/** The phone's Wi-Fi, for naming the network printers are looked for on. */
final class Wifi {

    private Wifi() {}

    private static boolean bound;

    /**
     * Sends all of this app's traffic over the Wi-Fi, for as long as there is
     * one. Android otherwise sends it over mobile data when the Wi-Fi has no
     * internet (a printer's own network, a router offline), and then no
     * printer on the Wi-Fi can be reached, found or typed in.
     */
    static synchronized void bind(Context context) {
        if (bound) return;
        final ConnectivityManager connectivity =
            (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) return;
        bound = true;
        NetworkRequest request = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        try {
            connectivity.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                private Network current;

                @Override
                public void onAvailable(Network network) {
                    current = network;
                    connectivity.bindProcessToNetwork(network);
                }

                @Override
                public void onLost(Network network) {
                    if (!network.equals(current)) return;
                    current = null;
                    connectivity.bindProcessToNetwork(null);
                }
            });
        } catch (RuntimeException e) {
            // Left to Android's own choice of network.
        }
    }

    static boolean on(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wifi != null && wifi.isWifiEnabled();
    }

    /** The network's name, or null when Android won't say (it needs the location permission to). */
    static String ssid(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return null;
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null) return null;
        String ssid = info.getSSID();
        if (ssid == null || ssid.isEmpty() || ssid.equals("<unknown ssid>") || ssid.equals("0x")) return null;
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) ssid = ssid.substring(1, ssid.length() - 1);
        return ssid;
    }

    /** "“Hollis-5G”", or "the same Wi-Fi as this phone" when the name isn't known. */
    static String name(Context context) {
        String ssid = ssid(context);
        return ssid == null ? "the same Wi-Fi as this phone" : "“" + ssid + "”";
    }
}
