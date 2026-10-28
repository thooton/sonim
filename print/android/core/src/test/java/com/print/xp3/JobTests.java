package com.print.xp3;

import com.print.xp3.ipp.Ipp;
import com.print.xp3.raster.Back;
import com.print.xp3.raster.Format;
import com.print.xp3.raster.Frame;
import com.xp3.common.Check;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Whole jobs, to a pretend IPP printer on loopback that takes only what it says it takes. */
final class JobTests {

    private JobTests() {}

    /** A printer that answers Get-Printer-Attributes, Print-Job and Get-Job-Attributes. */
    static final class FakePrinter implements AutoCloseable {
        final ServerSocket server;
        final List<String> formats;
        /** Formats it says it takes but refuses anyway, as some do with PDF. */
        final List<String> refuses = new ArrayList<String>();
        final List<Ipp.Message> jobs = new ArrayList<Ipp.Message>();
        final List<byte[]> documents = new ArrayList<byte[]>();
        int finalState = 9;
        String finalReason = "job-completed-successfully";
        /** The one path it answers at (404 elsewhere), or null for any. */
        String onlyPath;
        /** Turns down IPP 2.0 as a bad request, as some Brothers do. */
        boolean only11;
        private final Thread thread;

        FakePrinter(String... formats) throws IOException {
            this.formats = Arrays.asList(formats);
            server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        serve(socket);
                    } catch (IOException e) {
                        // Closed, or a client that went away.
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve(Socket socket) throws IOException {
            InputStream in = socket.getInputStream();
            long length = 0;
            String requestLine = line(in);
            String line;
            while (!(line = line(in)).isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Long.parseLong(line.substring(15).trim());
            }
            byte[] body = new byte[(int) length];
            int at = 0;
            while (at < body.length) {
                int read = in.read(body, at, body.length - at);
                if (read < 0) break;
                at += read;
            }
            String[] asked = requestLine.split(" ");
            if (onlyPath != null && (asked.length < 2 || !asked[1].equals(onlyPath))) {
                socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes("US-ASCII"));
                return;
            }
            Ipp.Message request = Ipp.decode(body);
            if (only11 && request.major == 2) {
                Ipp.Message refused = new Ipp.Message(Ipp.BAD_REQUEST, request.id);
                byte[] out = Ipp.encode(refused);
                OutputStream write = socket.getOutputStream();
                write.write(("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: " + out.length
                    + "\r\n\r\n").getBytes("US-ASCII"));
                write.write(out);
                return;
            }
            int head = Ipp.encode(request).length;
            Ipp.Message answer = new Ipp.Message(Ipp.OK, request.id);
            answer.add(Ipp.OPERATION).add("attributes-charset", Ipp.CHARSET, "utf-8")
                .add("attributes-natural-language", Ipp.LANGUAGE, "en");
            switch (request.code) {
                case Ipp.GET_PRINTER_ATTRIBUTES:
                    Ipp.Group group = answer.add(Ipp.PRINTER)
                        .add("printer-info", Ipp.TEXT, "Fake")
                        .add("document-format-supported", Ipp.MIME, formats.toArray())
                        .add("copies-supported", Ipp.RANGE, new int[] {1, 1})
                        .add("printer-state", Ipp.ENUM, 3);
                    if (formats.contains("image/pwg-raster")) {
                        group.add("pwg-raster-document-type-supported", Ipp.KEYWORD, "sgray_8")
                            .add("pwg-raster-document-resolution-supported", Ipp.RESOLUTION, new int[] {75, 75, 3});
                    }
                    break;
                case Ipp.PRINT_JOB: {
                    String format = request.get("document-format").string();
                    if (!formats.contains(format.toLowerCase(Locale.ROOT)) || refuses.contains(format)) {
                        answer.code = Ipp.FORMAT_NOT_SUPPORTED;
                        break;
                    }
                    synchronized (this) {
                        jobs.add(request);
                        documents.add(Arrays.copyOfRange(body, head, body.length));
                    }
                    answer.add(Ipp.JOB).add("job-id", Ipp.INTEGER, jobs.size()).add("job-state", Ipp.ENUM, 3);
                    break;
                }
                case Ipp.GET_JOB_ATTRIBUTES:
                    answer.add(Ipp.JOB).add("job-state", Ipp.ENUM, finalState)
                        .add("job-state-reasons", Ipp.KEYWORD, finalReason);
                    break;
                default:
                    answer.code = 0x0501;
            }
            byte[] out = Ipp.encode(answer);
            OutputStream write = socket.getOutputStream();
            write.write(("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: " + out.length
                + "\r\n\r\n").getBytes("US-ASCII"));
            write.write(out);
            write.flush();
        }

