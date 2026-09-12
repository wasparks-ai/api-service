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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The transactional outbox (epic §0.13, §C2). tenants-service INSERTs a row in the same transaction as
 * the state change it describes; this service reads unpublished rows and sets {@code published_at}.
 * <b>Those are the only two writers, and they never touch the same columns.</b>
 *
 * <p>One exception to "tenants-service inserts": the sandbox synthesiser (§0.10) writes its own
 * {@code message.sent} / {@code message.delivered} rows for TEST-mode sends, and
 * {@code POST /v1/webhooks/{id}/test} writes a {@code ping}, so a synthetic event travels the exact same
 * fan-out, signing and retry path as a real one rather than a shortcut beside it.
 */
@Entity
@Table(name = "api_outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiOutboxEvent {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** {@code message.sent}, {@code template.approved}, {@code account.token_expired}, {@code ping}, … */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** {@code message} | {@code template} | {@code account} (plus {@code webhook} for a ping). */
    @Column(name = "aggregate_type", nullable = false, length = 32)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Type(JsonType.class)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> payload = new HashMap<>();

    /**
     * Insertable so the sandbox synthesiser can post-date a row: a synthetic {@code message.delivered}
     * is written with {@code created_at} 4s in the future and the poller, which orders by and filters on
     * this column, simply does not see it until then. No second scheduler, no sleeping thread.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** NULL until this service has fanned the event out to the tenant's endpoints. */
    @Column(name = "published_at")
    private Instant publishedAt;
}
