package com.wasparks.api.repository;

import com.wasparks.api.entity.ApiPartnerTenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The partner → client links (021 §2).
 *
 * <p>Two read shapes matter and they are deliberately different. {@link #findTenantIdsByPartnerId} feeds
 * the 60-second Redis set behind {@code X-Tenant-Id} resolution, so it returns ids and nothing else — the
 * cache holds a membership set, and loading whole entities to build one would be work thrown away on
 * every request. {@link #findByPartnerIdAndTenantId} is the slow path taken on a cache miss and by every
 * write.
 */
public interface ApiPartnerTenantRepository
        extends JpaRepository<ApiPartnerTenant, ApiPartnerTenant.Key> {

    Optional<ApiPartnerTenant> findByPartnerIdAndTenantId(UUID partnerId, UUID tenantId);

    List<ApiPartnerTenant> findByPartnerIdOrderByCreatedAtDesc(UUID partnerId);

    Optional<ApiPartnerTenant> findByPartnerIdAndExternalRef(UUID partnerId, String externalRef);

    /**
     * Every tenant this partner may act on, ACTIVE links only.
     *
     * <p>A SUSPENDED link is excluded here rather than filtered after the fact, so suspending a client
     * takes effect the moment the cache expires and a suspended client is indistinguishable from one that
     * was never this partner's — both are a 404 (§0.4).
     */
    @Query("SELECT l.tenantId FROM ApiPartnerTenant l "
            + "WHERE l.partnerId = :partnerId AND l.status = com.wasparks.api.enums.PartnerTenantStatus.ACTIVE")
    List<UUID> findTenantIdsByPartnerId(@Param("partnerId") UUID partnerId);

    /** The partner a tenant belongs to, for webhook fan-out (§B5). At most one row: a client has one owner. */
    @Query("SELECT l.partnerId FROM ApiPartnerTenant l WHERE l.tenantId = :tenantId")
    List<UUID> findPartnerIdsByTenantId(@Param("tenantId") UUID tenantId);

    long countByPartnerId(UUID partnerId);
}
