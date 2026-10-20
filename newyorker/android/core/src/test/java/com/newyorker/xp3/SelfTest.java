package com.newyorker.xp3;

import com.xp3.common.Check;

/**
 * Everything in :core, checked on a desktop JVM.
 *
 * The parts worth being sure about are the parser's forgiveness and whether
 * pages shaped like the site's come out as the right stories and blocks. The
 * pages here are written for the tests, in the site's markup, not copied
 * from it.
 */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check();
        NewsTests.run(check);
        if (!check.report()) System.exit(1);
    }
}
