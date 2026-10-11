package com.delta.xp3;

import com.delta.xp3.data.DataTests;
import com.delta.xp3.net.NetTests;

/**
 * Everything in :core, checked on a desktop JVM.
 *
 * The parts worth being sure about are the protocol and what is made of it:
 * whether the requests have the shape the cloud accepts, whether a replayed or
 * echoed event can show up twice, and whether the session survives a round
 * trip to disk.
 */
public final class SelfTest {

    public static void main(String[] args) {
        Check check = new Check();
        DataTests.run(check);
        NetTests.run(check);
        if (!check.report()) System.exit(1);
    }
}
