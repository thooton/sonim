package com.print.xp3.find;

import com.print.xp3.Printer;
import com.print.xp3.Probe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds the printers on the network: what announces itself over mDNS, and
 * what answers on a printer port in a sweep of the subnet, both at once.
 * Each printer is asked what it is (IPP's attributes, or PJL on a raw port)
 * as soon as it is found, and the listener told each time one is added or
 * learns more, from whichever thread found it out.
 */
public final class Finder {

    public interface Listener {
        void changed(Printer printer);
    }

    public static final int IPP_PORT = 631;
    public static final int RAW_PORT = 9100;
    public static final int LPD_PORT = 515;

    private final Listener listener;
    private final Map<String, Printer> printers = new LinkedHashMap<String, Printer>();
    private final ExecutorService probes = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "probe");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopped;

    public Finder(Listener listener) {
        this.listener = listener;
    }

    /** Every printer found so far, in the order found. */
    public synchronized List<Printer> printers() {
        return new ArrayList<Printer>(printers.values());
    }

    public void stop() {
        stopped = true;
        probes.shutdownNow();
    }

    /** Looks for `millis` (the sweep may finish sooner), then returns; probes may still be under way. */
    public void run(final long millis) {
        Thread mdns = new Thread(() -> new Mdns().browse(
            Arrays.asList(Mdns.IPP, Mdns.IPPS, Mdns.RAW, Mdns.LPD), millis, this::announced), "mdns-browse");
        mdns.setDaemon(true);
        mdns.start();
        int[] self = Sweep.local();
        if (self != null && !stopped) {
            Sweep.run(Sweep.neighbours(self[0], self[1]), new int[] {IPP_PORT, RAW_PORT, LPD_PORT}, 700,
                this::knocked);
        }
        try {
            mdns.join(millis + 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A printer at an address typed in: whatever answers on the printer ports there. */
    public List<Printer> at(String address) throws IOException {
        List<Printer> found = new ArrayList<Printer>();
        if (Sweep.open(address, IPP_PORT, 3000)) {
            for (Printer printer : Probe.ippAt(address, IPP_PORT)) found.add(merge(printer));
        }
        if (Sweep.open(address, RAW_PORT, 3000)) {
            Printer printer = merge(raw(address, RAW_PORT));
            Probe.pjl(printer);
            if (!found.contains(printer)) found.add(printer);
        } else if (found.isEmpty() && Sweep.open(address, LPD_PORT, 3000)) {
            found.add(merge(lpd(address, "lp")));
        }
        if (found.isEmpty()) throw new IOException("No printer answers at " + address);
        for (Printer printer : found) listener.changed(printer);
        return found;
    }

    // What mDNS found.

    void announced(Mdns.Service service) {
        if (stopped) return;
        Map<String, String> txt = service.txt;
        String model = txt.containsKey("ty") ? txt.get("ty") : txt.containsKey("product") ? strip(txt.get("product")) : "";
        Printer found = new Printer(service.address);
        found.name = service.instance;
        found.model = model;
        if (txt.containsKey("note")) found.location = txt.get("note");
        if (service.type.equals(Mdns.IPP) || service.type.equals(Mdns.IPPS)) {
            String rp = txt.containsKey("rp") ? txt.get("rp") : "ipp/print";
            found.ippPort = service.port;
            found.ippPath = "/" + rp;
            found.ippTls = service.type.equals(Mdns.IPPS);
        } else if (service.type.equals(Mdns.RAW)) {
            found.rawPort = service.port;
        } else {
            String rp = txt.get("rp");
            found.lpdQueue = rp == null || rp.isEmpty() ? "auto" : rp;
        }
        String pdl = txt.get("pdl");
        if (pdl != null) {
            for (String each : pdl.split(",")) {
                String format = each.trim().toLowerCase(Locale.ROOT);
                if (!format.isEmpty()) found.pdl.add(format);
            }
        }
        final Printer printer = merge(found);
        listener.changed(printer);
        probe(printer);
    }

    /** "(HP LaserJet 400)" as "HP LaserJet 400". */
    private static String strip(String product) {
        String out = product.trim();
        if (out.startsWith("(") && out.endsWith(")")) out = out.substring(1, out.length() - 1);
        return out;
    }

    // What the sweep found.

    void knocked(String address, int port) {
        if (stopped) return;
        if (port == IPP_PORT) {
            synchronized (this) {
                // Already known from its announcement, which said more.
                for (Printer known : printers.values()) if (known.address.equals(address) && known.ipp()) return;
            }
            for (Printer printer : Probe.ippAt(address, port)) listener.changed(merge(printer));
        } else if (port == RAW_PORT) {
            Printer printer = merge(raw(address, port));
            listener.changed(printer);
            probe(printer);
        } else if (port == LPD_PORT) {
            Printer printer = merge(lpd(address, "lp"));
            listener.changed(printer);
        }
    }

    private static Printer raw(String address, int port) {
        Printer printer = new Printer(address);
        printer.rawPort = port;
        return printer;
    }

    private static Printer lpd(String address, String queue) {
        Printer printer = new Printer(address);
        printer.lpdQueue = queue;
        return printer;
    }

    /**
     * Adds what was found to the printer already known at that address (and
     * queue), or adds it; returns the one kept. What was announced wins over
     * what was guessed.
     */
    Printer merge(Printer found) {
        synchronized (this) {
            String key = found.key();
            Printer known = printers.get(key);
            if (known == null && !found.ippPath.isEmpty() && !key.equals(found.address)) {
                // A queue on a print server: its raw port, if any, is the server's own.
                printers.put(key, found);
                return found;
            }
            if (known == null) known = printers.get(found.address);
            if (known == null) {
                printers.put(key, found);
                return found;
            }
            synchronized (known) {
                if (known.name.isEmpty()) known.name = found.name;
                if (known.model.isEmpty()) known.model = found.model;
                if (known.location.isEmpty()) known.location = found.location;
                if (found.ippPort > 0 && (known.ippPort == 0 || !found.name.isEmpty())) {
                    known.ippPort = found.ippPort;
                    known.ippPath = found.ippPath;
                    known.ippTls = found.ippTls;
                    if (found.ipp != null) known.ipp = found.ipp;
                }
                if (found.ipp != null && known.ipp == null) known.ipp = found.ipp;
                if (found.rawPort > 0) known.rawPort = found.rawPort;
                if (!found.lpdQueue.isEmpty() && (known.lpdQueue.isEmpty() || !found.lpdQueue.equals("lp"))) {
                    known.lpdQueue = found.lpdQueue;
                }
                for (String format : found.pdl) if (!known.pdl.contains(format)) known.pdl.add(format);
                if (found.pjl != null) known.pjl = found.pjl;
            }
            return known;
        }
    }

    /** Asks the printer what it is, off the finding threads. */
    private void probe(final Printer printer) {
        if (stopped) return;
        try {
            probes.execute(() -> {
                boolean ipp;
                synchronized (printer) {
                    ipp = printer.ippPort > 0 && printer.ipp == null;
                }
                if (ipp) {
                    try {
                        Probe.ipp(printer);
                    } catch (IOException e) {
                        synchronized (printer) {
                            printer.problem = e.getMessage() == null ? "Didn't answer" : e.getMessage();
                        }
                    }
                }
                Probe.pjl(printer);
                if (!stopped) listener.changed(printer);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Stopped meanwhile.
        }
    }

    /** Waits for the probes under way, for the tests. */
    void settle(long millis) throws InterruptedException {
        probes.shutdown();
        probes.awaitTermination(millis, TimeUnit.MILLISECONDS);
    }
}
