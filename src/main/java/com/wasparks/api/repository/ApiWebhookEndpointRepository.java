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

    /** Counts against the plan's {@code max_webhook_endpoints}. DISABLED rows still occupy a slot. */
    long countByTenantId(UUID tenantId);
}
