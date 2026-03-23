package me.cortex.voxy.common.util;

/**
 * Polyfills for Java 21 Integer.compress/expand and Long.expand methods,
 * needed for Java 17 compatibility.
 */
public final class BitUtil {
    private BitUtil() {}

    /**
     * Equivalent to Integer.compress(i, mask) from Java 21.
     * Compresses the bits of i selected by mask to contiguous low-order bits.
     */
    public static int compressInt(int i, int mask) {
        i = i & mask;
        int mk = ~mask << 1;
        for (int j = 0; j < 5; j++) {
            int mp = mk ^ (mk << 1);
            mp ^= mp << 2;
            mp ^= mp << 4;
            mp ^= mp << 8;
            mp ^= mp << 16;
            int mv = mp & mask;
            mask = (mask ^ mv) | (mv >>> (1 << j));
            i = ((i ^ (i & mv)) | ((i & mv) >>> (1 << j)));
            mk &= ~mp;
        }
        return i;
    }

    /**
     * Equivalent to Integer.expand(i, mask) from Java 21.
     * Expands contiguous low-order bits of i into positions selected by mask.
     */
    public static int expandInt(int i, int mask) {
        int originalMask = mask;
        int mk = ~mask << 1;
        for (int j = 0; j < 5; j++) {
            int mp = mk ^ (mk << 1);
            mp ^= mp << 2;
            mp ^= mp << 4;
            mp ^= mp << 8;
            mp ^= mp << 16;
            int mv = mp & mask;
            mask = (mask ^ mv) | (mv >>> (1 << j));
            int t = i << (1 << j);
            i = (i & ~mv) | (t & mv);
            mk &= ~mp;
        }
        return i & originalMask;
    }

    /**
     * Equivalent to Long.expand(i, mask) from Java 21.
     * Expands contiguous low-order bits of i into positions selected by mask.
     */
    public static long expandLong(long i, long mask) {
        long originalMask = mask;
        long mk = ~mask << 1;
        for (int j = 0; j < 6; j++) {
            long mp = mk ^ (mk << 1);
            mp ^= mp << 2;
            mp ^= mp << 4;
            mp ^= mp << 8;
            mp ^= mp << 16;
            mp ^= mp << 32;
            long mv = mp & mask;
            mask = (mask ^ mv) | (mv >>> (1 << j));
            long t = i << (1 << j);
            i = (i & ~mv) | (t & mv);
            mk &= ~mp;
        }
        return i & originalMask;
    }
}
