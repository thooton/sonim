package com.canvas.xp3;

import com.canvas.xp3.data.DataTests;
import com.canvas.xp3.net.NetTests;

/**
 * Everything in :core, checked on a desktop JVM.
 *
 * This module exists so that the parts of the app worth being sure about can be
 * run without a phone: what counts as missing, what a grade adds up to, what a
 * Canvas body looks like once it is Markdown, and whether the client refuses
 * the pagination URL that points somewhere else. The Android half above it is
 * views and key handling, which a test would not tell you much about anyway.
 */
public final class SelfTest {

    public static void main(String[] args) {
        Check check = new Check();
        NetTests.run(check);
        DataTests.run(check);
        if (!check.report()) System.exit(1);
    }
}
