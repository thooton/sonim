package com.list.xp3;

import com.list.xp3.data.DataTests;
import com.xp3.common.Check;

/**
 * Everything in :core, checked on a desktop JVM.
 *
 * The parts worth being sure about are the list's edits and whether the list
 * survives a round trip to disk.
 */
public final class SelfTest {

    public static void main(String[] args) {
        Check check = new Check();
        DataTests.run(check);
        if (!check.report()) System.exit(1);
    }
}
