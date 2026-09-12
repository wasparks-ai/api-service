package com.wasparks.api.usage;

import com.wasparks.api.entity.ApiUsageDaily;
import com.wasparks.api.repository.ApiUsageDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Moves the Redis usage buckets into {@code api_usage_daily} every 10 minutes (epic §B3).
 *
 * <p>Each bucket is drained with {@code HGETALL} + {@code DEL} and then added to the database row with
 * an upsert that <b>sums</b> rather than overwrites. Read-and-delete is the important half: the deltas
 * leave Redis exactly once, so a bucket that is written again between two flushes simply becomes the
 * next delta. The alternative — reading a running total and writing it over the row — loses every
 * increment that lands between the read and the write, and quietly under-bills.
 *
 * <p>Two rows are written per bucket: the per-key row and the tenant-wide roll-up, which uses the
 * all-zero sentinel {@code api_key_id} because the primary key cannot hold a NULL (amendment 1).
 * Writing both here rather than aggregating at read time means admin-webapp's usage table is one
 * indexed lookup.
 *
 * <p>{@code SCAN} rather than {@code KEYS}: this runs against the same Redis instance serving the send
 * path, and {@code KEYS} blocks the server for the duration of the scan.
 *
 * <p>Yesterday's buckets are drained too, not just today's — a flush that runs at 00:03 must not strand
 * the last three minutes of the previous day in Redis until its TTL expires.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UsageFlushJob {

    private static final int SCAN_BATCH = 500;

    private final StringRedisTemplate redis;
    private final ApiUsageDailyRepository usageRepository;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${app.usage.flush-ms}",
            initialDelayString = "${app.usage.flush-initial-delay-ms}")
    @SchedulerLock(name = "api-usage-flush", lockAtMostFor = "PT20M", lockAtLeastFor = "PT10S")
    public void flush() {
        try {
            int flushed = drainAll();
            if (flushed > 0) {
                log.debug("Flushed {} usage buckets into api_usage_daily", flushed);
            }
        } catch (Exception e) {
            // The next tick tries again; the buckets are still in Redis because they are only deleted
            // after a successful drain.
            log.error("Usage flush failed", e);
        }
    }

    /** Visible for tests: drain every bucket currently in Redis and return how many were written. */
    public int drainAll() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(UsageService.KEY_PREFIX + "*")
                .count(SCAN_BATCH)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }

        int flushed = 0;
        for (String key : keys) {
            // Each bucket in its own transaction, through a TransactionTemplate rather than
            // @Transactional: drain() is called from this same bean, and a self-invocation never
            // reaches the proxy, so the annotation would be inert and the two upserts would not be
            // atomic with each other.
            Boolean drained = transactionTemplate.execute(status -> drain(key));
            if (Boolean.TRUE.equals(drained)) {
                flushed++;
            }
        }
        return flushed;
    }

    /**
     * Drain one bucket. Returns false when there was nothing in it.
     *
     * <p>The {@code DEL} happens before the database write, and that ordering is deliberate: a crash
     * between them loses at most one bucket of counters, whereas deleting afterwards would let a second
     * flush (or a restarted one) read the same values again and double-count them into a billing table.
     * Losing a count is recoverable from {@code messages} in the nightly reconciliation; inventing one
     * is not.
     */
    public boolean drain(String key) {
        UsageService.Bucket bucket = UsageService.parseKey(key);
        if (bucket == null) {
            log.warn("Skipping unrecognised usage key {}", key);
            return false;
        }

        Map<Object, Object> raw = redis.opsForHash().entries(key);
        if (raw.isEmpty()) {
            redis.delete(key);
            return false;
        }
        redis.delete(key);

        int requests = intOf(raw, UsageService.FIELD_REQUESTS);
        int rateLimited = intOf(raw, UsageService.FIELD_RATE_LIMITED);
        int accepted = intOf(raw, UsageService.FIELD_MESSAGES_ACCEPTED);
        int templateCreates = intOf(raw, UsageService.FIELD_TEMPLATE_CREATES);

        usageRepository.upsertDelta(bucket.tenantId(), bucket.keyId(), bucket.day(),
                requests, rateLimited, accepted, templateCreates);
        // The tenant-wide roll-up, under the zero-UUID sentinel (never NULL — amendment 1).
        usageRepository.upsertDelta(bucket.tenantId(), ApiUsageDaily.TENANT_TOTAL, bucket.day(),
                requests, rateLimited, accepted, templateCreates);
        return true;
    }

    private int intOf(Map<Object, Object> raw, String field) {
        Object value = raw.get(field);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Unparseable usage counter {}={}", field, value);
            return 0;
        }
    }
}
