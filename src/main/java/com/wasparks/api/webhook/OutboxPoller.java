package com.wasparks.api.webhook;

import com.wasparks.api.entity.ApiOutboxEvent;
import com.wasparks.api.entity.ApiWebhookDelivery;
import com.wasparks.api.entity.ApiWebhookEndpoint;
import com.wasparks.api.enums.DeliveryStatus;
import com.wasparks.api.enums.WebhookEndpointStatus;
import com.wasparks.api.repository.ApiOutboxEventRepository;
import com.wasparks.api.repository.ApiWebhookDeliveryRepository;
import com.wasparks.api.repository.ApiWebhookEndpointRepository;
import com.wasparks.api.util.Uuid7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains {@code api_outbox_events} and fans each event out to the tenant's matching endpoints
 * (epic §B8).
 *
 * <p>This is the seam between the two services. tenants-service inserts an outbox row inside the same
 * transaction as the state change, so an event exists if and only if the thing it describes actually
 * happened — no message can be reported sent that was not, and none that was sent can go unreported.
 * Publishing over a queue instead would have reintroduced exactly that gap.
 *
 * <h2>The apiKeyId filter</h2>
 * tenants-service emits {@code message.*} for <b>every</b> outbound send of an API-enabled tenant,
 * including messages a human typed in the app, with {@code apiKeyId} null for those (amendment 5).
 * Forwarding them all would mean a customer's integration receiving delivery reports for conversations
 * its own staff are having in the inbox — surprising, noisy, and a privacy question nobody asked for. So
 * {@code message.*} is delivered only when {@code payload.apiKeyId} is set. Non-message events
 * (template, account, quota, ping) are tenant-wide facts and are always delivered.
 *
 * <p>Marking {@code published_at} happens in the same transaction as creating the deliveries, so an
 * event is never marked published without its deliveries existing, and a crash mid-batch simply means
 * the next tick redoes the unmarked ones.
 *
 * <h2>The sandbox delivery receipt</h2>
 * A DRYRUN send never reaches a carrier, so no {@code message.delivered} will ever arrive for it. This is
 * where the one it gets is written (epic §0.10, amended): publishing a {@code message.sent} whose wamid
 * starts with {@code DRYRUN-} writes a {@code message.delivered}, post-dated 2s, which the next tick
 * picks up like any other row. Deriving it here rather than at accept time is the point — a send that
 * failed produces no {@code message.sent}, so it can never be followed by a synthetic receipt.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPoller {

    private static final String MESSAGE_EVENT_PREFIX = "message.";
    private static final String PAYLOAD_API_KEY_ID = "apiKeyId";
    private static final String PAYLOAD_WAMID = "wamid";
    /** The wamid tenants-service records for a dry run — the only marker that a send was a sandbox one. */
    private static final String DRYRUN_WAMID_PREFIX = "DRYRUN-";
    /** A receipt is synthesised only if the message has neither of these already. */
    private static final List<String> TERMINAL_AFTER_SENT =
            List.of(WebhookEvents.MESSAGE_DELIVERED, WebhookEvents.MESSAGE_FAILED);

    private final ApiOutboxEventRepository outboxRepository;
    private final ApiWebhookEndpointRepository endpointRepository;
    private final ApiWebhookDeliveryRepository deliveryRepository;
    private final WebhookEventPublisher eventPublisher;

    @Value("${app.webhook.outbox-batch}")
    private int batchSize;

    /**
     * {@code @Transactional} sits on this method as well as on {@link #publishBatch()} because the
     * scheduler reaches {@code poll()} through the proxy while {@code poll()} reaches
     * {@code publishBatch()} directly — a self-invocation, which never passes through a proxy, so the
     * inner annotation alone would leave the {@code FOR UPDATE SKIP LOCKED} claim without a transaction.
     */
    @Scheduled(fixedDelayString = "${app.webhook.outbox-poll-ms}", initialDelay = 5000)
    @SchedulerLock(name = "api-outbox-poller", lockAtMostFor = "PT2M", lockAtLeastFor = "PT1S")
    @Transactional
    public void poll() {
        try {
            int published = publishBatch();
            if (published > 0) {
                log.debug("Published {} outbox events", published);
            }
        } catch (Exception e) {
            log.error("Outbox poll failed", e);
        }
    }

    /** Visible for tests: claim and fan out one batch, returning how many events were published. */
    @Transactional
    public int publishBatch() {
        Instant now = Instant.now();
        List<ApiOutboxEvent> events = outboxRepository.claimUnpublished(now, Limit.of(batchSize));
        if (events.isEmpty()) {
            return 0;
        }

        // One endpoint lookup per tenant per batch, not per event: a burst of status updates for one
        // tenant is the normal shape of this table, and querying per event would turn a 500-row batch
        // into 500 queries.
        Map<UUID, List<ApiWebhookEndpoint>> endpointsByTenant = new HashMap<>();

        for (ApiOutboxEvent event : events) {
            List<ApiWebhookEndpoint> endpoints = endpointsByTenant.computeIfAbsent(
                    event.getTenantId(),
                    tenantId -> endpointRepository.findByTenantIdAndStatus(
                            tenantId, WebhookEndpointStatus.ACTIVE));

            if (shouldDeliver(event)) {
                for (ApiWebhookEndpoint endpoint : endpoints) {
                    if (subscribes(endpoint, event.getEventType())) {
                        createDelivery(endpoint, event, now);
                    }
                }
            }

            // Marked published either way. An event nobody subscribes to is still handled — leaving it
            // unpublished would make the poller re-examine it on every tick forever.
            event.setPublishedAt(now);

            synthesiseSandboxDeliveredIfDue(event);
        }

        outboxRepository.saveAll(events);
        return events.size();
    }

    /**
     * A published DRYRUN {@code message.sent} earns a synthetic {@code message.delivered} (epic §0.10,
     * amended). Written post-dated, so the next tick delivers it 2s later along the ordinary path.
     */
    private void synthesiseSandboxDeliveredIfDue(ApiOutboxEvent event) {
        if (!WebhookEvents.MESSAGE_SENT.equals(event.getEventType()) || !isDryRun(event)) {
            return;
        }
        if (event.getAggregateId() == null) {
            // Nothing to key the receipt or the duplicate guard on. Upstream always sets it for a
            // message event; skipping is the safe answer if that ever stops being true.
            log.warn("DRYRUN message.sent {} has no aggregate id — no delivery receipt synthesised",
                    event.getId());
            return;
        }
        if (outboxRepository.existsByAggregateIdAndEventTypeIn(
                event.getAggregateId(), TERMINAL_AFTER_SENT)) {
            // Already delivered, or already failed. Either way this message is past the point where a
            // receipt would be true.
            return;
        }
        eventPublisher.synthesiseSandboxDelivered(event);
    }

    private boolean isDryRun(ApiOutboxEvent event) {
        Map<String, Object> payload = event.getPayload();
        Object wamid = payload == null ? null : payload.get(PAYLOAD_WAMID);
        return wamid != null && wamid.toString().startsWith(DRYRUN_WAMID_PREFIX);
    }

    /**
     * {@code message.*} only when the send came from an API key (amendment 5). Everything else always.
     */
    private boolean shouldDeliver(ApiOutboxEvent event) {
        if (event.getEventType() == null || !event.getEventType().startsWith(MESSAGE_EVENT_PREFIX)) {
            return true;
        }
        Map<String, Object> payload = event.getPayload();
        Object apiKeyId = payload == null ? null : payload.get(PAYLOAD_API_KEY_ID);
        return apiKeyId != null && !apiKeyId.toString().isBlank();
    }

    private boolean subscribes(ApiWebhookEndpoint endpoint, String eventType) {
        List<String> patterns = endpoint.getEvents();
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        return patterns.stream().anyMatch(pattern -> WebhookEvents.matches(pattern, eventType));
    }

    private void createDelivery(ApiWebhookEndpoint endpoint, ApiOutboxEvent event, Instant now) {
        // Guard against a re-publish creating a second delivery for the same pair. The transaction
        // makes this nearly impossible, but "nearly" here would mean a duplicate webhook at the
        // customer, which is precisely what the delivery log exists to rule out.
        if (deliveryRepository.existsByEndpointIdAndEventId(endpoint.getId(), event.getId())) {
            return;
        }
        deliveryRepository.save(ApiWebhookDelivery.builder()
                .id(Uuid7.generate())
                .endpointId(endpoint.getId())
                .eventId(event.getId())
                .attempt(0)
                .status(DeliveryStatus.PENDING)
                .nextAttemptAt(now)
                .build());
    }
}
