package com.print.xp3;

import com.print.xp3.find.FindTests;
import com.print.xp3.ipp.IppTests;
import com.print.xp3.raster.RasterTests;
import com.print.xp3.send.SendTests;
import com.xp3.common.Check;

/** Runs on a desktop JVM via `:core:selfTest`: the codecs, the formats, the plan, then whole jobs to a printer on loopback. */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        Check check = new Check();
        IppTests.run(check);
        RasterTests.run(check);
        SendTests.run(check);
        FindTests.run(check);
        PlanTests.run(check);
        JobTests.run(check);
        if (!check.report()) System.exit(1);
    }
}
