package com.print.xp3.ipp;

import com.xp3.common.Check;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class IppTests {

    private IppTests() {}

    public static void run(final Check check) {
        check.run("ipp codec", () -> {
            Ipp.Message request = Ipp.request(Ipp.PRINT_JOB, 7, "ipp://10.0.0.5/ipp/print");
            request.group(Ipp.OPERATION).add("requesting-user-name", Ipp.NAME, "xp3");
            List<Ipp.Attribute> size = new ArrayList<Ipp.Attribute>();
            size.add(new Ipp.Attribute("x-dimension", Ipp.INTEGER, 21590));
            size.add(new Ipp.Attribute("y-dimension", Ipp.INTEGER, 27940));
            List<Ipp.Attribute> media = new ArrayList<Ipp.Attribute>();
            media.add(new Ipp.Attribute("media-size", Ipp.BEGIN_COLLECTION, size));
            request.add(Ipp.JOB)
                .add("copies", Ipp.INTEGER, 2)
                .add("page-ranges", Ipp.RANGE, new int[] {2, 4})
                .add("sides", Ipp.KEYWORD, "one-sided", "two-sided-long-edge")
                .add("media-col", Ipp.BEGIN_COLLECTION, media)
                .add("print-scaling", Ipp.KEYWORD, "fit");
            byte[] raw = Ipp.encode(request);
            check.same("version", 2, (int) raw[0]);
            check.same("operation", Ipp.PRINT_JOB, ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF));
            check.same("ends with END", Ipp.END, (int) raw[raw.length - 1]);

            Ipp.Message back = Ipp.decode(raw);
            check.same("id", 7, back.id);
            check.same("charset first", "attributes-charset", back.group(Ipp.OPERATION).attributes.get(0).name);
            check.same("uri", "ipp://10.0.0.5/ipp/print", back.get("printer-uri").string());
            check.same("copies", 2, back.get("copies").integer(0));
            check.same("range", "[2, 4]", Arrays.toString((int[]) back.get("page-ranges").values.get(0)));
            check.same("two keywords", Arrays.asList("one-sided", "two-sided-long-edge"), back.get("sides").strings());
            check.same("after a collection", "fit", back.get("print-scaling").string());
            @SuppressWarnings("unchecked")
            List<Ipp.Attribute> col = (List<Ipp.Attribute>) back.get("media-col").values.get(0);
            check.same("member", "media-size", col.get(0).name);
            @SuppressWarnings("unchecked")
            List<Ipp.Attribute> inner = (List<Ipp.Attribute>) col.get(0).values.get(0);
            check.same("nested member", 27940, Ipp.find(inner, "y-dimension").integer(0));

            check.fails("cut short", "cut short", () -> Ipp.decode(Arrays.copyOf(raw, raw.length / 2)));
        });

        check.run("capabilities", () -> {
            Ipp.Message answer = new Ipp.Message(Ipp.OK, 1);
            answer.add(Ipp.OPERATION).add("attributes-charset", Ipp.CHARSET, "utf-8");
            answer.add(Ipp.PRINTER)
                .add("printer-name", Ipp.NAME, "office")
                .add("printer-info", Ipp.TEXT, "Office Printer")
                .add("printer-make-and-model", Ipp.TEXT, "HP LaserJet M404")
                .add("document-format-supported", Ipp.MIME, "application/pdf", "image/pwg-raster", "image/urf")
                .add("color-supported", Ipp.BOOLEAN, false)
                .add("sides-supported", Ipp.KEYWORD, "one-sided", "two-sided-long-edge", "two-sided-short-edge")
                .add("media-supported", Ipp.KEYWORD, "na_letter_8.5x11in", "iso_a4_210x297mm")
                .add("copies-supported", Ipp.RANGE, new int[] {1, 99})
                .add("page-ranges-supported", Ipp.BOOLEAN, true)
                .add("pwg-raster-document-resolution-supported", Ipp.RESOLUTION, new int[] {300, 300, 3}, new int[] {600, 600, 3})
                .add("pwg-raster-document-type-supported", Ipp.KEYWORD, "sgray_8")
                .add("pwg-raster-document-sheet-back", Ipp.KEYWORD, "rotated")
                .add("urf-supported", Ipp.KEYWORD, "V1.4", "W8", "RS300-600", "DM3")
                .add("ipp-versions-supported", Ipp.KEYWORD, "1.0", "1.1", "2.0")
                .add("printer-state", Ipp.ENUM, 3)
                .add("printer-state-reasons", Ipp.KEYWORD, "none")
                .add("printer-location", Ipp.TEXT, "Office")
                .add("marker-names", Ipp.NAME, "Black", "Cyan")
                .add("marker-colors", Ipp.NAME, "#000000", "#00FFFF")
                .add("marker-levels", Ipp.INTEGER, 12, -2)
                .add("marker-low-levels", Ipp.INTEGER, 10, 10);
            Capabilities caps = Capabilities.from(Ipp.decode(Ipp.encode(answer)));
            check.same("shown", "Office Printer", caps.shown());
            check.ok("takes pdf", caps.takes("application/pdf"));
            check.ok("grey only", !caps.color);
            check.ok("two-sided", caps.twoSided());
            check.same("copies", 99, caps.copiesMax);
            check.same("resolutions", Arrays.asList(300, 600), caps.pwgResolutions);
            check.same("urf resolutions", Arrays.asList(300, 600), caps.urfResolutions());
            check.same("urf back", "rotated", caps.urfSheetBack());
            check.same("sheet back", "rotated", caps.pwgSheetBack);
            check.ok("ipp 2", caps.ipp2);
            check.same("state", 3, caps.state);
            check.same("location", "Office", caps.location);
            check.same("markers", 2, caps.markers.size());
            check.ok("black low under 15", caps.markers.get(0).isLow());
            check.same("unknown level", -1, caps.markers.get(1).level);
            check.same("marker colour", "#00FFFF", caps.markers.get(1).color);
        });

        check.run("http answers", () -> {
            byte[] body = Ipp.encode(new Ipp.Message(Ipp.OK, 3));
            String head = "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(body.length) + "\r\n";
            byte[] all = concat(head.getBytes("US-ASCII"), body, "\r\n0\r\n\r\n".getBytes("US-ASCII"));
            check.same("chunked after 100", 3, Ipp.decode(IppClient.response(new ByteArrayInputStream(all))).id);
            check.fails("401", "password", () -> IppClient.response(new ByteArrayInputStream(
                "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"))));
            IppClient client = new IppClient("fe80::1", 8631, "ipp/print", true);
            check.same("uri", "ipps://[fe80::1]:8631/ipp/print", client.uri());
            check.same("default port", "ipp://10.0.0.2/ipp", new IppClient("10.0.0.2", 631, "/ipp", false).uri());
        });
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
