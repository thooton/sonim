package com.print.xp3;

import com.print.xp3.ipp.Capabilities;
import com.print.xp3.send.Pjl;
import com.xp3.common.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A printer on the network and every way found to reach it: IPP (over
 * TLS or not), its raw port, LPD. Filled in as discovery and probing learn
 * more; main thread and workers share it, so it is only changed under its
 * own lock and read whole through {@link #copy()} where that matters.
 */
public final class Printer {

    public final String address;
    public String name = "";
    public String model = "";
    /** Where it is, as the printer or its announcement says ("Office"), or "". */
    public String location = "";
    public int ippPort;
    public String ippPath = "";
    public boolean ippTls;
    public int rawPort;
    public String lpdQueue = "";
    /** Formats the raw port takes, lower case, from its announcement (`pdl`). */
    public final List<String> pdl = new ArrayList<String>();
    /** From Get-Printer-Attributes, once asked; null until then. */
    public Capabilities ipp;
    /** From PJL over the raw port, once asked; null until then or if it said nothing. */
    public Pjl.Info pjl;
    public boolean pjlAsked;
    /** Why the last probe failed, or "". */
    public String problem = "";

    public Printer(String address) {
        this.address = address;
    }

    /** One entry per printer: the address, and the queue too for a print server's queues. */
    public String key() {
        return key(address, ippPath);
    }

    public static String key(String address, String ippPath) {
        String path = ippPath == null ? "" : ippPath.toLowerCase(Locale.ROOT);
        if (path.startsWith("/")) path = path.substring(1);
        if (path.startsWith("printers/") || path.startsWith("classes/")) return address + "/" + path;
        return address;
    }

    public synchronized String shown() {
        if (!name.isEmpty()) return name;
        if (ipp != null && !ipp.shown().isEmpty()) return ipp.shown();
        if (!model.isEmpty()) return model;
        if (pjl != null && !pjl.model.isEmpty()) return pjl.model;
        return address;
    }

    /** The make and model, when the name doesn't already say it. */
    public synchronized String shownModel() {
        String made = !model.isEmpty() ? model : ipp != null ? ipp.makeAndModel : pjl != null ? pjl.model : "";
        return made.equals(shown()) ? "" : made;
    }

    /** Where it is: its own word for it, or its announcement's. */
    public synchronized String shownLocation() {
        if (ipp != null && !ipp.location.isEmpty()) return ipp.location;
        return location;
    }

    /** ".1.24": the end of its address, enough to tell two of a kind apart. */
    public String shortAddress() {
        String[] parts = address.split("\\.");
        if (parts.length != 4) return address;
        return "." + parts[2] + "." + parts[3];
    }

    /** What to keep of it between runs: how to reach it and what it is called. */
    public synchronized Map<String, Object> save() {
        return Json.map(
            "address", address, "name", name, "model", model, "location", location,
            "ippPort", (double) ippPort, "ippPath", ippPath, "ippTls", ippTls,
            "rawPort", (double) rawPort, "lpdQueue", lpdQueue,
            "pdl", new ArrayList<Object>(pdl));
    }

    /** A printer as {@link #save} kept it; null if it isn't one. */
    public static Printer load(Map<String, Object> saved) {
        String address = text(saved, "address");
        if (address.isEmpty()) return null;
        Printer out = new Printer(address);
        out.name = text(saved, "name");
        out.model = text(saved, "model");
        out.location = text(saved, "location");
        Double ippPort = Json.number(saved, "ippPort");
        out.ippPort = ippPort == null ? 0 : ippPort.intValue();
        out.ippPath = text(saved, "ippPath");
        out.ippTls = Json.flag(saved, "ippTls");
        Double rawPort = Json.number(saved, "rawPort");
        out.rawPort = rawPort == null ? 0 : rawPort.intValue();
        out.lpdQueue = text(saved, "lpdQueue");
        for (Object format : Json.children(saved, "pdl")) {
            if (format instanceof String) out.pdl.add((String) format);
        }
        return out;
    }

    private static String text(Map<String, Object> saved, String key) {
        String value = Json.text(saved, key);
        return value == null ? "" : value;
    }

    public synchronized boolean ipp() {
        return ippPort > 0;
    }

    public synchronized Printer copy() {
        Printer out = new Printer(address);
        out.name = name;
        out.model = model;
        out.location = location;
        out.ippPort = ippPort;
        out.ippPath = ippPath;
        out.ippTls = ippTls;
        out.rawPort = rawPort;
        out.lpdQueue = lpdQueue;
        out.pdl.addAll(pdl);
        out.ipp = ipp;
        out.pjl = pjl;
        out.pjlAsked = pjlAsked;
        out.problem = problem;
        return out;
    }
}
