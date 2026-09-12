package com.wasparks.api.entity;

import com.wasparks.api.enums.OveragePolicy;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A rate/quota plan. <b>Read-only here</b> — admin-service owns every write (epic §0.5, shared-contracts
 * §5); admin-webapp is where plans are created and edited. This service only resolves the tenant's
 * effective limits from it.
 *
 * <p>Exactly one row carries {@code is_default = true} (partial unique index in 020). That row is what a
 * tenant with no {@code tenant_api_plans} assignment falls back to — seeded as FREE.
 */
@Entity
@Table(name = "api_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiPlan {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "requests_per_minute", nullable = false)
    private Integer requestsPerMinute;

    @Column(name = "messages_per_day", nullable = false)
    private Integer messagesPerDay;

    @Column(name = "messages_per_month", nullable = false)
    private Integer messagesPerMonth;

    @Column(name = "template_creates_per_day", nullable = false)
    private Integer templateCreatesPerDay;

    @Column(name = "max_keys", nullable = false)
    private Integer maxKeys;

    @Column(name = "max_webhook_endpoints", nullable = false)
    private Integer maxWebhookEndpoints;

    /** BLOCK rejects an over-quota send with 429; WARN accepts it and flags the response (epic §B3). */
    @Enumerated(EnumType.STRING)
    @Column(name = "overage_policy", nullable = false, length = 8)
    private OveragePolicy overagePolicy;

    /** A sandbox-only plan may issue LIVE keys but every send is forced to the DRYRUN path. */
    @Column(name = "sandbox_only", nullable = false)
    private boolean sandboxOnly;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false, insertable = false)
    private Instant updatedAt;
}
