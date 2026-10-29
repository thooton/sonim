package com.print.xp3.find;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the services announced with multicast DNS (Bonjour, as AirPrint and
 * Mopria printers announce themselves) for a while, telling a listener
 * about each as soon as its address and port are known.
 *
 * Queries go out from an ordinary port, which RFC 6762 calls a legacy
 * query: responders must answer it directly, so no multicast has to be
 * received. Answers multicast anyway (some responders do) are heard too,
 * on 5353, when that port can be shared.
 */
public final class Mdns {

    public static final String IPP = "_ipp._tcp.local";
    public static final String IPPS = "_ipps._tcp.local";
    public static final String RAW = "_pdl-datastream._tcp.local";
    public static final String LPD = "_printer._tcp.local";

    /** A service once its host's address is known. */
    public static final class Service {
        /** The instance's own name, "Office Printer" in "Office Printer._ipp._tcp.local". */
        public final String instance;
        public final String type;
        public final String address;
        public final int port;
        public final Map<String, String> txt;

        Service(String instance, String type, String address, int port, Map<String, String> txt) {
            this.instance = instance;
            this.type = type;
            this.address = address;
            this.port = port;
            this.txt = txt;
        }
    }

    public interface Listener {
        void found(Service service);
    }

    private static final InetSocketAddress GROUP = new InetSocketAddress("224.0.0.251", 5353);

    private final Map<String, Set<String>> instances = new HashMap<>();
    private final Map<String, Dns.Record> srv = new HashMap<>();
    private final Map<String, Map<String, String>> txt = new HashMap<>();
    private final Map<String, String> hosts = new HashMap<>();
    private final Map<String, String> senders = new HashMap<>();
    private final Set<String> told = new HashSet<>();
    private final Map<String, String> casing = new HashMap<>();

    /** Looks for `types` for `millis`, then returns. */
    public void browse(final List<String> types, long millis, final Listener listener) {
        final List<DatagramSocket> sockets = new ArrayList<>();
        try {
            DatagramSocket direct = new DatagramSocket();
            sockets.add(direct);
        } catch (IOException e) {
            return;
        }
        try {
            MulticastSocket shared = new MulticastSocket(null);
            shared.setReuseAddress(true);
            shared.bind(new InetSocketAddress(5353));
            shared.joinGroup(InetAddress.getByName("224.0.0.251"));
            sockets.add(shared);
        } catch (IOException | RuntimeException e) {
            // Something else holds 5353: direct answers still come.
        }
        List<Thread> readers = new ArrayList<>();
        for (final DatagramSocket socket : sockets) {
            Thread reader = new Thread(() -> read(socket, types, listener), "mdns");
            reader.setDaemon(true);
            reader.start();
            readers.add(reader);
        }
        long start = System.currentTimeMillis();
        long[] sends = {0, 1000, 3000, 7000};
        int next = 0;
        try {
            while (true) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= millis) break;
                if (next < sends.length && elapsed >= sends[next]) {
                    ask(sockets.get(0), types);
                    // And as an ordinary query from 5353, for responders that
                    // ignore the legacy kind and answer only by multicast.
                    if (sockets.size() > 1) ask(sockets.get(1), types);
                    next++;
                }
                Thread.sleep(250);
                if (next > 1) followUp(sockets.get(0));
                for (Service service : take(new ArrayList<>(), "", types)) listener.found(service);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            for (DatagramSocket socket : sockets) socket.close();
        }
    }

    private void ask(DatagramSocket socket, List<String> types) {
        List<Integer> kinds = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) kinds.add(Dns.PTR);
        send(socket, Dns.query(0, types, kinds));
    }

    /** What has been asked for again, and when. */
    private final Map<String, Long> chased = new HashMap<>();

    private boolean chase(String what) {
        if (chased.containsKey(what)) return false;
        chased.put(what, System.currentTimeMillis());
        return true;
    }

    /** Asks for what a service's announcement left out: its SRV and TXT, its host's address. */
    private void followUp(DatagramSocket socket) {
        List<String> names = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        synchronized (this) {
            for (Set<String> each : instances.values()) {
                for (String instance : each) {
                    Dns.Record record = srv.get(instance);
                    if (record == null) {
                        if (chase("srv " + instance)) {
                            names.add(casing.get(instance));
                            kinds.add(Dns.SRV);
                            names.add(casing.get(instance));
                            kinds.add(Dns.TXT);
                        }
                    } else if (!hosts.containsKey(record.target) && chase("a " + record.target)) {
                        names.add(record.target);
                        kinds.add(Dns.A);
                    }
                }
            }
        }
        if (!names.isEmpty()) send(socket, Dns.query(0, names, kinds));
    }

    private static void send(DatagramSocket socket, byte[] query) {
        try {
            socket.send(new DatagramPacket(query, query.length, GROUP));
        } catch (IOException e) {
            // No network to ask on; nothing will be found.
        }
    }

    private void read(DatagramSocket socket, List<String> types, Listener listener) {
        byte[] buffer = new byte[9000];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                return;
            }
            List<Dns.Record> records = Dns.parse(packet.getData(), packet.getLength());
            if (records == null) continue;
            String sender = packet.getAddress() == null ? "" : packet.getAddress().getHostAddress();
            List<Service> ready = take(records, sender, types);
            for (Service service : ready) listener.found(service);
        }
    }

    /** Files the records away, returning the services they complete. */
    synchronized List<Service> take(List<Dns.Record> records, String sender, List<String> types) {
        Set<String> wanted = new HashSet<>();
        for (String type : types) wanted.add(type.toLowerCase(Locale.ROOT));
        for (Dns.Record record : records) {
            String name = record.name.toLowerCase(Locale.ROOT);
            switch (record.type) {
                case Dns.PTR:
                    if (!wanted.contains(name) || record.ttl == 0) break;
                    String instance = record.target.toLowerCase(Locale.ROOT);
                    casing.put(instance, record.target);
                    Set<String> set = instances.get(name);
                    if (set == null) instances.put(name, set = new HashSet<>());
                    set.add(instance);
                    senders.put(instance, sender);
                    break;
                case Dns.SRV:
                    record.target = record.target.toLowerCase(Locale.ROOT);
                    srv.put(name, record);
                    if (!senders.containsKey(name)) senders.put(name, sender);
                    break;
                case Dns.TXT:
                    txt.put(name, record.txt);
                    break;
                case Dns.A:
                    if (!record.target.isEmpty() && !record.target.startsWith("169.254.")) hosts.put(name, record.target);
                    break;
                default:
                    break;
            }
        }
        List<Service> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : instances.entrySet()) {
            for (String instance : entry.getValue()) {
                if (told.contains(instance)) continue;
                Dns.Record where = srv.get(instance);
                if (where == null) continue;
                String address = hosts.get(where.target);
                if (address == null) {
                    // Asked for and still without its A record a second
                    // later, the host is taken to be whoever answered.
                    Long asked = chased.get("a " + where.target);
                    if (asked == null || System.currentTimeMillis() - asked < 1000) continue;
                    address = senders.get(instance);
                    if (address == null || address.isEmpty() || address.indexOf(':') >= 0) continue;
                }
                Map<String, String> text = txt.get(instance);
                told.add(instance);
                String shown = casing.get(instance);
                String suffix = "." + entry.getKey();
                String own = shown.toLowerCase(Locale.ROOT).endsWith(suffix)
                    ? shown.substring(0, shown.length() - suffix.length()) : shown;
                out.add(new Service(own, entry.getKey(), address, where.port,
                    text == null ? new LinkedHashMap<>() : text));
            }
        }
        return out;
    }
}
