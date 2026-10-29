package com.print.xp3;

import android.content.Context;
import android.content.SharedPreferences;
import com.print.xp3.raster.Frame;
import com.xp3.common.Disk;
import com.xp3.common.Json;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the app keeps between runs: the printing defaults and app settings
 * (preferences), the printers it knows (printers.json) and what was printed
 * (history.json, the newest 50). Main thread only.
 */
final class Store {

    private static Store store;

    static Store get(Context context) {
        if (store == null) store = new Store(context.getApplicationContext());
        return store;
    }

    /** One print, for Recent and Print again. */
    static final class Print {
        final String path;
        final String title;
        final String printer;
        final String printerName;
        final long when;
        final int pages;
        final Options options;

        Print(String path, String title, String printer, String printerName, long when, int pages, Options options) {
            this.path = path;
            this.title = title;
            this.printer = printer;
            this.printerName = printerName;
            this.when = when;
            this.pages = pages;
            this.options = options;
        }

        boolean exists() {
            return new File(path).isFile();
        }
    }

    private static final int HISTORY = 50;

    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, Printer> printers = new LinkedHashMap<>();
    /** The printers kept between runs: the ones chosen or added by address. Others are known for this run only. */
    private final Set<String> kept = new LinkedHashSet<>();
    private final List<Print> history = new ArrayList<>();

    private Store(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("print", Context.MODE_PRIVATE);
        try {
            for (Object each : load("printers.json")) {
                Printer printer = Printer.load(Json.object(each));
                if (printer != null) {
                    printers.put(printer.key(), printer);
                    kept.add(printer.key());
                }
            }
        } catch (RuntimeException ignored) {
            // Unreadable: start again.
        }
        try {
            for (Object each : load("history.json")) {
                Map<String, Object> m = Json.object(each);
                history.add(new Print(text(m, "path"), text(m, "title"), text(m, "printer"),
                    text(m, "printerName"), number(m, "when"), (int) number(m, "pages"), options(Json.child(m, "options"))));
            }
        } catch (RuntimeException ignored) {
            // Unreadable: start again.
        }
    }

    private File file(String name) {
        return new File(context.getFilesDir(), name);
    }

    /** A saved JSON array; empty when there is no file. Throws when the file isn't one. */
    private List<Object> load(String name) {
        String saved = Disk.read(file(name), 1 << 20);
        return saved == null ? Collections.emptyList() : Json.array(Json.parse(saved));
    }

    private static String text(Map<String, Object> m, String key) {
        String value = Json.text(m, key);
        return value == null ? "" : value;
    }

    private static long number(Map<String, Object> m, String key) {
        Double value = Json.number(m, key);
        return value == null ? 0 : value.longValue();
    }

    // Printers.

    /** The known printers: kept ones first, then those found since the app started. */
    List<Printer> printers() {
        return new ArrayList<>(printers.values());
    }

    Printer printer(String key) {
        return key == null ? null : printers.get(key);
    }

    /** The printer jobs go to unless another is picked; null before one is chosen. */
    Printer current() {
        return printer(prefs.getString("current", null));
    }

    void setCurrent(Printer printer) {
        keep(printer);
        prefs.edit().putString("current", printer.key()).apply();
    }

    /** Remembers a printer between runs, so it is listed even when it isn't found. */
    Printer keep(Printer printer) {
        Printer known = remember(printer);
        kept.add(known.key());
        savePrinters();
        return known;
    }

    boolean kept(Printer printer) {
        return kept.contains(printer.key());
    }

    /** No longer remembers a printer (nor uses it, if it was the one in use). */
    void forget(Printer printer) {
        String key = printer.key();
        kept.remove(key);
        printers.remove(key);
        if (key.equals(prefs.getString("current", null))) prefs.edit().remove("current").apply();
        savePrinters();
    }

    /**
     * Keeps a printer, merging what was found into the one already known
     * there (the same object is kept, so screens holding it see the news).
     */
    Printer remember(Printer found) {
        Printer known = printers.get(found.key());
        if (known == null) {
            printers.put(found.key(), found);
            known = found;
        } else if (known != found) {
            synchronized (known) {
                synchronized (found) {
                    if (!found.name.isEmpty()) known.name = found.name;
                    if (!found.model.isEmpty()) known.model = found.model;
                    if (!found.location.isEmpty()) known.location = found.location;
                    if (found.ippPort > 0) {
                        known.ippPort = found.ippPort;
                        known.ippPath = found.ippPath;
                        known.ippTls = found.ippTls;
                    }
                    if (found.ipp != null) known.ipp = found.ipp;
                    if (found.rawPort > 0) known.rawPort = found.rawPort;
                    if (!found.lpdQueue.isEmpty()) known.lpdQueue = found.lpdQueue;
                    if (found.pjl != null) known.pjl = found.pjl;
                    for (String format : found.pdl) if (!known.pdl.contains(format)) known.pdl.add(format);
                }
            }
        }
        if (kept.contains(known.key())) savePrinters();
        return known;
    }