        private static String line(InputStream in) throws IOException {
            StringBuilder out = new StringBuilder();
            int b;
            while ((b = in.read()) >= 0 && b != '\n') if (b != '\r') out.append((char) b);
            return out.toString();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    /** Three pages; page n is drawn in grey level n * 60, in strips of 10 rows. */
    static final class Pages implements Job.Document {
        final List<Integer> drawn = new ArrayList<Integer>();

        @Override
        public int pages() {
            return 3;
        }

        @Override
        public void draw(int index, Frame frame, Back back, Format format) throws IOException {
            drawn.add(index);
            byte[] rows = new byte[frame.bytesPerLine() * 10];
            Arrays.fill(rows, (byte) (index < 0 ? 255 : index * 60));
            for (int y = 0; y < frame.height; y += 10) format.rows(rows, 0, Math.min(10, frame.height - y));
        }
    }

    static Printer printer(FakePrinter fake) throws IOException {
        Printer printer = new Printer("127.0.0.1");
        printer.ippPort = fake.port();
        printer.ippPath = "/ipp/print";
        Probe.ipp(printer);
        return printer;
    }

    static void run(final Check check) {
        check.run("job: failures read in the user's words", () -> {
            check.same("reset", "The printer stopped taking the job", Job.message(new java.net.SocketException("Connection reset")));
            check.same("pipe", "The printer stopped taking the job", Job.message(new java.net.SocketException("Broken pipe")));
            check.same("refused", "The printer can't be reached", Job.message(new java.net.ConnectException("Connection refused")));
            check.same("tls", "The printer's secure connection failed", Job.message(new java.io.IOException("TLS: handshake")));
            check.same("own words kept", "The printer wants a password", Job.message(new java.io.IOException("The printer wants a password")));
        });
        check.run("probe: a Brother at /ipp/port1 that takes only IPP 1.1", () -> {
            try (FakePrinter fake = new FakePrinter("image/urf", "application/octet-stream")) {
                fake.onlyPath = "/ipp/port1";
                fake.only11 = true;
                List<Printer> found = Probe.ippAt("127.0.0.1", fake.port());
                check.same("found", 1, found.size());
                check.same("path", "/ipp/port1", found.get(0).ippPath);
                check.same("ipp 1.1", false, found.get(0).ipp.ipp2);
            }
        });
        check.run("job: pdf refused, pwg taken", () -> {
            File pdf = File.createTempFile("doc", ".pdf");
            pdf.deleteOnExit();
            OutputStream write = new FileOutputStream(pdf);
            write.write("%PDF-1.4 pretend".getBytes("US-ASCII"));
            write.close();
            File spool = pdf.getParentFile();
            try (FakePrinter fake = new FakePrinter("application/pdf", "image/pwg-raster")) {
                fake.refuses.add("application/pdf");
                Printer printer = printer(fake);
                check.same("probed", "Fake", printer.shown());
                Options options = new Options();
                options.from = 2;
                options.to = 3;
                options.copies = 2;
                options.media = "na_letter_8.5x11in";
                Pages pages = new Pages();
                final List<Job.Status> statuses = new ArrayList<Job.Status>();
                Job job = new Job(printer, options, pages, pdf, spool, "Report", statuses::add);
                job.pollMillis = 10;
                job.run();
                Job.Status last = statuses.get(statuses.size() - 1);
                check.same("printed", "Printed", last.text);
                check.same("done", Job.DONE, last.phase);
                check.same("one job reached it", 1, fake.jobs.size());
                Ipp.Message sent = fake.jobs.get(0);
                check.same("as pwg", "image/pwg-raster", sent.get("document-format").string());
                check.same("named", "Report", sent.get("job-name").string());
                check.same("pages drawn: the range twice", Arrays.asList(1, 2, 1, 2), pages.drawn);

                byte[] doc = fake.documents.get(0);
                check.same("pwg", "RaS2", new String(doc, 0, 4, "US-ASCII"));
                ByteBuffer header = ByteBuffer.wrap(doc, 4, 1796).slice();
                check.same("75 dpi", 75, header.getInt(276));
                check.same("letter wide", 638, header.getInt(372));
                check.same("pages in header", 4, header.getInt(452));
            }
        });

        check.run("job: failure and cancel", () -> {
            File pdf = File.createTempFile("doc", ".pdf");
            pdf.deleteOnExit();
            try (FakePrinter fake = new FakePrinter("application/pdf")) {
                fake.finalState = 8;
                fake.finalReason = "media-jam";
                Printer printer = printer(fake);
                final List<Job.Status> statuses = new ArrayList<Job.Status>();
                Options options = new Options();
                options.to = 3;
                Job job = new Job(printer, options, new Pages(), pdf, pdf.getParentFile(), "", statuses::add);
                job.pollMillis = 10;
                job.run();
                Job.Status last = statuses.get(statuses.size() - 1);
                check.same("failed", Job.FAILED, last.phase);
                check.same("pdf sent as is", "application/pdf", fake.jobs.get(0).get("document-format").string());
                check.same("untitled", "Document", fake.jobs.get(0).get("job-name").string());

                fake.finalState = 5;
                statuses.clear();
                final Job slow = new Job(printer, options, new Pages(), pdf, pdf.getParentFile(), "x", statuses::add);
                slow.pollMillis = 20;
                Thread runner = new Thread(slow::run);
                runner.start();
                Thread.sleep(300);
                slow.cancel();
                runner.join(3000);
                check.same("canceled", Job.CANCELED, statuses.get(statuses.size() - 1).phase);
            }
        });

        check.run("job: nothing takes it", () -> {
            try (FakePrinter fake = new FakePrinter("image/jpeg")) {
                Printer printer = printer(fake);
                final List<Job.Status> statuses = new ArrayList<Job.Status>();
                Job job = new Job(printer, new Options(), new Pages(), null, null, "", statuses::add);
                job.run();
                Job.Status last = statuses.get(statuses.size() - 1);
                check.same("failed", Job.FAILED, last.phase);
                check.same("why", "No way found to print to this printer", last.text);
            }
        });

        check.run("job: printer gone", () -> {
            Printer printer = new Printer("127.0.0.1");
            printer.ippPort = 1;
            printer.ippPath = "/ipp/print";
            printer.ipp = PlanTests.caps("application/pdf");
            File pdf = File.createTempFile("doc", ".pdf");
            pdf.deleteOnExit();
            final List<Job.Status> statuses = new ArrayList<Job.Status>();
            new Job(printer, new Options(), new Pages(), pdf, null, "", statuses::add).run();
            check.same("unreachable", "The printer can't be reached", statuses.get(statuses.size() - 1).text);
        });
    }
}
