package com.wasparks.api.repository;

import com.wasparks.api.entity.ApiKey;
import com.wasparks.api.enums.ApiKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /** The authentication lookup. {@code key_hash} is UNIQUE, so this is an index hit on one row. */
    Optional<ApiKey> findByKeyHash(String keyHash);

    /**
     * The tenant's own keys. UI-session keys are excluded here rather than filtered by the controller,
     * so a listing cannot leak one by forgetting to (§D.3): they are an implementation detail of
     * tenant-web's own session, not something the tenant issued.
     */
    List<ApiKey> findByTenantIdAndUiSessionFalseOrderByCreatedAtDesc(UUID tenantId);

    Optional<ApiKey> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * One tenant, one usable key id — the sweep list for {@code ScheduledCampaignQuotaJob} (api-partner
     * epic §B3).
     *
     * <p>Two things come out of one query on purpose. Only a tenant holding an API key can have an API
     * campaign, so this <em>is</em> the set of tenants worth sweeping; and the internal chain requires
     * {@code X-Actor-Api-Key} to be a real {@code api_keys} id, so the sweep needs a key to call as. The
     * key is arbitrary (the smallest id, for stability between ticks) because the job never acts as it —
     * it only reads, and the audit trail records that a key belonging to the tenant did the reading.
     *
     * <p>UI-session keys are excluded: they expire hourly, and a sweep list that churned every hour would
     * be a different list every time for no reason.
     */
    @Query("SELECT k.tenantId AS tenantId, MIN(k.id) AS apiKeyId FROM ApiKey k "
            + "WHERE k.status = com.wasparks.api.enums.ApiKeyStatus.ACTIVE AND k.uiSession = false "
            + "GROUP BY k.tenantId")
    List<TenantKeyRef> findActiveTenantKeys();

    /** A tenant and one of its usable key ids. */
    interface TenantKeyRef {

        UUID getTenantId();

        UUID getApiKeyId();
    }

    /**
     * Counts against the plan's {@code max_keys}. Revoked keys do not occupy a slot, and neither do
     * UI-session keys — a tenant on the 3-key FREE plan must not be locked out of their own dashboard
     * by having opened it three times.
     */
    long countByTenantIdAndStatusAndUiSessionFalse(UUID tenantId, ApiKeyStatus status);

    /**
     * The daily purge (§D.3). A UI-session key is unusable the moment it expires — {@link ApiKey#isUsable}
     * checks {@code expires_at} — so this is housekeeping rather than security: without it the table
     * accumulates a dead row per dashboard visit forever.
     *
     * <p>A bulk DELETE rather than a load-and-delete: there is nothing to cascade (no message references
     * a UI-session key — those scopes cannot send) and nothing to audit in a row that only ever existed
     * for an hour.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM ApiKey k WHERE k.uiSession = true AND k.expiresAt < :before")
    int deleteExpiredUiSessionKeys(@Param("before") Instant before);

    /**
     * {@code last_used_at} touch. A bare UPDATE rather than a load-modify-save: the value is advisory,
     * the caller already rate-limits it to once a key per minute, and going through the entity would
     * pull a managed row into the request's persistence context for a column nothing reads back.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE ApiKey k SET k.lastUsedAt = :at WHERE k.id = :id")
    void touchLastUsedAt(@Param("id") UUID id, @Param("at") Instant at);
}
