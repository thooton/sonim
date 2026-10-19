package com.print.xp3.send;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * LPD (RFC 1179, port 515), the oldest way to send a job: a control file
 * naming the job, then the data file, each acknowledged with a zero byte.
 * Printers no longer insist on the reserved source ports the RFC asks for.
 */
public final class Lpd {

    private Lpd() {}

    public static byte[] ascii(String text) {
        byte[] out = new byte[text.length()];
        for (int i = 0; i < out.length; i++) {
            char c = text.charAt(i);
            out[i] = (byte) (c < 256 ? c : '?');
        }
        return out;
    }

    public static void send(String host, String queue, String title, byte[] before, File document,
                            byte[] after, Socket9100.Progress progress) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, 515), 5000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            int job = (int) (System.currentTimeMillis() % 1000);
            String number = String.format(java.util.Locale.ROOT, "%03d", job);
            String self = "phone";
            String dataName = "dfA" + number + self;
            String name = title.replaceAll("[^ -~]", "?");
            if (name.length() > 99) name = name.substring(0, 99);
            String control = "H" + self + "\nPprint\nJ" + name + "\nl" + dataName + "\nN" + name + "\nU" + dataName + "\n";

            out.write(ascii("\u0002" + queue + "\n"));
            acknowledged(in, "The printer refused the queue " + queue);
            byte[] controlBytes = ascii(control);
            out.write(ascii("\u0002" + controlBytes.length + " cfA" + number + self + "\n"));
            acknowledged(in, "The printer refused the job");
            out.write(controlBytes);
            out.write(0);
            acknowledged(in, "The printer refused the job");

            long total = before.length + document.length() + after.length;
            out.write(ascii("\u0003" + total + " " + dataName + "\n"));
            acknowledged(in, "The printer refused the document");
            out.write(before);
            long done = before.length;
            byte[] buffer = new byte[64 * 1024];
            InputStream file = new FileInputStream(document);
            try {
                int read;
                while ((read = file.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (progress != null && !progress.sent(done, total)) throw new IOException("Canceled");
                }
            } finally {
                file.close();
            }
            out.write(after);
            out.write(0);
            out.flush();
            acknowledged(in, "The printer refused the document");
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
    }

    private static void acknowledged(InputStream in, String otherwise) throws IOException {
        int answer = in.read();
        if (answer != 0) throw new IOException(otherwise);
    }
}
