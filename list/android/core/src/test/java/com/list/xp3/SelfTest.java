package com.list.xp3;

import com.list.xp3.data.DataTests;
import com.list.xp3.net.NetTests;

/**
 * Everything in :core, checked on a desktop JVM.
 *
 * The parts worth being sure about are the ones the assistant writes through:
 * whether set_list is refused until the list has been read, whether a list the
 * person changed underneath it is protected, and whether the list and the chat
 * survive a round trip to disk.
 */
public final class SelfTest {

    public static void main(String[] args) {
        Check check = new Check();
        DataTests.run(check);
        NetTests.run(check);
        if (!check.report()) System.exit(1);
    }
}
