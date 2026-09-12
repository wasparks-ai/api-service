package com.wasparks.api.usage;

import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.entity.ApiUsageDaily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis counters to {@code api_usage_daily} (epic §B3).
 *
 * <p>This table is what a customer is billed from, so the two properties worth proving are that a flush
 * <em>adds</em> rather than overwrites, and that the tenant roll-up uses the zero-UUID sentinel rather
 * than NULL — which the primary key would reject (amendment 1).
 */
class UsageFlushIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UsageService usageService;
    @Autowired
    private UsageFlushJob flushJob;

    @Test
    @DisplayName("counters flush into a per-key row and a tenant roll-up")
    void flushWritesBothRows() {
        UUID keyId = UUID.randomUUID();
        usageService.increment(tenantId, keyId, UsageService.FIELD_REQUESTS, 5);
        usageService.increment(tenantId, keyId, UsageService.FIELD_RATE_LIMITED, 2);
        usageService.increment(tenantId, keyId, UsageService.FIELD_MESSAGES_ACCEPTED, 3);

        assertEquals(1, flushJob.drainAll());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ApiUsageDaily perKey = usageRepository
                .findById(new ApiUsageDaily.Key(tenantId, keyId, today)).orElseThrow();
        assertEquals(5, perKey.getRequests());
        assertEquals(2, perKey.getRateLimited());
        assertEquals(3, perKey.getMessagesAccepted());

        ApiUsageDaily rollUp = usageRepository
                .findById(new ApiUsageDaily.Key(tenantId, ApiUsageDaily.TENANT_TOTAL, today))
                .orElseThrow();
        assertEquals(5, rollUp.getRequests());
        assertEquals(new UUID(0L, 0L), rollUp.getApiKeyId(),
                "the tenant row must use the zero-UUID sentinel, never NULL");
    }

    @Test
    @DisplayName("a second flush adds to the row rather than replacing it")
    void flushesAccumulate() {
        // The property that stops a customer being under-billed: deltas leave Redis exactly once and
        // are summed into the row, so counts that arrive between flushes are never lost.
        UUID keyId = UUID.randomUUID();
        usageService.increment(tenantId, keyId, UsageService.FIELD_REQUESTS, 4);
        flushJob.drainAll();

        usageService.increment(tenantId, keyId, UsageService.FIELD_REQUESTS, 6);
        flushJob.drainAll();

        ApiUsageDaily row = usageRepository.findById(new ApiUsageDaily.Key(
                tenantId, keyId, LocalDate.now(ZoneOffset.UTC))).orElseThrow();
        assertEquals(10, row.getRequests());
    }

    @Test
    @DisplayName("a drained bucket is removed from Redis")
    void bucketIsDrained() {
        UUID keyId = UUID.randomUUID();
        usageService.increment(tenantId, keyId, UsageService.FIELD_REQUESTS, 1);
        String bucket = UsageService.bucketKey(LocalDate.now(ZoneOffset.UTC), tenantId, keyId);
        assertNotNull(redis.opsForHash().get(bucket, UsageService.FIELD_REQUESTS));

        flushJob.drainAll();

        assertTrue(redis.opsForHash().entries(bucket).isEmpty(),
                "leaving the bucket would double-count it on the next flush");
    }

    @Test
    @DisplayName("a bucket key gets a TTL so a stalled job cannot leak keys forever")
    void bucketHasTtl() {
        UUID keyId = UUID.randomUUID();
        usageService.increment(tenantId, keyId, UsageService.FIELD_REQUESTS, 1);

        Long ttl = redis.getExpire(UsageService.bucketKey(
                LocalDate.now(ZoneOffset.UTC), tenantId, keyId));
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 48 * 3600, "expected a 48-hour backstop, got " + ttl);
    }

    @Test
    @DisplayName("nothing to flush is not an error")
    void emptyFlush() {
        assertEquals(0, flushJob.drainAll());
    }

    @Test
    @DisplayName("a bucket key round-trips through its parser")
    void keyRoundTrip() {
        UUID keyId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 9, 11);
        UsageService.Bucket parsed = UsageService.parseKey(
                UsageService.bucketKey(day, tenantId, keyId));

        assertNotNull(parsed);
        assertEquals(day, parsed.day());
        assertEquals(tenantId, parsed.tenantId());
        assertEquals(keyId, parsed.keyId());
    }
}
