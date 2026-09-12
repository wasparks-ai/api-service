package com.wasparks.api.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the pending-entry sweep every 60 seconds (epic §B6).
 *
 * <p>Deliberately <b>not</b> ShedLock'd, unlike the webhook jobs. Reclaiming is per-instance recovery
 * work and a second replica sweeping concurrently is harmless — {@code XCLAIM} with a minimum idle time
 * means only one of them can take any given entry, and the entries at stake belong to workers that are
 * already gone. Locking it would mean a single replica's stranded entries wait for whichever instance
 * holds the lock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SendReclaimJob {

    private final SendWorker sendWorker;
    private final SendQueue sendQueue;

    @Scheduled(fixedDelayString = "${app.queue.claim-interval-ms}",
            initialDelayString = "${app.queue.claim-interval-ms}")
    public void sweep() {
        int reclaimed = sendWorker.reclaimStale();
        if (reclaimed > 0) {
            log.info("Reclaimed {} stranded send entries", reclaimed);
        }
        // Bounding the stream is minute-scale housekeeping, not per-send work — see SendQueue.trim().
        sendQueue.trim();
    }
}
