package com.print.xp3;

import com.print.xp3.ipp.Capabilities;
import com.print.xp3.ipp.Ipp;
import com.print.xp3.ipp.IppClient;
import com.print.xp3.send.Pjl;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Asks printers what they are and what they take. */
public final class Probe {

    private Probe() {}

    /** Where IPP printers usually listen, most usual first. */
    static final String[] PATHS = {"/ipp/print", "/ipp", "/ipp/printer", "/printer", "/"};

    /** Get-Printer-Attributes: everything the printer says about itself. */
    public static Capabilities attributes(IppClient client, boolean ipp2) throws IOException {
        Ipp.Message request = Ipp.request(Ipp.GET_PRINTER_ATTRIBUTES, client.id(), client.uri());
        if (!ipp2) {
            request.major = 1;
            request.minor = 1;
        }
        request.group(Ipp.OPERATION)
            .add("requesting-user-name", Ipp.NAME, Job.USER)
            .add("requested-attributes", Ipp.KEYWORD, "all");
        Ipp.Message answer = client.send(request);
        if (answer.code == Ipp.VERSION_NOT_SUPPORTED && ipp2) return attributes(client, false);
        if (!answer.ok() || answer.group(Ipp.PRINTER) == null) {
            throw new IOException("The printer said " + String.format("0x%04x", answer.code));
        }
        Capabilities caps = Capabilities.from(answer);
        if (!ipp2) caps.ipp2 = false;
        return caps;
    }

    /** Fills in `printer.ipp`, trying TLS when plain IPP is refused. */
    public static void ipp(Printer printer) throws IOException {
        String host;
        int port;
        String path;
        boolean tls;
        synchronized (printer) {
            host = printer.address;
            port = printer.ippPort;
            path = printer.ippPath;
            tls = printer.ippTls;
        }
        IppClient client = new IppClient(host, port, path, tls).readTimeout(8000);
        Capabilities caps;
        try {
            caps = attributes(client, true);
        } catch (IOException e) {
            if (tls) throw e;
            client = new IppClient(host, port, path, true).readTimeout(8000);
            caps = attributes(client, true);
        }
        synchronized (printer) {
            printer.ipp = caps;
            printer.ippTls = client.tls();
            printer.problem = "";
        }
    }

    /**
     * The IPP printers at an address with port `port` open but nothing
     * announced: the usual paths in turn, then a print server's queues.
     */
    public static List<Printer> ippAt(String address, int port) {
        List<Printer> out = new ArrayList<Printer>();
        for (String path : PATHS) {
            try {
                IppClient client = new IppClient(address, port, path, false).readTimeout(5000);
                Capabilities caps = attributes(client, true);
                Printer printer = new Printer(address);
                printer.ippPort = port;
                printer.ippPath = path;
                printer.ippTls = client.tls();
                printer.ipp = caps;
                out.add(printer);
                return out;
            } catch (IOException e) {
                // Not there; try the next.
            }
        }
        // A print server (CUPS on a computer sharing its printers) lists its queues.
        try {
            IppClient client = new IppClient(address, port, "/", false).readTimeout(5000);
            Ipp.Message request = Ipp.request(Ipp.CUPS_GET_PRINTERS, client.id(), null);
            request.major = 1;
            request.minor = 1;
            request.group(Ipp.OPERATION).add("requested-attributes", Ipp.KEYWORD, "printer-uri-supported", "printer-name");
            Ipp.Message answer = client.send(request);
            if (!answer.ok()) return out;
            for (Ipp.Group group : answer.all(Ipp.PRINTER)) {
                Ipp.Attribute uris = group.get("printer-uri-supported");
                if (uris == null) continue;
                String path = null;
                for (String uri : uris.strings()) {
                    try {
                        path = new URI(uri).getRawPath();
                        if (path != null && !path.isEmpty()) break;
                    } catch (Exception ignored) {
                        // Not a URI to use.
                    }
                }
                if (path == null) continue;
                Printer printer = new Printer(address);
                printer.ippPort = port;
                printer.ippPath = path;
                try {
                    ipp(printer);
                    out.add(printer);
                } catch (IOException e) {
                    // A queue that won't answer is left out.
                }
            }
        } catch (IOException e) {
            // Not a print server.
        }
        return out;
    }

    /** Asks a raw port's PJL, once. */
    public static void pjl(Printer printer) {
        String host;
        int port;
        synchronized (printer) {
            if (printer.pjlAsked || printer.rawPort == 0) return;
            host = printer.address;
            port = printer.rawPort;
        }
        Pjl.Info info = Pjl.query(host, port, 2500);
        synchronized (printer) {
            printer.pjl = info;
            printer.pjlAsked = true;
        }
    }
}
