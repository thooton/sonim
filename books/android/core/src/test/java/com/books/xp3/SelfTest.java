package com.books.xp3;

import com.books.xp3.book.BookTests;
import com.books.xp3.book.MobiTests;
import com.books.xp3.shelf.ShelfTests;
import com.xp3.common.Check;

/**
 * Everything in :core, checked on a desktop JVM.
 *
 * The parts worth being sure about are reading books the way they are found
 * in the wild (loose markup, odd encodings, missing pieces) and the library
 * index surviving a round trip to disk.
 */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check();
        BookTests.run(check);
        MobiTests.run(check);
        ShelfTests.run(check);
        if (!check.report()) System.exit(1);
    }
}
