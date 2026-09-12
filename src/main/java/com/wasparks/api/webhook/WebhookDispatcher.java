package com.wasparks.api.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.entity.ApiOutboxEvent;
import com.wasparks.api.entity.ApiWebhookDelivery;
import com.wasparks.api.entity.ApiWebhookEndpoint;
import com.wasparks.api.enums.DeliveryStatus;
import com.wasparks.api.enums.WebhookEndpointStatus;
import com.wasparks.api.repository.ApiOutboxEventRepository;
import com.wasparks.api.repository.ApiWebhookDeliveryRepository;
import com.wasparks.api.repository.ApiWebhookEndpointRepository;
import com.wasparks.api.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Delivers due webhooks and manages the retry schedule (epic §B8).
 *
 * <h2>Retry schedule</h2>
 * 10s, 1m, 5m, 30m, 2h, then {@code EXHAUSTED}. Roughly three hours of attempts spread over widening
 * gaps — long enough to ride out a deploy or a restart at the customer's end, short enough at the start
 * that a momentary blip costs ten seconds rather than an hour.
 *
 * <h2>Auto-pause</h2>
 * 100 consecutive failures pauses the endpoint. A URL that has failed a hundred times in a row is not
 * having a bad minute; it has been decommissioned, and continuing to POST to it wastes our scheduler and
 * may be hitting something that now belongs to someone else. A success anywhere in the run resets the
 * counter, so an endpoint that is merely flaky is never paused.
 *
 * <p><b>Known gap:</b> pausing should also email the tenant owner (epic §B8). That is a P1.1 follow-up —
 * it needs the admin-service {@code EmailService} contract and a new {@code noreply} template. Until
 * then a pause is logged at warn level and visible in tenant-web, but nobody is told.
 *
 * <h2>Why the JDK HTTP client</h2>
 * These are calls to arbitrary customer-controlled URLs. The JDK client does not follow redirects by
 * default, which is what we want — an open redirect at a customer's host should not turn our signed POST
 * into a request somewhere else.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatcher {

    private final ApiWebhookDeliveryRepository deliveryRepository;
    private final ApiWebhookEndpointRepository endpointRepository;
    private final ApiOutboxEventRepository outboxRepository;
    private final WebhookSigner signer;
    private final EncryptionUtil encryptionUtil;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.webhook.dispatch-batch}")
    private int batchSize;

    @Value("${app.webhook.timeout-ms}")
    private long timeoutMs;

    @Value("${app.webhook.retry-backoff-ms}")
    private String retryBackoffMs;

    @Value("${app.webhook.pause-after-failures}")
    private int pauseAfterFailures;

    private HttpClient httpClient;

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }
        return httpClient;
    }

    @Scheduled(fixedDelayString = "${app.webhook.dispatch-poll-ms}", initialDelay = 7000)
    @SchedulerLock(name = "api-webhook-dispatcher", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void dispatch() {
        try {
            int delivered = dispatchBatch();
            if (delivered > 0) {
                log.debug("Attempted {} webhook deliveries", delivered);
            }
        } catch (Exception e) {
            log.error("Webhook dispatch failed", e);
        }
    }

    /** Visible for tests: attempt every currently due delivery, returning how many were attempted. */
    public int dispatchBatch() {
        List<ApiWebhookDelivery> due = claimDue();
        for (ApiWebhookDelivery delivery : due) {
            try {
                attempt(delivery);
            } catch (Exception e) {
                log.error("Delivery {} threw unexpectedly", delivery.getId(), e);
            }
        }
        return due.size();
    }

    /**
     * Claim the due batch in its own short transaction.
     *
     * <p>{@code TransactionTemplate} rather than {@code @Transactional}, because this is called from a
     * method on the same bean and a self-invocation never passes through the proxy — the annotation
     * would be silently inert and the {@code FOR UPDATE SKIP LOCKED} in the query would fail for want of
     * a transaction. Being explicit here also keeps the transaction tightly around the claim and well
     * clear of the HTTP calls that follow.
     */
    private List<ApiWebhookDelivery> claimDue() {
        return transactionTemplate.execute(status ->
                deliveryRepository.claimDue(DeliveryStatus.PENDING, Instant.now(),
                        Limit.of(batchSize)));
    }

    /**
     * One attempt at one delivery.
     *
     * <p>The HTTP call is deliberately outside any transaction: it can take the full 10-second timeout,
     * and holding a database connection open for that long across a batch would exhaust the pool long
     * before the customer's endpoint recovered.
     */
    public void attempt(ApiWebhookDelivery delivery) {
        Optional<ApiWebhookEndpoint> maybeEndpoint =
                endpointRepository.findById(delivery.getEndpointId());
        Optional<ApiOutboxEvent> maybeEvent = outboxRepository.findById(delivery.getEventId());

        if (maybeEndpoint.isEmpty() || maybeEvent.isEmpty()) {
            // The endpoint or event was deleted under us. Nothing to deliver and nothing to retry.
            delivery.setStatus(DeliveryStatus.EXHAUSTED);
            delivery.setErrorTruncated("The endpoint or event no longer exists.");
            delivery.setNextAttemptAt(null);
            deliveryRepository.save(delivery);
            return;
        }

        ApiWebhookEndpoint endpoint = maybeEndpoint.get();
        ApiOutboxEvent event = maybeEvent.get();

        if (endpoint.getStatus() != WebhookEndpointStatus.ACTIVE) {
            // Paused or disabled between fan-out and now. Stop rather than deliver to an endpoint the
            // tenant has switched off.
            delivery.setStatus(DeliveryStatus.EXHAUSTED);
            delivery.setErrorTruncated("The endpoint is " + endpoint.getStatus() + ".");
            delivery.setNextAttemptAt(null);
            deliveryRepository.save(delivery);
            return;
        }

        String body = renderBody(event);
        int attempt = delivery.getAttempt() == null ? 0 : delivery.getAttempt();
        delivery.setAttempt(attempt + 1);

        try {
            String secret = encryptionUtil.decrypt(endpoint.getSecretEncrypted());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.getUrl()))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(WebhookSigner.HEADER_EVENT, event.getEventType())
                    .header(WebhookSigner.HEADER_DELIVERY, delivery.getId().toString())
                    .header(WebhookSigner.HEADER_SIGNATURE, signer.sign(secret, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response =
                    httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            delivery.setResponseCode(status);

            if (status >= 200 && status < 300) {
                succeed(delivery, endpoint);
            } else {
                fail(delivery, endpoint, "HTTP " + status);
            }
        } catch (Exception e) {
            delivery.setResponseCode(null);
            fail(delivery, endpoint, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        deliveryRepository.save(delivery);
        endpointRepository.save(endpoint);
    }

    private void succeed(ApiWebhookDelivery delivery, ApiWebhookEndpoint endpoint) {
        Instant now = Instant.now();
        delivery.setStatus(DeliveryStatus.SUCCESS);
        delivery.setDeliveredAt(now);
        delivery.setNextAttemptAt(null);
        delivery.setError(null);
        endpoint.setConsecutiveFailures(0);
        endpoint.setLastSuccessAt(now);
    }

    private void fail(ApiWebhookDelivery delivery, ApiWebhookEndpoint endpoint, String reason) {
        Instant now = Instant.now();
        List<Long> schedule = retrySchedule();
        int attempt = delivery.getAttempt();

        delivery.setErrorTruncated(reason);
        endpoint.setLastFailureAt(now);
        endpoint.setConsecutiveFailures(
                (endpoint.getConsecutiveFailures() == null ? 0 : endpoint.getConsecutiveFailures()) + 1);

        if (attempt > schedule.size()) {
            delivery.setStatus(DeliveryStatus.EXHAUSTED);
            delivery.setNextAttemptAt(null);
            log.warn("Webhook delivery {} exhausted after {} attempts to {}: {}",
                    delivery.getId(), attempt, endpoint.getUrl(), reason);
        } else {
            delivery.setStatus(DeliveryStatus.PENDING);
            delivery.setNextAttemptAt(now.plusMillis(schedule.get(attempt - 1)));
        }

        if (endpoint.getConsecutiveFailures() >= pauseAfterFailures
                && endpoint.getStatus() == WebhookEndpointStatus.ACTIVE) {
            endpoint.setStatus(WebhookEndpointStatus.PAUSED);
            // P1.1: this should also email the tenant owner via the admin-service EmailService
            // contract (epic §B8). Until that ships, the pause is only visible here and in tenant-web.
            log.warn("PAUSED webhook endpoint {} ({}) after {} consecutive failures. "
                            + "The tenant has NOT been emailed — that notification is a P1.1 follow-up.",
                    endpoint.getId(), endpoint.getUrl(), endpoint.getConsecutiveFailures());
        }
    }

    /**
     * The event envelope (epic §B8): {@code {"id","type","created_at","data"}}.
     *
     * <p>{@code id} is prefixed {@code evt_} so a support conversation can tell an event id from a
     * delivery id from a message id at a glance.
     */
    String renderBody(ApiOutboxEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "evt_" + event.getId());
        body.put("type", event.getEventType());
        body.put("created_at", event.getCreatedAt().toString());
        body.put("data", event.getPayload());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not render webhook body", e);
        }
    }

    private List<Long> retrySchedule() {
        try {
            return Arrays.stream(retryBackoffMs.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException e) {
            log.warn("Unparseable webhook retry schedule '{}', using the documented default",
                    retryBackoffMs);
            return List.of(10_000L, 60_000L, 300_000L, 1_800_000L, 7_200_000L);
        }
    }
}