    private void savePrinters() {
        List<Object> out = new ArrayList<>();
        for (Printer printer : printers.values()) if (kept.contains(printer.key())) out.add(printer.save());
        write("printers.json", Json.write(out));
    }

    private void write(String name, String text) {
        try {
            Disk.write(file(name), text, false);
        } catch (IOException ignored) {
            // Kept in memory for this run, at least.
        }
    }

    // Settings.

    boolean color() {
        return prefs.getBoolean("color", true);
    }

    void setColor(boolean value) {
        prefs.edit().putBoolean("color", value).apply();
    }

    boolean twoSided() {
        return prefs.getBoolean("twoSided", false);
    }

    void setTwoSided(boolean value) {
        prefs.edit().putBoolean("twoSided", value).apply();
    }

    /** The paper size asked for by default: chosen in Settings, or by the phone's country. */
    String media() {
        String chosen = prefs.getString("media", "");
        return chosen.isEmpty() ? Media.forCountry(Locale.getDefault().getCountry()) : chosen;
    }

    void setMedia(String value) {
        prefs.edit().putString("media", value).apply();
    }

    boolean vibrate() {
        return prefs.getBoolean("vibrate", true);
    }

    void setVibrate(boolean value) {
        prefs.edit().putBoolean("vibrate", value).apply();
    }

    /** The location the file picker opened last. */
    String lastLocation() {
        return prefs.getString("location", "downloads");
    }

    void setLastLocation(String value) {
        prefs.edit().putString("location", value).apply();
    }

    int sort() {
        return prefs.getInt("sort", 0);
    }

    void setSort(int value) {
        prefs.edit().putInt("sort", value).apply();
    }

    /** A new job's options: the defaults, fitted to what `printer` can do (null: no printer yet). */
    Options defaults(Printer printer, int pages) {
        Options options = new Options();
        options.from = 1;
        options.to = Math.max(1, pages);
        options.color = color();
        options.duplex = twoSided() ? Frame.LONG_EDGE : Frame.ONE_SIDED;
        options.media = media();
        return Choices.fit(options, printer);
    }

    // History.

    List<Print> history() {
        return new ArrayList<>(history);
    }

    /** The newest print, for Print again; null when there is none. */
    Print last() {
        return history.isEmpty() ? null : history.get(0);
    }

    void printed(String path, String title, Printer printer, int pages, Options options) {
        drop(path);
        history.add(0, new Print(path, title, printer.key(), printer.shown(), System.currentTimeMillis(), pages, copy(options)));
        while (history.size() > HISTORY) history.remove(history.size() - 1);
        saveHistory();
    }

    /** Takes a file off Recent. */
    void unlist(String path) {
        drop(path);
        saveHistory();
    }

    private void drop(String path) {
        for (int i = history.size() - 1; i >= 0; i--) if (history.get(i).path.equals(path)) history.remove(i);
    }

    void clearHistory() {
        history.clear();
        saveHistory();
    }

    private void saveHistory() {
        List<Object> out = new ArrayList<>();
        for (Print each : history) {
            out.add(Json.map("path", each.path, "title", each.title, "printer", each.printer,
                "printerName", each.printerName, "when", (double) each.when, "pages", (double) each.pages,
                "options", save(each.options)));
        }
        write("history.json", Json.write(out));
    }

    static Options copy(Options from) {
        Options out = new Options();
        out.copies = from.copies;
        out.from = from.from;
        out.to = from.to;
        out.color = from.color;
        out.duplex = from.duplex;
        out.media = from.media;
        out.images = from.images;
        return out;
    }

    private static Map<String, Object> save(Options options) {
        return Json.map("copies", (double) options.copies, "from", (double) options.from, "to", (double) options.to,
            "color", options.color, "duplex", (double) options.duplex, "media", options.media);
    }

    private static Options options(Map<String, Object> m) {
        Options out = new Options();
        out.copies = (int) Math.max(1, number(m, "copies"));
        out.from = (int) Math.max(1, number(m, "from"));
        out.to = (int) Math.max(out.from, number(m, "to"));
        out.color = Json.flag(m, "color");
        out.duplex = (int) number(m, "duplex");
        String media = text(m, "media");
        out.media = media.isEmpty() ? Media.LETTER : media;
        return out;
    }
}
