package com.wasparks.api.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The tenant's plan assignment. <b>Read-only here</b> — admin-service writes it (epic §0.5).
 *
 * <p>{@code overrides} is a per-tenant patch over the plan's columns, keyed by the same names
 * ({@code requestsPerMinute}, {@code messagesPerDay}, …). It exists so a single customer can be given
 * headroom without minting a bespoke plan; {@code PlanResolver} layers it on top of the plan row.
 *
 * <p>The PK is {@code tenant_id}, so a tenant has at most one assignment. {@code ends_at} in the past
 * means the assignment has lapsed and the tenant falls back to the default plan.
 */
@Entity
@Table(name = "tenant_api_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantApiPlan {

    @Id
    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID tenantId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Type(JsonType.class)
    @Column(name = "overrides", columnDefinition = "jsonb")
    private Map<String, Object> overrides;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false, insertable = false)
    private Instant updatedAt;
}
