package com.wasparks.api.v1;

import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.RequiredScope;
import com.wasparks.api.entity.ApiWebhookDelivery;
import com.wasparks.api.entity.ApiWebhookEndpoint;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.enums.WebhookEndpointStatus;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.webhook.WebhookEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook endpoint management (epic §B7).
 *
 * <p>The create response is the only time the signing secret is ever returned. Everything else here is
 * ordinary CRUD over the tenant's own endpoints, scoped by the principal so one tenant cannot see or
 * touch another's.
 */
@RestController
@RequestMapping("/v1/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks",
        description = "Register URLs to receive delivery statuses and template updates. Verify the "
                + "X-WaSparks-Signature header on everything you receive.")
public class WebhooksController {

    private final WebhookEndpointService endpointService;

    @Data
    public static class CreateRequest {
        @NotBlank
        private String url;
        /** Event names or globs, e.g. {@code ["message.*", "template.approved"]}. */
        private List<String> events;
    }

    @Data
    public static class UpdateRequest {
        private String url;
        private List<String> events;
        /** {@code ACTIVE} to resume a paused endpoint, {@code DISABLED} to stop delivery. */
        private String status;
    }

    @GetMapping
    @RequiredScope(Scope.WEBHOOKS_MANAGE)
    @PreAuthorize("hasAuthority('SCOPE_webhooks:manage')")
    @Operation(summary = "List webhook endpoints")
    public PagedResponse<Map<String, Object>> list() {
        ApiPrincipal principal = CurrentPrincipal.api();
        List<Map<String, Object>> data = endpointService.list(principal.tenantId()).stream()
                .map(this::toPublic)
                .toList();
        return PagedResponse.of(data, null);
    }

