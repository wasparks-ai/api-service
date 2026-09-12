package com.wasparks.api.webhook;

import com.wasparks.api.entity.ApiOutboxEvent;
import com.wasparks.api.repository.ApiOutboxEventRepository;
import com.wasparks.api.util.Uuid7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the outbox rows this service originates.
 *
 * <p>Almost everything in {@code api_outbox_events} is inserted by tenants-service inside the
 * transaction that changed the state (epic §0.13). Three kinds of event have no such transaction
 * upstream because they are facts about the API itself, and they are written here:
 *
 * <ul>
 *   <li><b>the sandbox {@code message.delivered}</b> — a DRYRUN send never reaches Meta, so no carrier
 *       receipt will ever arrive for it. tenants-service reports the send itself, so only the delivery
 *       receipt is missing and only that is invented (epic §0.10, amended);</li>
 *   <li><b>{@code ping}</b> — the test button in tenant-web;</li>
 *   <li><b>{@code quota.exceeded}</b> — a WARN-plan tenant going past its allowance (epic §B3).</li>
 * </ul>
 *
 * <p>All three go into the same table and travel the same poller, fan-out, signing and retry path as a
 * real event. Giving synthetic events a shortcut would mean the sandbox exercised different code from
 * production, which is the one thing a sandbox must not do.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEventPublisher {

    private final ApiOutboxEventRepository outboxRepository;

    @Value("${app.webhook.sandbox.delivered-delay-ms}")
    private long sandboxDeliveredDelayMs;

    /**
     * Synthesise the sandbox {@code message.delivered} that follows a DRYRUN {@code message.sent}.
     *
     * <p><b>Only {@code delivered}, and only from a real {@code sent}</b> (epic §0.10, amended
     * 2026-09-12). The original design synthesised {@code SENT → DELIVERED} at accept time, on the
     * premise that nothing upstream reports a dry run. internal.md made that premise false: the DRYRUN
     * branch writes an outbox event like any other send. The live walk caught both consequences — a
     * duplicated {@code message.sent}, and, because accept-time events cannot be recalled, a
     * {@code sent} + {@code delivered} pair arriving <em>after</em> a {@code message.failed} for a
     * message that never went anywhere. Deriving this from the published {@code sent} fixes both: there
     * is nothing to duplicate, and a send that failed never produces a {@code sent} to derive from.
     *
     * <p>The event is <b>post-dated</b> rather than scheduled: the poller only claims rows whose
     * {@code created_at} has arrived, so writing it 2s ahead delivers it at the right moment with no
     * timer, no in-memory state, and no way to lose it to a restart in between.
     *
     * <p>The payload is the {@code sent} payload with its status changed, so every other field —
     * {@code wamid}, {@code clientRef}, {@code apiKeyId}, the timestamps — is upstream's own and a client
     * cannot tell the synthetic receipt from a real one by its shape.
     */
    @Transactional
    public ApiOutboxEvent synthesiseSandboxDelivered(ApiOutboxEvent sentEvent) {
        Map<String, Object> payload = new LinkedHashMap<>(sentEvent.getPayload());
        payload.put("status", "DELIVERED");

        ApiOutboxEvent delivered = write(sentEvent.getTenantId(), WebhookEvents.MESSAGE_DELIVERED,
                WebhookEvents.AGGREGATE_MESSAGE, sentEvent.getAggregateId(), payload,
                Instant.now().plus(Duration.ofMillis(sandboxDeliveredDelayMs)));

        log.debug("Synthesised sandbox message.delivered for message {}", sentEvent.getAggregateId());
        return delivered;
    }

    /** The {@code ping} behind {@code POST /v1/webhooks/{id}/test}. */
    @Transactional
    public ApiOutboxEvent publishPing(UUID tenantId, UUID endpointId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("endpointId", endpointId.toString());
        payload.put("message", "This is a test event from WaSparks.");
        return write(tenantId, WebhookEvents.PING, WebhookEvents.AGGREGATE_WEBHOOK, endpointId,
                payload, Instant.now());
    }

    /** A WARN-plan tenant went past its quota. Fired at most once a day by {@code QuotaInterceptor}. */
    @Transactional
    public void publishQuotaExceeded(UUID tenantId, String scope, int limit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scope);
        payload.put("limit", limit);
        payload.put("policy", "WARN");
        write(tenantId, WebhookEvents.QUOTA_EXCEEDED, WebhookEvents.AGGREGATE_TENANT, tenantId,
                payload, Instant.now());
    }

    private ApiOutboxEvent write(UUID tenantId, String eventType, String aggregateType, UUID aggregateId,
                                 Map<String, Object> payload, Instant createdAt) {
        ApiOutboxEvent event = ApiOutboxEvent.builder()
                .id(Uuid7.generate())
                .tenantId(tenantId)
                .eventType(eventType)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .payload(payload)
                .createdAt(createdAt)
                .build();
        return outboxRepository.save(event);
    }
}
