package com.wasparks.api.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.internal.InternalTenantsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Reads a customer's per-client settings — today just the marketing frequency guard (§B3a).
 *
 * <h2>Why this is cached</h2>
 * Every customer response carries {@code minDaysBetweenMarketing}, and the value lives in
 * {@code tenants.min_days_between_marketing}, which <b>tenants-service owns</b> (internal.md, Customer
 * settings: one writer per column, and it is the service that reads it at campaign create). So reading it
 * means an internal call — and a partner listing two hundred customers would make two hundred of them,
 * inside one request, over HTTP.
 *
 * <p>Hence a short Redis cache per tenant. The number changes perhaps twice in a customer's lifetime, is
 * read on every list, and is <b>invalidated the moment this service writes it</b>, so the TTL only ever
 * bounds a change made somewhere else. A miss costs exactly what the uncached version would have.
 *
 * <p>An unreadable or unreachable setting reports {@code 0} — the same value as "off" — rather than
 * failing the customer list. A guard we cannot read about is a field on a screen; a customer list that
 * 500s because one internal call timed out is an outage. The distinction is logged, not surfaced.
 *
 * <p>Reading the mapped {@code tenants} row directly would avoid the call entirely, and was not done on
 * purpose: this service does not map that column, and adding it would give two services a reader's
 * opinion about a value whose range and meaning are defined upstream. If the N-per-list shape turns out
 * to matter more than that, a batch read is the fix — see the report.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerSettingsService {

    /** {@code partner:settings:{tenantId}} — the frequency guard, as a plain integer string. */
    private static final String CACHE_PREFIX = "partner:settings:";

    /** What an unknown, unreadable or never-set guard reports. Identical to "off", which it is. */
    public static final int GUARD_OFF = 0;

    private final InternalTenantsClient tenantsClient;
    private final StringRedisTemplate redis;

    @Value("${app.partner.tenant-cache-ttl-seconds}")
    private long cacheTtlSeconds;

    /** {@code minDaysBetweenMarketing} for one customer; {@code 0} when it is off or unknown. */
    public int frequencyGuardDays(UUID tenantId, UUID apiKeyId) {
        String cacheKey = CACHE_PREFIX + tenantId;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return Integer.parseInt(cached);
            }
        } catch (Exception e) {
            log.debug("Customer settings cache unusable for {}: {}", tenantId, e.getMessage());
        }

        int days = read(tenantId, apiKeyId);
        try {
            redis.opsForValue().set(cacheKey, String.valueOf(days),
                    Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.debug("Could not cache the customer settings for {}: {}", tenantId, e.getMessage());
        }
        return days;
    }

    private int read(UUID tenantId, UUID apiKeyId) {
        try {
            JsonNode settings = tenantsClient.getTenantSettings(tenantId, apiKeyId);
            return settings != null && settings.hasNonNull("minDaysBetweenMarketing")
                    ? settings.get("minDaysBetweenMarketing").asInt(GUARD_OFF)
                    : GUARD_OFF;
        } catch (Exception e) {
            // Includes the case where tenants-service has not shipped the GET yet: the field reads 0,
            // the customer list still renders, and the PATCH that sets it still works.
            log.debug("Could not read customer settings for {}: {}", tenantId, e.getMessage());
            return GUARD_OFF;
        }
    }

    /**
     * Drop one customer's cached settings. Called immediately after this service writes them, so the
     * console never shows the value the partner has just replaced.
     */
    public void invalidate(UUID tenantId) {
        try {
            redis.delete(CACHE_PREFIX + tenantId);
        } catch (Exception e) {
            log.warn("Could not invalidate the customer settings cache for {}: {}", tenantId,
                    e.getMessage());
        }
    }
}
