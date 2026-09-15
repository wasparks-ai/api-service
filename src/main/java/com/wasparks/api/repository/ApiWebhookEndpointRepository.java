package com.wasparks.api.repository;

import com.wasparks.api.entity.ApiWebhookEndpoint;
import com.wasparks.api.enums.WebhookEndpointStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiWebhookEndpointRepository extends JpaRepository<ApiWebhookEndpoint, UUID> {

    List<ApiWebhookEndpoint> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** The fan-out query: only ACTIVE endpoints receive events. PAUSED and DISABLED are skipped. */
    List<ApiWebhookEndpoint> findByTenantIdAndStatus(UUID tenantId, WebhookEndpointStatus status);

    Optional<ApiWebhookEndpoint> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * The partner fan-out query (§B5): a partner's own endpoints, which receive every event for every
     * one of its clients rather than only the events of the tenant they are filed under.
     *
     * <p>Partner endpoints carry {@code tenant_id = owner_tenant_id} and {@code partner_id} set, so they
     * would also be returned by {@link #findByTenantIdAndStatus} when an event concerns the partner's own
     * tenant. The poller de-duplicates by endpoint id for exactly that overlap.
     */
    List<ApiWebhookEndpoint> findByPartnerIdAndStatus(UUID partnerId, WebhookEndpointStatus status);

    List<ApiWebhookEndpoint> findByPartnerIdOrderByCreatedAtDesc(UUID partnerId);

    /** Counts against the plan's {@code max_webhook_endpoints}. DISABLED rows still occupy a slot. */
    long countByTenantId(UUID tenantId);
}
