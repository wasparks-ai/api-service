package com.wasparks.api.webhook;

import com.wasparks.api.entity.ApiWebhookDelivery;
import com.wasparks.api.entity.ApiWebhookEndpoint;
import com.wasparks.api.enums.WebhookEndpointStatus;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.plans.EffectiveLimits;
import com.wasparks.api.repository.ApiWebhookDeliveryRepository;
import com.wasparks.api.repository.ApiWebhookEndpointRepository;
import com.wasparks.api.util.EncryptionUtil;
import com.wasparks.api.util.Uuid7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Managing a tenant's webhook endpoints (epic §B7, §B8).
 *
 * <p>The signing secret is generated here, shown once in the create response, and stored AES-256-GCM
 * encrypted (shared-contracts §1). Once only, for the same reason as an API key: a secret that can be
 * read back is a secret that lives in whatever the tenant reads it with.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEndpointService {

    /** 32 random bytes, URL-safe Base64 — long enough that brute-forcing an HMAC key is not a plan. */
    private static final int SECRET_BYTES = 32;
    private static final String SECRET_PREFIX = "whsec_";

    /** Deliveries returned by the list endpoint (epic §B7: "last 100"). */
    private static final int DELIVERY_HISTORY = 100;

    private final ApiWebhookEndpointRepository endpointRepository;
    private final ApiWebhookDeliveryRepository deliveryRepository;
    private final WebhookEventPublisher eventPublisher;
    private final EncryptionUtil encryptionUtil;

    private final SecureRandom random = new SecureRandom();

    /** The endpoint plus its plaintext secret, which exists only in the create response. */
    public record Created(ApiWebhookEndpoint endpoint, String secret) {
    }

    @Transactional(readOnly = true)
    public List<ApiWebhookEndpoint> list(UUID tenantId) {
        return endpointRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public ApiWebhookEndpoint get(UUID tenantId, UUID id) {
        return endpointRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "No such webhook endpoint."));
    }

    /**
     * Register an endpoint.
     *
     * <p>{@code partnerId} turns it into a <b>partner endpoint</b> (§B5): one registration that receives
     * every event for every one of that partner's clients, rather than one per client. That is the shape
     * both launch partners asked for — they hold one webhook handler and route on the {@code tenantId}
     * in the payload — and a partner with two hundred clients registering two hundred endpoints would
     * exhaust any sane plan limit anyway.
     *
     * <p>A partner endpoint always has {@code include_ui_sends} true (§0.7), because a partner is the
     * system of record for its client's conversations and a message it cannot see is a gap in its own
     * product. It is set here as well as derived at fan-out, so the column tells the truth to anyone
     * reading the table.
     */
    @Transactional
    public Created create(UUID tenantId, UUID partnerId, UUID createdBy, String url,
                          List<String> events, EffectiveLimits limits) {
        requireDeliverableUrl(url);
        List<String> patterns = requireEvents(events);

        long existing = endpointRepository.countByTenantId(tenantId);
        if (existing >= limits.maxWebhookEndpoints()) {
            throw ApiException.of(ApiErrorCode.PLAN_LIMIT_REACHED,
                            "This plan allows " + limits.maxWebhookEndpoints() + " webhook endpoints.")
                    .withDetail("limit", limits.maxWebhookEndpoints())
                    .withDetail("current", existing);
        }

        String secret = SECRET_PREFIX + newSecret();
        ApiWebhookEndpoint endpoint = ApiWebhookEndpoint.builder()
                .id(Uuid7.generate())
                .tenantId(tenantId)
                .partnerId(partnerId)
                .url(url.trim())
                .secretEncrypted(encryptionUtil.encrypt(secret))
                .events(patterns)
                .status(WebhookEndpointStatus.ACTIVE)
                .includeUiSends(partnerId != null)
                .consecutiveFailures(0)
                .createdBy(createdBy)
                .build();
        return new Created(endpointRepository.save(endpoint), secret);
    }

    /**
     * Update a subset of fields. A null argument means "leave it alone", which is what distinguishes
     * PATCH from PUT and lets a tenant re-enable a paused endpoint without restating its events.
     */
    @Transactional
    public ApiWebhookEndpoint update(UUID tenantId, UUID id, String url, List<String> events,
                                     WebhookEndpointStatus status) {
        ApiWebhookEndpoint endpoint = get(tenantId, id);
        if (url != null) {
            requireDeliverableUrl(url);
            endpoint.setUrl(url.trim());
        }
        if (events != null) {
            endpoint.setEvents(requireEvents(events));
        }
        if (status != null) {
            endpoint.setStatus(status);
            if (status == WebhookEndpointStatus.ACTIVE) {
                // Re-activating clears the failure run. Otherwise an endpoint that was auto-paused at
                // 100 failures would be paused again by its very next failure, and the tenant would
                // have no way to give a fixed URL a clean start.
                endpoint.setConsecutiveFailures(0);
            }
        }
        return endpointRepository.save(endpoint);
    }

    @Transactional
    public void delete(UUID tenantId, UUID id) {
        ApiWebhookEndpoint endpoint = get(tenantId, id);
        // Deliveries cascade (020). The delivery log is only meaningful against an endpoint that
        // exists, and keeping orphans would leave the dispatcher work it can never complete.
        endpointRepository.delete(endpoint);
    }

    /**
     * Send a {@code ping}.
     *
     * <p>Written into the outbox rather than POSTed inline, so it exercises the whole real path —
     * fan-out, signature, retries, delivery log. A test that took a shortcut would prove the shortcut
     * works and tell the tenant nothing about whether their endpoint will receive real events.
     */
    @Transactional
    public UUID sendTest(UUID tenantId, UUID id) {
        ApiWebhookEndpoint endpoint = get(tenantId, id);
        if (endpoint.getStatus() != WebhookEndpointStatus.ACTIVE) {
            throw ApiException.of(ApiErrorCode.CONFLICT,
                    "This endpoint is " + endpoint.getStatus() + " — activate it before testing.");
        }
        return eventPublisher.publishPing(tenantId, endpoint.getId()).getId();
    }

    @Transactional(readOnly = true)
    public List<ApiWebhookDelivery> deliveries(UUID tenantId, UUID id) {
        ApiWebhookEndpoint endpoint = get(tenantId, id);
        return deliveryRepository.findByEndpointIdOrderByCreatedAtDesc(
                endpoint.getId(), Limit.of(DELIVERY_HISTORY));
    }

    /**
     * Retry an exhausted delivery now.
     *
     * <p>Only an EXHAUSTED one: a PENDING delivery already has a scheduled attempt, and letting a tenant
     * re-arm it would just move that attempt around. A SUCCESS is not re-sent, because a duplicate
     * webhook is exactly what the delivery log exists to prevent.
     */
    @Transactional
    public ApiWebhookDelivery retry(UUID tenantId, UUID endpointId, UUID deliveryId) {
        ApiWebhookEndpoint endpoint = get(tenantId, endpointId);
        ApiWebhookDelivery delivery = deliveryRepository.findById(deliveryId)
                .filter(d -> d.getEndpointId().equals(endpoint.getId()))
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "No such delivery."));

        if (delivery.getStatus() != com.wasparks.api.enums.DeliveryStatus.EXHAUSTED) {
            throw ApiException.of(ApiErrorCode.CONFLICT,
                    "Only an EXHAUSTED delivery can be retried; this one is "
                            + delivery.getStatus() + ".");
        }
        delivery.setStatus(com.wasparks.api.enums.DeliveryStatus.PENDING);
        delivery.setAttempt(0);
        delivery.setNextAttemptAt(Instant.now());
        delivery.setError(null);
        return deliveryRepository.save(delivery);
    }

    /**
     * The URL must be one we can actually POST to.
     *
     * <p>{@code https} is required outside development: the payload carries message content and
     * recipient numbers, and a plaintext webhook would put that on the wire. {@code http} is allowed
     * only for loopback, which is what a developer testing locally needs.
     */
    private void requireDeliverableUrl(String url) {
        if (url == null || url.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "`url` is required.");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "`url` is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "`url` must include a host.");
        }
        // An IPv6 literal comes back from URI.getHost() wrapped in brackets ("[::1]"), so strip them
        // before comparing — otherwise a developer pointing at an IPv6 loopback is told to use https.
        String bareHost = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        boolean loopback = "localhost".equalsIgnoreCase(bareHost)
                || bareHost.startsWith("127.")   // the whole 127.0.0.0/8 loopback range
                || "::1".equals(bareHost)
                || "0:0:0:0:0:0:0:1".equals(bareHost);
        if ("https".equals(scheme)) {
            return;
        }
        if ("http".equals(scheme) && loopback) {
            return;
        }
        throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                "`url` must be https (http is accepted only for localhost).");
    }

    private List<String> requireEvents(List<String> events) {
        if (events == null || events.isEmpty()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`events` must list at least one event or pattern, for example [\"message.*\"].");
        }
        for (String pattern : events) {
            if (pattern == null || pattern.isBlank()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "`events` must not contain blank entries.");
            }
        }
        return events.stream().map(String::trim).toList();
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
