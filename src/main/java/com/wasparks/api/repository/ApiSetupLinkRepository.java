package com.wasparks.api.repository;

import com.wasparks.api.entity.ApiSetupLink;
import com.wasparks.api.enums.SetupLinkStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to {@code api_setup_links} (021 §3). tenants-service writes every column; this service
 * reads them so a partner can see a link's state without a round trip per row.
 *
 * <p>Every finder is scoped by {@code partnerId} as well as by whatever else it filters on. The partner
 * is established from the key, so a link id belonging to another partner resolves to nothing and becomes
 * a 404 — the same answer a link that never existed gets.
 */
public interface ApiSetupLinkRepository extends JpaRepository<ApiSetupLink, UUID> {

    Optional<ApiSetupLink> findByIdAndPartnerId(UUID id, UUID partnerId);

    List<ApiSetupLink> findByPartnerIdAndTenantIdOrderByCreatedAtDesc(UUID partnerId, UUID tenantId);

    /** The "at most one live link per customer" check (§B2) — a new link cancels whatever this finds. */
    Optional<ApiSetupLink> findFirstByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, SetupLinkStatus status);
}
