package com.print.xp3.send;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Sends a document to a printer's raw port (AppSocket, JetDirect: 9100),
 * which prints whatever it is sent, in a language it reads.
 */
public final class Socket9100 {

    private Socket9100() {}

    /** Told how much has gone; returns false to stop. */
    public interface Progress {
        boolean sent(long done, long total);
    }

    /** `before` and `after` (may be "") wrap the document: PJL, usually. */
    public static void send(String host, int port, String before, File document, String after,
                            Progress progress) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(60000);
            OutputStream out = socket.getOutputStream();
            out.write(Lpd.ascii(before));
            long total = document.length();
            long done = 0;
            byte[] buffer = new byte[64 * 1024];
            InputStream in = new FileInputStream(document);
            try {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (progress != null && !progress.sent(done, total)) throw new IOException("Canceled");
                }
            } finally {
                in.close();
            }
            out.write(Lpd.ascii(after));
            out.flush();
            // A clean close, rather than a reset that could cut off what
            // is still in flight.
            socket.shutdownOutput();
            try {
                InputStream rest = socket.getInputStream();
                socket.setSoTimeout(3000);
                while (rest.read(buffer) > 0) {
                    // The printer may chatter back (PJL status); it is not needed.
                }
            } catch (IOException ignored) {
                // Done either way.
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
    }
}
