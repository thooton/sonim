package com.print.xp3;

import com.print.xp3.ipp.Capabilities;
import com.print.xp3.ipp.Ipp;
import com.print.xp3.ipp.IppClient;
import com.print.xp3.raster.Back;
import com.print.xp3.raster.Format;
import com.print.xp3.raster.Frame;
import com.print.xp3.send.Lpd;
import com.print.xp3.send.Pjl;
import com.print.xp3.send.Socket9100;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One print job, start to end: choose how to send it, make the document
 * the printer needs (page images, unless it reads the PDF itself), send
 * it, and follow it at the printer until it is printed or fails. If the
 * printer refuses a format, the next way {@link Plan} found is tried.
 */
public final class Job {

    /** The name jobs go under at the printer. */
    public static final String USER = "xp3";

    /** The pages, drawn for the printer. */
    public interface Document {
        int pages();

        /**
         * Draws page `index` (from 0; -1 for a blank page) to fill
         * `frame`, turned as `back` says, as rows into `format`: all
         * `frame.height` of them.
         */
        void draw(int index, Frame frame, Back back, Format format) throws IOException;
    }

    public static final int PREPARING = 0;
    public static final int SENDING = 1;
    public static final int PRINTING = 2;
    public static final int DONE = 3;
    public static final int FAILED = 4;
    public static final int CANCELED = 5;

    public static final class Status {
        public final int phase;
        public final String text;
        /** 0 to 100, or -1 when there is no telling. */
        public final int percent;
        /** Whether `text` is a problem at the printer (out of paper, a jam) that holds the job up. */
        public final boolean problem;

        Status(int phase, String text, int percent, boolean problem) {
            this.phase = phase;
            this.text = text;
            this.percent = percent;
            this.problem = problem;
        }

        public boolean over() {
            return phase >= DONE;
        }
    }

    public interface Listener {
        void changed(Status status);
    }

    /** A printer's no to a format, which another format may get past. */
    static final class Refused extends IOException {
        Refused(String message) {
            super(message);
        }
    }

    private static final class Canceled extends IOException {
        Canceled() {
            super("Canceled");
        }
    }

    private final Printer printer;
    private final Options options;
    private final Document document;
    private final File pdf;
    private final File spoolDir;
    private final String title;
    private final Listener listener;
    private volatile boolean canceled;
    private volatile int ippJob;
    private volatile Route sending;
    /** Pages in the document going to the printer, for "Sending page 3 of 6". */
    private int pagesSent;
    private long lastReport;

    /** Polling a printer for the job's state: how often, and for how long at most. */
    long pollMillis = 2000;
    long followMillis = 20 * 60 * 1000;

    public Job(Printer printer, Options options, Document document, File pdf, File spoolDir, String title,
               Listener listener) {
        this.printer = printer.copy();
        this.options = options;
        this.document = document;
        this.pdf = pdf;
        this.spoolDir = spoolDir;
        this.title = title;
        this.listener = listener;
    }

    /** Stops the job, here and, once it has reached it, at the printer. */
    public void cancel() {
        canceled = true;
        final int id = ippJob;
        final Route route = sending;
        if (id <= 0 || route == null || route.transport != Route.Transport.IPP) return;
        Thread thread = new Thread(() -> {
            try {
                IppClient client = client().readTimeout(5000);
                Ipp.Message request = request(client, Ipp.CANCEL_JOB);
                request.group(Ipp.OPERATION).add("job-id", Ipp.INTEGER, id);
                client.send(request);
            } catch (IOException ignored) {
                // The printer will finish it, then.
            }
        }, "cancel");
        thread.setDaemon(true);
        thread.start();
    }

    public void run() {
        try {
            int count = document.pages();
            if (count <= 0) throw new IOException("The document has no pages");
            options.from = Math.max(1, Math.min(options.from, count));
            options.to = Math.max(options.from, Math.min(options.to, count));
            List<Route> routes = Plan.routes(printer, options, count);
            if (routes.isEmpty()) throw new IOException("No way found to print to this printer");
            IOException refused = null;
            for (Route route : routes) {
                try {
                    attempt(route);
                    return;
                } catch (Refused e) {
                    refused = e;
                }
            }
            throw refused;
        } catch (Canceled e) {
            report(CANCELED, "Canceled", -1);
        } catch (IOException e) {
            report(canceled ? CANCELED : FAILED, canceled ? "Canceled" : message(e), -1);
        } catch (RuntimeException e) {
            report(FAILED, "Something went wrong: " + e, -1);
        }
    }

