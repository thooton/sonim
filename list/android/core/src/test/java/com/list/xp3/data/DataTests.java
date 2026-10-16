package com.list.xp3.data;

import com.xp3.common.Check;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public final class DataTests {

    private DataTests() {}

    public static void run(final Check check) {
        check.run("Item text", () -> {
            check.same("trimmed", "Buy milk", new Item("  Buy milk ", false).text);
            check.same("newlines fold", "Buy milk and eggs", new Item("Buy milk\nand\r\n eggs", false).text);
            check.fails("empty refused", () -> new Item("   ", false));
            check.fails("null refused", () -> new Item(null, false));
            StringBuilder longText = new StringBuilder();
            for (int i = 0; i <= Item.MAX_TEXT; i++) longText.append('x');
            final String tooLong = longText.toString();
            check.fails("oversized refused", "at most", () -> new Item(tooLong, false));
            Item item = new Item("Call mum", false);
            check.ok("toggle flips", item.toggled().done);
            check.same("toggle keeps text", "Call mum", item.toggled().text);
            check.same("edit keeps done", Boolean.TRUE,
                Boolean.valueOf(item.toggled().withText("Call dad").done));
        });

        check.run("Checklist edits", () -> {
            Checklist list = new Checklist();
            check.same("starts empty", Integer.valueOf(0), Integer.valueOf(list.size()));
            long v0 = list.version();
            list.insert(0, "B");
            list.insert(0, "A");
            list.insert(2, "C");
            check.same("order", Arrays.asList(new Item("A", false), new Item("B", false),
                new Item("C", false)), list.items());
            check.ok("insert bumps version", list.version() == v0 + 3);
            list.toggle(1);
            check.ok("toggled", list.get(1).done);
            list.edit(1, "Bee");
            check.same("edited", new Item("Bee", true), list.get(1));
            list.remove(0);
            check.same("removed", Arrays.asList(new Item("Bee", true), new Item("C", false)),
                list.items());
            check.ok("every edit bumps", list.version() == v0 + 6);
            final Checklist bounded = list;
            check.fails("insert past end", () -> { bounded.insert(5, "x"); });
            check.fails("blank edit", () -> bounded.edit(0, " "));
            check.ok("failed edits leave version", list.version() == v0 + 6);
        });

        check.run("Checklist moves", () -> {
            final Checklist list = new Checklist(Arrays.asList(
                new Item("A", false), new Item("B", true), new Item("C", false)));
            long v0 = list.version();
            list.move(2, 1);
            check.same("moved up", Arrays.asList(new Item("A", false), new Item("C", false),
                new Item("B", true)), list.items());
            list.move(0, 2);
            check.same("moved to end", Arrays.asList(new Item("C", false), new Item("B", true),
                new Item("A", false)), list.items());
            check.ok("move bumps version", list.version() == v0 + 2);
            check.fails("past the end", () -> list.move(2, 3));
            check.fails("before the start", () -> list.move(0, -1));
            check.same("failed moves leave the list", Arrays.asList(new Item("C", false),
                new Item("B", true), new Item("A", false)), list.items());
            check.ok("failed moves leave version", list.version() == v0 + 2);
        });

        check.run("Checklist compare and replace", () -> {
            Checklist list = new Checklist(Arrays.asList(new Item("A", false)));
            long read = list.version();
            list.toggle(0);
            long stale = list.replaceIf(read, Arrays.asList(new Item("Z", false)));
            check.same("stale write refused", Long.valueOf(-1), Long.valueOf(stale));
            check.same("list untouched", new Item("A", true), list.get(0));
            long fresh = list.replaceIf(list.version(), Arrays.asList(new Item("Z", false)));
            check.ok("fresh write accepted", fresh > 0);
            check.same("returns new version", Long.valueOf(list.version()), Long.valueOf(fresh));
            check.same("replaced", new Item("Z", false), list.get(0));
            List<Item> snapshot = list.items();
            list.insert(0, "Y");
            check.same("snapshots are copies", Integer.valueOf(1), Integer.valueOf(snapshot.size()));
        });

        check.run("Store round trips", () -> {
            File dir = Files.createTempDirectory("list-store").toFile();
            Store store = new Store(dir);
            check.same("no list yet", Integer.valueOf(0), Integer.valueOf(store.list().size()));

            List<Item> items = Arrays.asList(new Item("Milk", true), new Item("Eggs \"large\"", false));
            store.saveList(items);
            check.same("list round trip", items, store.list());

            write(new File(dir, "list.json"), "{\"items\":[{\"text\":\"ok\",\"done\":true},{\"text\":\"\"},7]");
            check.same("truncated list is absent", Integer.valueOf(0), Integer.valueOf(store.list().size()));
            write(new File(dir, "list.json"), "{\"items\":[{\"text\":\"ok\",\"done\":true},{\"text\":\"  \"},7,{\"done\":true}]}");
            check.same("bad entries skipped", Arrays.asList(new Item("ok", true)), store.list());

            write(new File(dir, "settings.json"), "{\"claude\":{}}");
            write(new File(dir, "chat.json"), "{\"turns\":[]}");
            store.forgetChat();
            check.ok("old login removed", !new File(dir, "settings.json").exists());
            check.ok("old chat removed", !new File(dir, "chat.json").exists());
            check.same("list kept", Arrays.asList(new Item("ok", true)), store.list());
        });
    }

    private static void write(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
