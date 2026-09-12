package com.wasparks.api.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562) — time-ordered, so {@code messages.id} values cluster by insertion time and the
 * B-tree stays dense instead of scattering the way a v4 would. Hand-rolled rather than pulling in a
 * dependency for one method.
 *
 * <p>Layout: 48 bits of Unix milliseconds, version 7, a 12-bit sub-millisecond counter, variant 10, and
 * 62 bits of randomness. The counter makes ids minted inside the same millisecond strictly increasing, so
 * two sends in one tick cannot collide or sort arbitrarily; it is reseeded with fresh randomness whenever
 * the clock advances, and if a millisecond ever produces more than 4096 ids the generator spins to the
 * next millisecond rather than wrapping into a duplicate.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_COUNTER = 0xFFF;

    private static long lastMillis = -1L;
    private static int counter = 0;

    private Uuid7() {
    }

    public static synchronized UUID generate() {
        long millis = System.currentTimeMillis();
        if (millis > lastMillis) {
            lastMillis = millis;
            counter = RANDOM.nextInt(MAX_COUNTER >>> 1);   // leave headroom before the roll-over spin
        } else {
            millis = lastMillis;
            counter++;
            if (counter > MAX_COUNTER) {
                // More than 4096 ids in one millisecond: advance to the next one rather than wrap.
                do {
                    millis = System.currentTimeMillis();
                } while (millis <= lastMillis);
                lastMillis = millis;
                counter = 0;
            }
        }

        long msb = (millis & 0xFFFFFFFFFFFFL) << 16      // 48 bits of timestamp
                | 0x7000L                                 // version 7
                | (counter & MAX_COUNTER);                // 12-bit intra-millisecond counter

        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;   // variant 10
        return new UUID(msb, lsb);
    }
}
