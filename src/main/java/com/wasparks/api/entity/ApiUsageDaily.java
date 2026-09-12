package com.wasparks.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Daily usage rollup — the billing/admin source of truth (epic §B3). Redis counters are the fast path;
 * a 10-minute job flushes them here and the DB row wins on any disagreement.
 *
 * <p><b>The zero UUID is a real value, not a null.</b> The epic wrote the PK as
 * {@code (tenant_id, COALESCE(api_key_id, …), day)}, but PostgreSQL cannot put an expression in a primary
 * key, so 020 made the column NOT NULL with the all-zero UUID as the explicit sentinel for the
 * tenant-wide row. Writing NULL here would violate the constraint; {@link #TENANT_TOTAL} is that
 * sentinel and must be used instead.
 */
@Entity
@Table(name = "api_usage_daily")
@IdClass(ApiUsageDaily.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiUsageDaily {

    /** The sentinel {@code api_key_id} for a tenant-wide row (all zeroes). Never write NULL. */
    public static final UUID TENANT_TOTAL = new UUID(0L, 0L);

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Id
    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Id
    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "requests", nullable = false)
    @Builder.Default
    private Integer requests = 0;

    @Column(name = "rate_limited", nullable = false)
    @Builder.Default
    private Integer rateLimited = 0;

    @Column(name = "messages_accepted", nullable = false)
    @Builder.Default
    private Integer messagesAccepted = 0;

    /**
     * Terminal send outcomes. P1 leaves these at zero: the API service sees an accept, not a delivery,
     * and the nightly reconciliation from {@code messages} that fills them is P1.1 (hand-off §8).
     */
    @Column(name = "messages_sent", nullable = false)
    @Builder.Default
    private Integer messagesSent = 0;

    @Column(name = "messages_failed", nullable = false)
    @Builder.Default
    private Integer messagesFailed = 0;

    @Column(name = "template_creates", nullable = false)
    @Builder.Default
    private Integer templateCreates = 0;

    /** Composite primary key: one row per (tenant, key-or-sentinel, day). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID tenantId;
        private UUID apiKeyId;
        private LocalDate day;
    }
}
