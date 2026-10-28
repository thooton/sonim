package com.print.xp3;

import com.xp3.common.Check;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** T9 search: what matches, which result comes first, and names in a person's order. */
final class SearchTests {

    private SearchTests() {}

    static void run(final Check check) {
        check.run("t9: a match at the start of the name ranks first", () -> {
            String boarding = "Boarding pass for flight to San Francisco International Airport October";
            int[] inside = T9.match(boarding, "468");
            int[] start = T9.match("Invoice", "468");
            check.ok("both match", inside != null && start != null);
            check.same("name start", 0, T9.rank("Invoice", start));
            check.same("later word", 1, T9.rank(boarding, inside));
            check.same("inside a word", 2, T9.rank("Lint", T9.match("Lint", "468")));
        });
        check.run("names: digits as numbers", () -> {
            List<String> names = new ArrayList<String>(Arrays.asList("Scan_10", "scan_2", "Scan_100", "Scan_1", "Scan_02b", "Scan_9", "Invoice"));
            Collections.sort(names, T9::compare);
            check.same("order", Arrays.asList("Invoice", "Scan_1", "scan_2", "Scan_02b", "Scan_9", "Scan_10", "Scan_100"), names);
            check.ok("case aside", T9.compare("apple", "Banana") < 0);
            check.ok("shorter first", T9.compare("Scan", "Scan_1") < 0);
        });
    }
}
