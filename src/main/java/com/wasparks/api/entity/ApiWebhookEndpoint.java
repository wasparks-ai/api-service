package com.wasparks.api.entity;

import com.wasparks.api.enums.WebhookEndpointStatus;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A tenant's outbound webhook endpoint (epic §B8). Written by this service.
 *
 * <p>{@code secretEncrypted} holds the HMAC signing secret under AES-256-GCM (shared-contracts §1) and is
 * decrypted per delivery, never logged, and shown to the tenant exactly once at creation.
 *
 * <p>{@code events} is a JSONB array of glob patterns ({@code ["message.*", "template.approved"]}) matched
 * against the outbox event type. {@code consecutiveFailures} drives the automatic PAUSE.
 */
@Entity
@Table(name = "api_webhook_endpoints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiWebhookEndpoint {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "partner_id")
    private UUID partnerId;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "secret_encrypted", nullable = false, columnDefinition = "TEXT")
    private String secretEncrypted;

    @Type(JsonType.class)
    @Column(name = "events", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> events = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WebhookEndpointStatus status;

    @Column(name = "consecutive_failures", nullable = false)
    @Builder.Default
    private Integer consecutiveFailures = 0;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false, insertable = false)
    private Instant updatedAt;
}
