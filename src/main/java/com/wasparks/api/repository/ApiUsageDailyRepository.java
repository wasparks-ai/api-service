package com.wasparks.api.repository;

import com.wasparks.api.entity.ApiUsageDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ApiUsageDailyRepository
        extends JpaRepository<ApiUsageDaily, ApiUsageDaily.Key> {

    List<ApiUsageDaily> findByTenantIdAndDayBetween(UUID tenantId, LocalDate from, LocalDate to);

    /**
     * The flush (epic §B3). Native because it must be a single atomic upsert that <b>adds</b> deltas:
     * two flushes racing, or a flush racing the nightly reconciliation, must sum rather than overwrite.
     * JPA has no portable {@code ON CONFLICT … DO UPDATE SET col = col + EXCLUDED.col}, and the
     * read-modify-write it would otherwise take loses counts under concurrency.
     *
     * <p>The counters flushed are deltas drained from Redis, never running totals, so adding is correct
     * even if a batch is replayed after a partial failure — {@code UsageFlushJob} deletes the Redis hash
     * only once this statement has committed.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO api_usage_daily (tenant_id, api_key_id, day, requests, rate_limited,
                                         messages_accepted, messages_sent, messages_failed, template_creates)
            VALUES (:tenantId, :apiKeyId, :day, :requests, :rateLimited,
                    :messagesAccepted, 0, 0, :templateCreates)
            ON CONFLICT (tenant_id, api_key_id, day) DO UPDATE SET
                requests          = api_usage_daily.requests          + EXCLUDED.requests,
                rate_limited      = api_usage_daily.rate_limited      + EXCLUDED.rate_limited,
                messages_accepted = api_usage_daily.messages_accepted + EXCLUDED.messages_accepted,
                template_creates  = api_usage_daily.template_creates  + EXCLUDED.template_creates
            """, nativeQuery = true)
    void upsertDelta(@Param("tenantId") UUID tenantId,
                     @Param("apiKeyId") UUID apiKeyId,
                     @Param("day") LocalDate day,
                     @Param("requests") int requests,
                     @Param("rateLimited") int rateLimited,
                     @Param("messagesAccepted") int messagesAccepted,
                     @Param("templateCreates") int templateCreates);
}
