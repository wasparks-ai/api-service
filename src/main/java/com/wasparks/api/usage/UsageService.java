package com.wasparks.api.usage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Per-request usage counters (epic §B3). Redis is the fast path; {@link UsageFlushJob} moves the numbers
 * into {@code api_usage_daily}, which is the billing truth.
 *
 * <p>One hash per (day, tenant, key): {@code usage:{yyyymmdd}:{tenantId}:{keyId}}, fields incremented
 * with {@code HINCRBY}. A hash rather than a key per counter because the flush needs to read a whole
 * bucket atomically and delete it in one move — with separate keys, a counter incremented between the
 * read and the delete would be lost.
 *
 * <p><b>Counting never fails a request.</b> Every method swallows Redis errors: usage is an accounting
 * concern, and dropping a customer's message because a counter could not be written would be a far worse
 * outcome than an under-counted day. The flush job logs when it sees a gap.
 *
 * <p>The keys carry a 48-hour TTL as a backstop. The flush deletes each bucket as it drains it, so the
 * TTL only matters if the job has been down across a day boundary — in which case the data is stale
 * anyway and the nightly reconciliation from {@code messages} (P1.1) is the recovery path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UsageService {

    public static final String KEY_PREFIX = "usage:";
    public static final String FIELD_REQUESTS = "requests";
    public static final String FIELD_RATE_LIMITED = "rate_limited";
    public static final String FIELD_MESSAGES_ACCEPTED = "messages_accepted";
    public static final String FIELD_TEMPLATE_CREATES = "template_creates";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration BUCKET_TTL = Duration.ofHours(48);

    private final StringRedisTemplate redis;

    public void increment(UUID tenantId, UUID keyId, String field) {
        increment(tenantId, keyId, field, 1L);
    }

    public void increment(UUID tenantId, UUID keyId, String field, long delta) {
        if (tenantId == null || keyId == null) {
            return;
        }
        String key = bucketKey(LocalDate.now(ZoneOffset.UTC), tenantId, keyId);
        try {
            Long value = redis.opsForHash().increment(key, field, delta);
            if (value != null && value == delta) {
                // First write into a fresh bucket — give it the backstop TTL.
                redis.expire(key, BUCKET_TTL);
            }
        } catch (Exception e) {
            log.warn("Usage counter {} for tenant {} could not be written: {}", field, tenantId,
                    e.getMessage());
        }
    }

    /** {@code usage:20260911:{tenantId}:{keyId}} */
    public static String bucketKey(LocalDate day, UUID tenantId, UUID keyId) {
        return KEY_PREFIX + day.format(DAY) + ":" + tenantId + ":" + keyId;
    }

    /** Parse a bucket key back into its three parts; empty when the key is not one of ours. */
    public static Bucket parseKey(String key) {
        if (key == null || !key.startsWith(KEY_PREFIX)) {
            return null;
        }
        String[] parts = key.substring(KEY_PREFIX.length()).split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new Bucket(LocalDate.parse(parts[0], DAY), UUID.fromString(parts[1]),
                    UUID.fromString(parts[2]));
        } catch (Exception e) {
            return null;
        }
    }

    public record Bucket(LocalDate day, UUID tenantId, UUID keyId) {
    }
}