    @PostMapping
    @RequiredScope(Scope.WEBHOOKS_MANAGE)
    @PreAuthorize("hasAuthority('SCOPE_webhooks:manage')")
    @Operation(summary = "Create a webhook endpoint",
            description = """
                    Returns the signing `secret` **once**. Store it now — it is encrypted at rest and
                    cannot be shown again. Use it to verify `X-WaSparks-Signature` on every delivery:
                    the header is `t=<unix>,v1=<hex hmac-sha256(secret, t + "." + rawBody)>`, and you
                    should reject anything whose `t` is more than five minutes old.

                    `url` must be https, except on localhost.""")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest request) {
        ApiPrincipal principal = CurrentPrincipal.api();
        WebhookEndpointService.Created created = endpointService.create(
                principal.tenantId(), null, request.getUrl(), request.getEvents(),
                principal.limits());

        Map<String, Object> body = toPublic(created.endpoint());
        body.put("secret", created.secret());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    @RequiredScope(Scope.WEBHOOKS_MANAGE)
    @PreAuthorize("hasAuthority('SCOPE_webhooks:manage')")
    @Operation(summary = "Get a webhook endpoint")
    public Map<String, Object> get(@PathVariable UUID id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return toPublic(endpointService.get(principal.tenantId(), id));
    }

    @PatchMapping("/{id}")
    @RequiredScope(Scope.WEBHOOKS_MANAGE)
    @PreAuthorize("hasAuthority('SCOPE_webhooks:manage')")
    @Operation(summary = "Update a webhook endpoint",
            description = "Omitted fields are left alone. Setting `status` to ACTIVE resumes an "
                    + "endpoint that was paused after repeated failures and clears its failure count.")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody UpdateRequest request) {
        ApiPrincipal principal = CurrentPrincipal.api();
        WebhookEndpointStatus status = parseStatus(request.getStatus());
        return toPublic(endpointService.update(principal.tenantId(), id, request.getUrl(),
                request.getEvents(), status));
    }

    @DeleteMapping("/{id}")
    @RequiredScope(Scope.WEBHOOKS_MANAGE)
    @PreAuthorize("hasAuthority('SCOPE_webhooks:manage')")
    @Operation(summary = "Delete a webhook endpoint")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        endpointService.delete(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @RequiredScope(Scope.WEBHOOKS_MANAGE)
    @PreAuthorize("hasAuthority('SCOPE_webhooks:manage')")
    @Operation(summary = "Send a test event",
            description = "Queues a `ping` event through the real delivery pipeline — same signature, "
                    + "same retries, same delivery log. It arrives within a few seconds.")
    public ResponseEntity<Map<String, Object>> test(@PathVariable UUID id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        UUID eventId = endpointService.sendTest(principal.tenantId(), id);
        return ResponseEntity.accepted().body(Map.of(
                "eventId", "evt_" + eventId,
                "message", "A ping event has been queued for delivery."));
    }

    @GetMapping("/{id}/deliveries")
    @RequiredScope(Scope.WEBHOOKS_MANAGE)
    @PreAuthorize("hasAuthority('SCOPE_webhooks:manage')")
    @Operation(summary = "List recent deliveries",
            description = "The last 100 attempts for this endpoint, newest first, with the HTTP "
                    + "status we received and the next scheduled retry.")
    public PagedResponse<Map<String, Object>> deliveries(@PathVariable UUID id) {
        ApiPrincipal principal = CurrentPrincipal.api();
        List<Map<String, Object>> data = endpointService.deliveries(principal.tenantId(), id).stream()
                .map(this::toPublic)
                .toList();
        return PagedResponse.of(data, null);
    }

    @PostMapping("/{id}/deliveries/{deliveryId}/retry")
    @RequiredScope(Scope.WEBHOOKS_MANAGE)
    @PreAuthorize("hasAuthority('SCOPE_webhooks:manage')")
    @Operation(summary = "Retry an exhausted delivery",
            description = "Re-queues a delivery that used up its retry schedule. Only EXHAUSTED "
                    + "deliveries can be retried.")
    public Map<String, Object> retry(@PathVariable UUID id, @PathVariable UUID deliveryId) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return toPublic(endpointService.retry(principal.tenantId(), id, deliveryId));
    }

    /** Never includes the secret — that exists only in the create response. */
    private Map<String, Object> toPublic(ApiWebhookEndpoint endpoint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", endpoint.getId().toString());
        body.put("url", endpoint.getUrl());
        body.put("events", endpoint.getEvents());
        body.put("status", endpoint.getStatus().name());
        body.put("consecutiveFailures", endpoint.getConsecutiveFailures());
        body.put("lastSuccessAt", endpoint.getLastSuccessAt());
        body.put("lastFailureAt", endpoint.getLastFailureAt());
        body.put("createdAt", endpoint.getCreatedAt());
        return body;
    }

    private Map<String, Object> toPublic(ApiWebhookDelivery delivery) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", delivery.getId().toString());
        body.put("eventId", "evt_" + delivery.getEventId());
        body.put("status", delivery.getStatus().name());
        body.put("attempt", delivery.getAttempt());
        body.put("responseCode", delivery.getResponseCode());
        body.put("error", delivery.getError());
        body.put("nextAttemptAt", delivery.getNextAttemptAt());
        body.put("deliveredAt", delivery.getDeliveredAt());
        body.put("createdAt", delivery.getCreatedAt());
        return body;
    }

    /**
     * {@code PAUSED} is not accepted from a client. It is a state the dispatcher assigns after repeated
     * failures; a tenant who wants an endpoint to stop should DISABLE it, which says so plainly and does
     * not get silently un-set by a later success.
     */
    private WebhookEndpointStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        WebhookEndpointStatus status;
        try {
            status = WebhookEndpointStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`status` must be ACTIVE or DISABLED.");
        }
        if (status == WebhookEndpointStatus.PAUSED) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "PAUSED is set automatically after repeated failures. Use DISABLED to stop "
                            + "delivery yourself.");
        }
        return status;
    }
}