    static String message(IOException e) {
        String message = e.getMessage();
        if (e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException) {
            return "The printer can't be reached";
        }
        if (e instanceof java.net.SocketTimeoutException) return "The printer stopped answering";
        if (e instanceof java.net.UnknownHostException) return "The printer can't be found";
        if (message == null || message.isEmpty()) return "The printer couldn't be reached";
        return message;
    }

    private void attempt(Route route) throws IOException {
        sending = route;
        pagesSent = route.raster() ? sequence(options, route).size() : options.to - options.from + 1;
        File file = route.raster() ? render(route) : pdf;
        try {
            for (int j = 0; j < route.jobs; j++) {
                switch (route.transport) {
                    case IPP:
                        ipp(route, file);
                        break;
                    case RAW:
                        raw(route, file);
                        break;
                    case LPD:
                        lpd(route, file);
                        break;
                }
            }
            if (route.transport != Route.Transport.IPP) report(DONE, "Sent to the printer", 100);
        } finally {
            if (route.raster()) file.delete();
        }
    }

    // Making the document.

    /** The pages in the order sent: the range, again for each copy made by repeating. */
    static List<Integer> sequence(Options options, Route route) {
        List<Integer> out = new ArrayList<Integer>();
        int pages = options.to - options.from + 1;
        for (int copy = 0; copy < route.repeat; copy++) {
            for (int p = options.from; p <= options.to; p++) out.add(p - 1);
            // Each copy starts on a fresh sheet.
            boolean more = copy < route.repeat - 1;
            if (more && route.duplex != Frame.ONE_SIDED && pages % 2 == 1) out.add(-1);
        }
        return out;
    }

