package com.print.xp3.send;

import com.xp3.common.Check;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

public final class SendTests {

    private SendTests() {}

    public static void run(final Check check) {
        check.run("pjl", () -> {
            String answer = "@PJL INFO ID\r\n\"HP LaserJet 4250\"\r\n\f@PJL INFO CONFIG\r\nIN TRAYS [3 ENUMERATED]\r\n"
                + "\tINTRAY1 MP\r\nLANGUAGES [3 ENUMERATED]\r\n\tPCL\r\n\tPOSTSCRIPT\r\n\tPDF\r\nUSTATUS [1 ENUMERATED]\r\n\tDEVICE\r\n\f";
            Pjl.Info info = Pjl.parse(answer);
            check.same("model", "HP LaserJet 4250", info.model);
            check.same("languages", Arrays.asList("PCL", "POSTSCRIPT", "PDF"), info.languages);
            check.ok("speaks PDF", info.speaks("PDF"));
            String begin = Pjl.begin("A \"quoted\" job", "PDF", 2, 1);
            check.ok("starts with UEL", begin.startsWith(Pjl.UEL + "@PJL\r\n"));
            check.ok("quotes dropped", begin.contains("NAME=\"A quoted job\""));
            check.ok("copies", begin.contains("COPIES=2"));
            check.ok("duplex", begin.contains("BINDING=LONGEDGE"));
            check.ok("language last", begin.endsWith("ENTER LANGUAGE=PDF\r\n"));
            check.ok("PCL's own copies", !Pjl.begin("x", "PCL", 2, 0).contains("COPIES"));
        });

        check.run("raw and lpd", () -> {
            File file = File.createTempFile("doc", ".bin");
            file.deleteOnExit();
            byte[] doc = new byte[200000];
            for (int i = 0; i < doc.length; i++) doc[i] = (byte) (i * 7);
            OutputStream write = new FileOutputStream(file);
            write.write(doc);
            write.close();

            final ServerSocket raw = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            final ByteArrayOutputStream got = new ByteArrayOutputStream();
            Thread server = new Thread(() -> {
                try (Socket socket = raw.accept()) {
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) got.write(buffer, 0, read);
                } catch (Exception ignored) {
                    // The check below sees what came.
                }
            });
            server.start();
            Socket9100.send("127.0.0.1", raw.getLocalPort(), "<", file, ">", null);
            server.join(5000);
            raw.close();
            byte[] all = got.toByteArray();
            check.same("raw length", doc.length + 2, all.length);
            check.ok("raw body", all[0] == '<' && all[all.length - 1] == '>'
                && Arrays.equals(doc, Arrays.copyOfRange(all, 1, all.length - 1)));
        });
    }
}
