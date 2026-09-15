package com.wasparks.api.entity;

import com.wasparks.api.enums.BillingModel;
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

    /**
     * FIXED = the allowance above with {@link OveragePolicy} past it; METERED = no meaningful allowance,
     * every accepted message priced (epic §0.9). The seeded PARTNER_* plans are one of each.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_model", nullable = false, length = 8)
    private BillingModel billingModel;

    /** Minor units, never a float (the 019 rule). NULL on a FIXED plan; required on a METERED one. */
    @Column(name = "price_per_message_minor")
    private Long pricePerMessageMinor;

    @Column(name = "currency", length = 3)
    private String currency;

    /**
     * Assignable only to a partner's owner tenant, and its allowance <b>pools</b> across every client of
     * that partner (§0.9). admin-service enforces the assignment rule in both directions; this service
     * reads the flag to know whether {@code messagesPerDay} means "this tenant" or "this partner".
     */
    @Column(name = "partner_plan", nullable = false)
    private boolean partnerPlan;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false, insertable = false)
    private Instant updatedAt;
}
