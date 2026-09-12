package com.wasparks.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UUIDv7 generation. These ids become {@code messages.id} upstream, so uniqueness is a correctness
 * requirement rather than a nicety — a collision would be one customer's message overwriting another's.
 */
class Uuid7Test {

    @Test
    @DisplayName("carries version 7 and the RFC variant")
    void versionAndVariant() {
        UUID id = Uuid7.generate();
        assertEquals(7, id.version());
        assertEquals(2, id.variant(), "variant 2 is the RFC 4122/9562 layout");
    }

    @Test
    @DisplayName("ids minted in sequence sort in the order they were created")
    void monotonic() {
        // The whole reason for v7 over v4: a time-ordered id keeps the primary-key index dense instead
        // of scattering inserts across the whole B-tree.
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            ids.add(Uuid7.generate());
        }
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(compareUnsigned(ids.get(i - 1), ids.get(i)) < 0,
                    "id " + i + " sorted before its predecessor");
        }
    }

    @Test
    @DisplayName("stays unique across concurrent threads")
    void uniqueUnderConcurrency() throws InterruptedException {
        // Generation is synchronized and the intra-millisecond counter is shared state; this is the
        // test that would catch a future "optimisation" that removed either.
        int threads = 8;
        int perThread = 2000;
        Set<UUID> all = java.util.Collections.synchronizedSet(new HashSet<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        all.add(Uuid7.generate());
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "generation did not finish in time");
        pool.shutdownNow();
        assertEquals(threads * perThread, all.size(), "duplicate ids were generated");
    }

    private int compareUnsigned(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high
                : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