    private File render(Route route) throws IOException {
        double[] size = Media.points(options.media);
        if (size == null) size = Media.points(Media.LETTER);
        List<Integer> pages = sequence(options, route);
        int copies = route.kind == Route.Kind.PCL || route.kind == Route.Kind.POSTSCRIPT ? route.printerCopies : 1;
        Frame frame = new Frame(size[0], size[1], route.dpi, route.color, options.media, route.duplex,
            pages.size(), copies);
        File file = File.createTempFile("job", ".print", spoolDir);
        boolean done = false;
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 256 * 1024);
        try {
            final Format format = route.format();
            format.start(out, frame);
            Format guarded = new Guard(format);
            for (int i = 0; i < pages.size(); i++) {
                check();
                report(PREPARING, "Preparing page " + (i + 1) + " of " + pages.size(), i * 100 / pages.size());
                Back back = frame.back(i) ? route.back : Back.NONE;
                format.startPage(i, back);
                document.draw(pages.get(i), frame, back, guarded);
                format.endPage();
            }
            format.finish();
            done = true;
        } finally {
            out.close();
            if (!done) file.delete();
        }
        return file;
    }

    /** Passes rows on, stopping the drawing when the job is canceled. */
    private final class Guard extends Format {
        private final Format inner;

        Guard(Format inner) {
            this.inner = inner;
        }

        @Override
        public void startPage(int index, Back back) {}

        @Override
        public void rows(byte[] data, int offset, int count) throws IOException {
            check();
            inner.rows(data, offset, count);
        }

        @Override
        public void endPage() {}
    }

    private void check() throws Canceled {
        if (canceled) throw new Canceled();
    }

    // Sending over IPP.

    private IppClient client() {
        return new IppClient(printer.address, printer.ippPort, printer.ippPath, printer.ippTls);
    }

    private Ipp.Message request(IppClient client, int operation) {
        Ipp.Message request = Ipp.request(operation, client.id(), client.uri());
        if (printer.ipp != null && !printer.ipp.ipp2) {
            request.major = 1;
            request.minor = 1;
        }
        request.group(Ipp.OPERATION).add("requesting-user-name", Ipp.NAME, USER);
        return request;
    }

    private Ipp.Message printJob(IppClient client, Route route, boolean full) {
        Ipp.Message request = request(client, Ipp.PRINT_JOB);
        request.group(Ipp.OPERATION)
            .add("job-name", Ipp.NAME, title.isEmpty() ? "Document" : title)
            .add("document-format", Ipp.MIME, route.mime);
        if (!full) return request;
        Capabilities caps = printer.ipp;
        Ipp.Group job = new Ipp.Group(Ipp.JOB);
        if (route.kind == Route.Kind.PDF && route.printerCopies > 1) job.add("copies", Ipp.INTEGER, route.printerCopies);
        if (caps.sides.size() > 1) {
            String sides = route.duplex == Frame.LONG_EDGE ? "two-sided-long-edge"
                : route.duplex == Frame.SHORT_EDGE ? "two-sided-short-edge" : "one-sided";
            if (caps.sides.contains(sides)) job.add("sides", Ipp.KEYWORD, sides);
        }
        boolean color = route.raster() ? route.color : options.color && caps.color;
        String mode = color ? "color" : "monochrome";
        if (caps.colorModes.contains(mode)) job.add("print-color-mode", Ipp.KEYWORD, mode);
        if (caps.media.contains(options.media)) job.add("media", Ipp.KEYWORD, options.media);
        if (route.pageRanges) job.add("page-ranges", Ipp.RANGE, new int[] {options.from, options.to});
        if (!job.attributes.isEmpty()) request.groups.add(job);
        return request;
    }

    private void ipp(Route route, File file) throws IOException {
        IppClient client = client();
        boolean full = true;
        boolean older = false;
        int busy = 0;
        Ipp.Message answer;
        while (true) {
            check();
            Ipp.Message request = printJob(client, route, full);
            if (older) {
                request.major = 1;
                request.minor = 1;
            }
            sent(0, 1);
            answer = client.send(request, file, (done, total) -> sent(done, total));
            if (answer.ok()) break;
            int code = answer.code;
            if (code == Ipp.VERSION_NOT_SUPPORTED && !older) {
                older = true;
            } else if ((code == Ipp.ATTRIBUTES_NOT_SUPPORTED || code == Ipp.BAD_REQUEST) && full) {
                full = false;
            } else if (code == Ipp.BUSY && busy < 12) {
                busy++;
                report(SENDING, "The printer is busy", -1, true);
                pause(5000);
            } else if (code == Ipp.FORMAT_NOT_SUPPORTED || code == 0x0409 || code == 0x0410) {
                throw new Refused("The printer doesn't take this kind of document");
            } else if (code == Ipp.NOT_AUTHENTICATED || code == Ipp.FORBIDDEN) {
                throw new IOException("The printer wants a password");
            } else {
                Ipp.Attribute said = answer.get("status-message");
                String detail = said == null ? "" : said.string();
                throw new IOException("The printer refused the job"
                    + (detail.isEmpty() ? String.format(" (0x%04x)", code) : ": " + detail));
            }
        }
        Ipp.Attribute id = answer.get("job-id");
        ippJob = id == null ? 0 : id.integer(0);
        follow(route, ippJob);
    }

    /** Asks after the job until it is printed, canceled or failed. */
    private void follow(Route route, int id) throws IOException {
        report(PRINTING, "Waiting for the printer", -1);
        if (id <= 0) {
            report(DONE, "Sent to the printer", 100);
            return;
        }
        long until = System.currentTimeMillis() + followMillis;
        int failures = 0;
        while (System.currentTimeMillis() < until) {
            if (canceled) throw new Canceled();
            pause(pollMillis);
            Ipp.Message answer;
            try {
                IppClient client = client().readTimeout(8000);
                Ipp.Message request = request(client, Ipp.GET_JOB_ATTRIBUTES);
                request.group(Ipp.OPERATION)
                    .add("job-id", Ipp.INTEGER, id)
                    .add("requested-attributes", Ipp.KEYWORD, "job-state", "job-state-reasons");
                answer = client.send(request);
            } catch (IOException e) {
                if (++failures >= 5) break;
                continue;
            }
            if (answer.code == Ipp.NOT_FOUND) {
                // Finished and already forgotten.
                report(DONE, "Printed", 100);
                return;
            }
            if (!answer.ok()) {
                if (++failures >= 5) break;
                continue;
            }
            failures = 0;
            Ipp.Attribute stateAttribute = answer.get("job-state");
            int state = stateAttribute == null ? 0 : stateAttribute.integer(0);
            Ipp.Attribute reasonsAttribute = answer.get("job-state-reasons");
            List<String> reasons = reasonsAttribute == null ? new ArrayList<String>() : reasonsAttribute.strings();
            switch (state) {
                case 9:
                    report(DONE, "Printed", 100);
                    return;
                case 7:
                    report(CANCELED, "Canceled at the printer", -1);
                    return;
                case 8: {
                    String why = Reasons.job(reasons);
                    if (why.startsWith("The printer doesn't take") || why.startsWith("The printer couldn't read")) {
                        throw new Refused(why);
                    }
                    throw new IOException(why.isEmpty() ? "The printer couldn't print it" : why);
                }
                default: {
                    String problem = printerProblem();
                    if (!problem.isEmpty()) report(PRINTING, problem, -1, true);
                    else report(PRINTING, state == 5 ? "Printing" : "Waiting for the printer", -1);
                }
            }
        }
        report(DONE, "Sent to the printer", 100);
    }

    private String printerProblem() {
        try {
            IppClient client = client().readTimeout(8000);
            Ipp.Message request = request(client, Ipp.GET_PRINTER_ATTRIBUTES);
            request.group(Ipp.OPERATION).add("requested-attributes", Ipp.KEYWORD, "printer-state", "printer-state-reasons");
            Ipp.Message answer = client.send(request);
            Ipp.Attribute reasons = answer.get("printer-state-reasons");
            return reasons == null ? "" : Reasons.printer(reasons.strings());
        } catch (IOException e) {
            return "";
        }
    }

    // Sending raw and over LPD.

    private String language(Route route) {
        return route.kind.pjl;
    }

    private void raw(Route route, File file) throws IOException {
        String before = route.pjl ? Pjl.begin(title, language(route), route.printerCopies, route.duplex) : "";
        String after = route.pjl ? Pjl.end(title) : "";
        sent(0, 1);
        Socket9100.send(printer.address, printer.rawPort, before, file, after, (done, total) -> {
            sent(done, total);
            return !canceled;
        });
    }

    private void lpd(Route route, File file) throws IOException {
        String before = route.pjl ? Pjl.begin(title, language(route), route.printerCopies, route.duplex) : "";
        String after = route.pjl ? Pjl.end(title) : "";
        sent(0, 1);
        Lpd.send(printer.address, printer.lpdQueue, title, Lpd.ascii(before), file, Lpd.ascii(after), (done, total) -> {
            sent(done, total);
            return !canceled;
        });
    }

    private void pause(long millis) throws Canceled {
        try {
            long until = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < until) {
                check();
                Thread.sleep(Math.min(200, Math.max(1, until - System.currentTimeMillis())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Canceled();
        }
    }

    /** "Sending page 3 of 6", from how much of the document has gone. */
    private void sent(long done, long total) {
        int percent = (int) (done * 100 / Math.max(1, total));
        int page = (int) Math.min(pagesSent, Math.max(1, (done * pagesSent + total - 1) / Math.max(1, total)));
        report(SENDING, "Sending page " + page + " of " + pagesSent, percent);
    }

    private void report(int phase, String text, int percent) {
        report(phase, text, percent, false);
    }

    private void report(int phase, String text, int percent, boolean problem) {
        // Progress within a phase is told at most five times a second.
        long now = System.currentTimeMillis();
        boolean step = phase == SENDING || phase == PREPARING;
        if (step && percent > 0 && percent < 100 && now - lastReport < 200) return;
        lastReport = now;
        listener.changed(new Status(phase, text, percent, problem));
    }
}
