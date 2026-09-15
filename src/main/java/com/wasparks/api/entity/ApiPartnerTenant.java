package com.wasparks.api.entity;

import com.wasparks.api.enums.CreatedVia;
import com.wasparks.api.enums.PartnerTenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * The link between a partner and one of its clients (021 §2) — the row that makes a tenant "a customer
 * of LeadBoard" rather than an ordinary WaSparks tenant.
 *
 * <p><b>Two services write different columns of it</b> (shared-contracts §5, and the reason this is not
 * a read-only projection like {@link TenantRef}). admin-service INSERTs the row when it provisions the
 * tenant, and owns nothing afterwards; this service owns {@code status}, {@code messages_per_day_cap}
 * and {@code app_access}, because those are the switches the Partner console and {@code PATCH
 * /v1/customers/{id}} operate. Nothing here ever touches {@code partner_id}, {@code tenant_id} or
 * {@code external_ref} — an identity a partner has already keyed its own database against.
 *
 * <p>This table is also the answer to the question {@code X-Tenant-Id} asks on every partner-key
 * request: <b>is this tenant mine?</b> A row missing is a 404, never a 403 (§0.4) — telling a partner
 * that a tenant exists but is not theirs is telling them about a competitor's customer.
 */
@Entity
@Table(name = "api_partner_tenants")
@IdClass(ApiPartnerTenant.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiPartnerTenant {

    @Id
    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The partner's own id for this client, and the idempotency key on create. Unique per partner. */
    @Column(name = "external_ref", length = 128)
    private String externalRef;

    /** NOT NULL DEFAULT '' in 021 — a client provisioned without a name is '', never null. */
    @Column(name = "display_name", nullable = false, length = 150)
    @Builder.Default
    private String displayName = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private PartnerTenantStatus status = PartnerTenantStatus.ACTIVE;

    /** P2: the client's staff get a branded login. Stored and reported; nothing acts on it yet. */
    @Column(name = "app_access", nullable = false)
    @Builder.Default
    private boolean appAccess = false;

    /**
     * The partner's own daily ceiling for this client, or null for "the pool is the only limit".
     *
     * <p>It cannot raise anything: the effective quota is {@code min(cap, partner pool remaining)}
     * (§0.9), so a cap larger than the pool is simply never the binding constraint.
     */
    @Column(name = "messages_per_day_cap")
    private Integer messagesPerDayCap;

    @Enumerated(EnumType.STRING)
    @Column(name = "created_via", nullable = false, length = 8)
    @Builder.Default
    private CreatedVia createdVia = CreatedVia.API;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public boolean isActive() {
        return status == PartnerTenantStatus.ACTIVE;
    }

    /** Composite primary key: one row per (partner, tenant). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID partnerId;
        private UUID tenantId;
    }
}
