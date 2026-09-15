package com.wasparks.api.repository;

import com.wasparks.api.entity.ApiPartner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to {@code api_partners} — admin-service owns every write (021 ownership header).
 *
 * <p>{@link #findByOwnerTenantId} is backed by the unique index 021 §1 creates, so it is a one-row index
 * hit rather than a scan. It answers "is the tenant behind this session a partner?" for the console
 * backend's own resolve; the authoritative answer still comes from admin-service's
 * {@code /internal/v1/partners/by-owner/{tenantId}}, which is what the console actually calls.
 */
public interface ApiPartnerRepository extends JpaRepository<ApiPartner, UUID> {

    Optional<ApiPartner> findByOwnerTenantId(UUID ownerTenantId);
}
