package com.wasparks.api.webhook;

import com.wasparks.api.entity.ApiOutboxEvent;
import com.wasparks.api.entity.ApiWebhookDelivery;
import com.wasparks.api.entity.ApiWebhookEndpoint;
import com.wasparks.api.enums.DeliveryStatus;
import com.wasparks.api.enums.WebhookEndpointStatus;
import com.wasparks.api.repository.ApiOutboxEventRepository;
import com.wasparks.api.repository.ApiWebhookDeliveryRepository;
import com.wasparks.api.partner.PartnerTenantResolver;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * <p>{@code message.received} is exempt: an inbound message has no {@code apiKeyId} — nobody on our side
 * sent it — so applying the filter would have suppressed every reply a customer ever wrote, which is the
 * single most valuable event on the API.
 *
 * <p>An endpoint with {@code include_ui_sends} set opts back in to the whole stream (021 §7). It is
 * forced true on partner endpoints (§0.7): a partner whose client has {@code app_access} on is the system
 * of record for that conversation, and a message it cannot see is a gap in its own product.
 *
 * <h2>Partner fan-out (api-partner epic §B5)</h2>
 * Every event now reaches two sets of endpoints: the tenant's own, as in P1, and the endpoints of the
 * <b>partner that owns that tenant</b>, if any. The partner is resolved here at fan-out rather than
 * stamped on the event upstream — the epic offers both and picks this one, because it is one cached
 * lookup per tenant per batch instead of a column on every row of a table that only ever grows.
 *
 * <p>{@code partnerId} is also written into the payload on the way out, so a partner receiving events for
 * a hundred clients can route them without a lookup of its own. tenants-service already sets it on the
 * events it knows are partner-related ({@code campaign.*}, {@code customer.*}); this fills it in for the
 * rest, and never overwrites a value upstream supplied.
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
    private static final String PAYLOAD_PARTNER_ID = "partnerId";
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
    private final PartnerTenantResolver partnerTenantResolver;

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
        // into 500 queries. The partner lookups are memoised over the same batch for the same reason.
        Map<UUID, List<ApiWebhookEndpoint>> endpointsByTenant = new HashMap<>();
        Map<UUID, Optional<UUID>> partnerByTenant = new HashMap<>();

        for (ApiOutboxEvent event : events) {
            Optional<UUID> partnerId = partnerByTenant.computeIfAbsent(
                    event.getTenantId(), partnerTenantResolver::partnerOf);
            partnerId.ifPresent(id -> stampPartnerId(event, id));

            List<ApiWebhookEndpoint> endpoints = endpointsByTenant.computeIfAbsent(
                    event.getTenantId(),
                    tenantId -> recipients(tenantId, partnerId));

            for (ApiWebhookEndpoint endpoint : endpoints) {
                if (shouldDeliver(event, endpoint) && subscribes(endpoint, event.getEventType())) {
                    createDelivery(endpoint, event, now);
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
     * The endpoints an event may reach: the tenant's own, plus its partner's if it has one.
     *
     * <p>De-duplicated by id, because a partner's endpoints are filed under the partner's own tenant and
     * would otherwise be returned twice for an event about that tenant — which would be a duplicate
     * webhook at the customer, the one thing the delivery log exists to prevent.
     */
    private List<ApiWebhookEndpoint> recipients(UUID tenantId, Optional<UUID> partnerId) {
        List<ApiWebhookEndpoint> endpoints = new ArrayList<>(
                endpointRepository.findByTenantIdAndStatus(tenantId, WebhookEndpointStatus.ACTIVE));
        partnerId.ifPresent(id -> {
            Set<UUID> seen = endpoints.stream().map(ApiWebhookEndpoint::getId)
                    .collect(Collectors.toSet());
            for (ApiWebhookEndpoint endpoint
                    : endpointRepository.findByPartnerIdAndStatus(id, WebhookEndpointStatus.ACTIVE)) {
                if (seen.add(endpoint.getId())) {
                    endpoints.add(endpoint);
                }
            }
        });
        return endpoints;
    }

    /**
     * Whether this endpoint gets this event.
     *
     * <p>Only outbound {@code message.*} is filtered, and only for an endpoint that has not opted in.
     * {@code message.received} is inbound and carries no {@code apiKeyId} by nature, so filtering it
     * would have silenced every customer reply; the other message events carry one when a key sent them
     * and null when a human did.
     */
    private boolean shouldDeliver(ApiOutboxEvent event, ApiWebhookEndpoint endpoint) {
        String type = event.getEventType();
        if (type == null || !type.startsWith(MESSAGE_EVENT_PREFIX)
                || WebhookEvents.MESSAGE_RECEIVED.equals(type)) {
            return true;
        }
        if (includesUiSends(endpoint)) {
            return true;
        }
        Map<String, Object> payload = event.getPayload();
        Object apiKeyId = payload == null ? null : payload.get(PAYLOAD_API_KEY_ID);
        return apiKeyId != null && !apiKeyId.toString().isBlank();
    }

    /**
     * A partner endpoint always sees UI sends, whatever the column says (§0.7).
     *
     * <p>Derived rather than trusted, because the column is only forced true at create time and a row
     * written before this epic — or by a future admin tool — would otherwise silently drop half of a
     * partner's message stream. The flag remains meaningful for an ordinary tenant that wants the same.
     */
    private boolean includesUiSends(ApiWebhookEndpoint endpoint) {
        return endpoint.getPartnerId() != null || endpoint.isIncludeUiSends();
    }

    /**
     * Put the partner id on the event so a partner can route it without a lookup.
     *
     * <p>Never overwrites: tenants-service already sets {@code partnerId} on the events it resolves
     * itself ({@code campaign.*}, {@code customer.*}, {@code message.received}), and its value is the one
     * taken inside the transaction that made the change. This only fills the gap on the rest.
     */
    private void stampPartnerId(ApiOutboxEvent event, UUID partnerId) {
        Map<String, Object> payload = event.getPayload();
        if (payload == null) {
            return;
        }
        Object existing = payload.get(PAYLOAD_PARTNER_ID);
        if (existing == null || existing.toString().isBlank()) {
            payload.put(PAYLOAD_PARTNER_ID, partnerId.toString());
        }
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
