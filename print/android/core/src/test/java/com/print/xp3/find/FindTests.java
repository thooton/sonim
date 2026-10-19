package com.print.xp3.find;

import com.print.xp3.Printer;
import com.xp3.common.Check;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FindTests {

    private FindTests() {}

    /** A DNS answer as a responder sends one: records only, names written out (with one compression pointer). */
    static final class Answer {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count;

        Answer() {
            short16(0);
            short16(0x8400);
            short16(0);
            short16(0); // answers, patched in bytes()
            short16(0);
            short16(0);
        }

        void short16(int value) {
            out.write(value >>> 8);
            out.write(value);
        }

        void name(String name) {
            for (String label : Dns.labels(name)) {
                byte[] raw = label.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(raw.length);
                out.write(raw, 0, raw.length);
            }
            out.write(0);
        }

        Answer record(String name, int type, byte[] data) {
            name(name);
            short16(type);
            short16(0x8001);
            short16(0);
            short16(120);
            short16(data.length);
            out.write(data, 0, data.length);
            count++;
            return this;
        }

        Answer ptr(String service, String instance) {
            Answer inner = new Answer();
            inner.out.reset();
            inner.name(instance);
            return record(service, Dns.PTR, inner.out.toByteArray());
        }

        Answer srv(String instance, String host, int port) {
            Answer inner = new Answer();
            inner.out.reset();
            inner.short16(0);
            inner.short16(0);
            inner.short16(port);
            inner.name(host);
            return record(instance, Dns.SRV, inner.out.toByteArray());
        }

        Answer txt(String instance, String... entries) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            for (String entry : entries) {
                byte[] raw = entry.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                data.write(raw.length);
                data.write(raw, 0, raw.length);
            }
            return record(instance, Dns.TXT, data.toByteArray());
        }

        Answer a(String host, int... address) {
            byte[] raw = new byte[4];
            for (int i = 0; i < 4; i++) raw[i] = (byte) address[i];
            return record(host, Dns.A, raw);
        }

        byte[] bytes() {
            byte[] raw = out.toByteArray();
            raw[6] = (byte) (count >>> 8);
            raw[7] = (byte) count;
            return raw;
        }
    }

    public static void run(final Check check) {
        check.run("dns", () -> {
            byte[] query = Dns.query(0, Arrays.asList("_ipp._tcp.local"), Arrays.asList(Dns.PTR));
            check.same("one question", 1, query[5] & 0xFF);
            check.same("dotted instance kept whole", Arrays.asList("Office v2.1", "_ipp", "_tcp", "local"),
                Dns.labels("Office v2.1._ipp._tcp.local"));

            byte[] raw = new Answer()
                .ptr("_ipp._tcp.local", "Office v2.1._ipp._tcp.local")
                .srv("Office v2.1._ipp._tcp.local", "office.local", 631)
                .txt("Office v2.1._ipp._tcp.local", "rp=ipp/print", "ty=HP LaserJet", "Color=F")
                .a("office.local", 192, 168, 1, 40)
                .bytes();
            // A compression pointer back to the first name, as responders write them.
            byte[] pointed = Arrays.copyOf(raw, raw.length + 2 + 10 + 4);
            int at = raw.length;
            pointed[at++] = (byte) 0xC0;
            pointed[at++] = 12;
            int[] rest = {0, 1, 0x80, 1, 0, 0, 0, 120, 0, 4, 10, 0, 0, 9};
            for (int v : rest) pointed[at++] = (byte) v;
            pointed[7] = 5;
            List<Dns.Record> records = Dns.parse(pointed, pointed.length);
            check.same("records", 5, records.size());
            check.same("ptr", "Office v2.1._ipp._tcp.local", records.get(0).target);
            check.same("srv port", 631, records.get(1).port);
            check.same("txt rp", "ipp/print", records.get(2).txt.get("rp"));
            check.same("txt keys lowered", "F", records.get(2).txt.get("color"));
            check.same("a", "192.168.1.40", records.get(3).target);
            check.same("pointer", "_ipp._tcp.local", records.get(4).name);
            check.same("cut short", null, Dns.parse(raw, raw.length - 3));

            Mdns mdns = new Mdns();
            List<String> types = Arrays.asList(Mdns.IPP);
            List<Mdns.Service> found = mdns.take(Dns.parse(raw, raw.length), "192.168.1.40", types);
            check.same("one service", 1, found.size());
            Mdns.Service service = found.get(0);
            check.same("instance", "Office v2.1", service.instance);
            check.same("address", "192.168.1.40", service.address);
            check.same("told once", 0, mdns.take(Dns.parse(raw, raw.length), "", types).size());
        });

        check.run("sweep", () -> {
            int self = (192 << 24) | (168 << 16) | (1 << 8) | 23;
            List<String> around = Sweep.neighbours(self, 24);
            check.same("a /24 less network, broadcast and self", 253, around.size());
            check.ok("not self", !around.contains("192.168.1.23"));
            check.same("first", "192.168.1.1", around.get(0));
            check.same("a /16 is cut to the /24", 253, Sweep.neighbours(self, 16).size());
        });

        check.run("merge", () -> {
            final List<Printer> told = new ArrayList<Printer>();
            Finder finder = new Finder(told::add);
            Printer raw = new Printer("10.0.0.9");
            raw.rawPort = 9100;
            Printer kept = finder.merge(raw);
            Printer ipp = new Printer("10.0.0.9");
            ipp.name = "Hall";
            ipp.ippPort = 631;
            ipp.ippPath = "/ipp/print";
            ipp.pdl.add("image/urf");
            check.ok("same printer", finder.merge(ipp) == kept);
            check.same("one", 1, finder.printers().size());
            check.same("named", "Hall", kept.shown());
            check.same("both ways", 9100, kept.rawPort);
            check.same("ipp", "/ipp/print", kept.ippPath);
            Printer queue = new Printer("10.0.0.9");
            queue.ippPort = 631;
            queue.ippPath = "/printers/Lab";
            finder.merge(queue);
            check.same("a server's queue is its own", 2, finder.printers().size());
            Printer again = Printer.load(com.xp3.common.Json.object(com.xp3.common.Json.parse(com.xp3.common.Json.write(kept.save()))));
            check.same("saved address", "10.0.0.9", again.address);
            check.same("saved ipp", 631, again.ippPort);
            check.same("saved raw", 9100, again.rawPort);
            check.same("saved formats", kept.pdl, again.pdl);
            check.same("saved name", "Hall", again.shown());
            check.same("short address", ".0.9", again.shortAddress());
            check.same("not a printer", null, Printer.load(new java.util.HashMap<String, Object>()));
            finder.stop();
        });
    }
}
