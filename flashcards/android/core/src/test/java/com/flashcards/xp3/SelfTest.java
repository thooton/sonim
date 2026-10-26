package com.flashcards.xp3;

import com.xp3.common.Check;

/**
 * Everything in :core, checked on a desktop JVM.
 *
 * The core exists so the parts of the app worth being certain about — the
 * FSRS-6 memory math, the review queue and its 85% gate on new cards, and the state
 * round-trip — can be run without a phone. The Android half above it is views
 * and numpad handling, which a unit test would not say much about anyway.
 */
public final class SelfTest {

    public static void main(String[] args) {
        Check check = new Check();
        AlgoTests.run(check);
        DataTests.run(check);
        SyncTests.run(check);
        if (!check.report()) System.exit(1);
    }
}
