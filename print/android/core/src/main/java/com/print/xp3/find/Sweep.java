package com.print.xp3.find;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Knocks on the printer ports of every address on the local network, for
 * printers that don't announce themselves (or a network that drops the
 * announcements).
 */
public final class Sweep {

    private Sweep() {}

    public interface Listener {
        void open(String address, int port);
    }

    /** This phone's IPv4 address on the local network and its prefix length; null if none. */
    public static int[] local() {
        int[] best = null;
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface each : all) {
                if (!each.isUp() || each.isLoopback()) continue;
                boolean wifi = each.getName().startsWith("wlan") || each.getName().startsWith("eth");
                for (InterfaceAddress address : each.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (!(ip instanceof Inet4Address) || ip.isLoopbackAddress() || ip.isLinkLocalAddress()) continue;
                    byte[] raw = ip.getAddress();
                    int value = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16) | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
                    int[] found = {value, address.getNetworkPrefixLength()};
                    if (wifi) return found;
                    if (best == null && ip.isSiteLocalAddress()) best = found;
                }
            }
        } catch (SocketException | NullPointerException e) {
            // No interfaces to ask about.
        }
        return best;
    }

    public static String dotted(int value) {
        return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "." + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
    }

    /** The other addresses to try: the whole subnet, but no more than the /24 around this one. */
    public static List<String> neighbours(int self, int prefix) {
        int bits = Math.max(24, Math.min(30, prefix));
        int mask = bits == 0 ? 0 : -1 << (32 - bits);
        int network = self & mask;
        int size = 1 << (32 - bits);
        List<String> out = new ArrayList<String>();
        for (int i = 1; i < size - 1; i++) {
            int address = network + i;
            if (address != self) out.add(dotted(address));
        }
        return out;
    }

    /** Tries each port of each address, many at once; returns when all are tried. */
    public static void run(List<String> addresses, final int[] ports, final int millis, final Listener listener) {
        ExecutorService pool = Executors.newFixedThreadPool(48, runnable -> {
            Thread thread = new Thread(runnable, "sweep");
            thread.setDaemon(true);
            return thread;
        });
        for (final String address : addresses) {
            for (final int port : ports) {
                pool.execute(() -> {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (open(address, port, millis)) listener.open(address, port);
                });
            }
        }
        pool.shutdown();
        try {
            pool.awaitTermination(2, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static boolean open(String address, int port, int millis) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), millis);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
    }
}
