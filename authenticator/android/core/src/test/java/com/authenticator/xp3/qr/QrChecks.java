package com.authenticator.xp3.qr;

import com.xp3.common.Check;

import java.util.Random;

/** Checks of the QR reader's insides, which SelfTest can't reach from its package. */
public final class QrChecks {

    private QrChecks() {}

    public static void run(Check check) {
        check.run("reed-solomon", () -> {
            Random random = new Random(1);
            for (int trial = 0; trial < 200; trial++) {
                int ec = 2 + random.nextInt(29);
                int dataLength = 1 + random.nextInt(120);
                int[] data = new int[dataLength];
                for (int i = 0; i < dataLength; i++) data[i] = random.nextInt(256);
                int[] parity = ReedSolomon.encode(data, ec);
                int[] block = new int[dataLength + ec];
                System.arraycopy(data, 0, block, 0, dataLength);
                System.arraycopy(parity, 0, block, dataLength, ec);
                int[] damaged = block.clone();
                int errors = random.nextInt(ec / 2 + 1);
                for (int e = 0; e < errors; e++) damaged[random.nextInt(damaged.length)] ^= 1 + random.nextInt(255);
                boolean mended = ReedSolomon.correct(damaged, ec);
                check.ok("mends up to half the check bytes (trial " + trial + ")", mended && java.util.Arrays.equals(block, damaged));
            }
            int[] data = {1, 2, 3, 4, 5, 6, 7, 8};
            int[] parity = ReedSolomon.encode(data, 4);
            int[] block = new int[12];
            System.arraycopy(data, 0, block, 0, 8);
            System.arraycopy(parity, 0, block, 8, 4);
            int[] original = block.clone();
            block[0] ^= 9;
            block[3] ^= 7;
            block[6] ^= 5;
            // Three errors are beyond four check bytes: never "mended" back to the original.
            boolean claimed = ReedSolomon.correct(block, 4);
            check.ok("too many errors are not mended", !claimed || !java.util.Arrays.equals(original, block));
        });

        check.run("format and version bits", () -> {
            check.same("format of M, mask 0", 0x5412, Version.formatBits(0));
            check.same("format of L, mask 7 (from the spec's table)", 0x6976, Version.formatBits(0x0F));
            check.same("version 7's information (from the spec)", 0x07C94, Version.versionBits(7));
            check.same("version 40's information (from the spec)", 0x28C69, Version.versionBits(40));
            check.same("format read with 3 errors", 0x0F, Version.decodeFormatBits(0x6976 ^ 0x0007, 0));
            check.same("version read with 3 errors", 21, Version.decodeVersionBits(Version.versionBits(21) ^ 0x10101));
            for (int v = 1; v <= 40; v++) {
                Version version = Version.of(v);
                int[] groups = version.blocks(Version.H);
                int total = 0;
                for (int g = 0; g < groups.length; g += 3) total += groups[g] * groups[g + 1];
                int dimension = version.dimension();
                int function = 0;
                BitMatrix pattern = version.functionPattern();
                for (int y = 0; y < dimension; y++) for (int x = 0; x < dimension; x++) if (pattern.get(x, y)) function++;
                int dataModules = dimension * dimension - function;
                check.ok("version " + v + "'s codewords fill its data area", dataModules / 8 == total && total == version.totalCodewords());
            }
        });
    }
}
