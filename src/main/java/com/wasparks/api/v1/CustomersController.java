package com.wasparks.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.auth.ApiKeyService;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.RequiredScope;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.enums.CreatedVia;
import com.wasparks.api.enums.PartnerTenantStatus;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import com.wasparks.api.partner.PartnerCustomerService;
import com.wasparks.api.partner.SetupLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.Set;
import java.util.UUID;

/**
 * A partner's customers (§B2) — <b>partner keys only</b>.
 *
 * <p>This is where the reseller model becomes an API. A partner registers a business, generates a link
 * that lets that business connect its own WhatsApp number, and from then on sends and receives on its
 * behalf by naming it in {@code X-Tenant-Id}. The customer never has a WaSparks account and never sees
 * our product.
 *
 * <p>The customer is addressed <b>in the path</b> here rather than in {@code X-Tenant-Id}: these are
 * operations <em>about</em> a customer rather than operations performed as one, and using the header
 * would make "create a customer" need a header naming the customer it is about to create. Every path id
 * is proved against {@code api_partner_tenants} exactly as the header would be — a customer that is not
 * this partner's is a 404 either way (§0.4).
 */
@RestController
@RequestMapping("/v1/customers")
@RequiredArgsConstructor
@Tag(name = "Customers",
        description = "Register the businesses you resell WhatsApp to, connect their numbers, and set "
                + "their limits. Partner keys only.")
public class CustomersController {

    private final PartnerCustomerService customerService;
    private final SetupLinkService setupLinkService;

    @Data
    public static class CreateRequest {
        @NotBlank
        @Size(min = 2, max = 150)
        private String name;
        /**
         * Your own id for this customer. Required — it is what a retried create is matched on, so a
         * request that times out and is sent again returns the same customer instead of a second one.
         */
        @NotBlank
        @Size(max = 128)
        private String externalRef;
        @Size(max = 255)
        private String contactEmail;
        @Size(max = 50)
        private String contactPhone;
        private Integer messagesPerDayCap;
    }

    @Data
    public static class UpdateRequest {
        @Size(max = 150)
        private String name;
        private Integer messagesPerDayCap;
        /** Remove the cap entirely. Cannot be combined with `messagesPerDayCap`. */
        private Boolean clearCap;
        /** `ACTIVE` or `SUSPENDED`. */
        private String status;
    }

    @Data
    public static class SetupLinkRequest {
        @NotBlank
        private String successUrl;
        @NotBlank
        private String failureUrl;
        /** Defaults to 168 (seven days). */
        private Integer expiresInHours;
    }

    @PostMapping
    @RequiredScope(Scope.CUSTOMERS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_customers:write')")
    @Operation(summary = "Register a customer",
            description = """
                    Creates a WaSparks tenant owned by you. The customer gets no login and no email —
                    it exists so that its number, conversations and campaigns have somewhere to live.

                    **Send `externalRef`.** It is your own id for this customer and it is what makes this
                    call idempotent: a retry with the same `externalRef` returns the customer you already
                    created, with `200` instead of `201`.

                    The customer has no WhatsApp number yet. Generate a setup link next.""")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest request) {
        ApiPrincipal principal = CurrentPrincipal.api();
        PartnerCustomerService.CreateResult result = customerService.create(principal,
                new PartnerCustomerService.CreateRequest(request.getName(), request.getExternalRef(),
                        request.getContactEmail(), request.getContactPhone(),
                        request.getMessagesPerDayCap(), CreatedVia.API));

        // 201 for a create, 200 for a replay — the difference a partner needs in order to tell "I made
        // this now" from "I had already made it", without having to compare timestamps.
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.customer());
    }

    @GetMapping
    @RequiredScope(Scope.CUSTOMERS_READ)
    @PreAuthorize("hasAuthority('SCOPE_customers:read')")
    @Operation(summary = "List your customers",
            description = "Newest first, with each customer's numbers, cap and usage so far today.")
    public PagedResponse<Map<String, Object>> list() {
        return PagedResponse.of(customerService.list(CurrentPrincipal.api()), null);
    }

    @GetMapping("/{id}")
    @RequiredScope(Scope.CUSTOMERS_READ)
    @PreAuthorize("hasAuthority('SCOPE_customers:read')")
    @Operation(summary = "Get a customer")
    public Map<String, Object> get(@PathVariable UUID id) {
        return customerService.get(CurrentPrincipal.api(), id);
    }

    @PatchMapping("/{id}")
    @RequiredScope(Scope.CUSTOMERS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_customers:write')")
    @Operation(summary = "Update a customer",
            description = """
                    Change the display name, the daily cap or the status.

                    `SUSPENDED` stops this customer immediately: sends and campaigns on its behalf are
                    refused with `403 customer_suspended`. It stays visible here and you can re-activate
                    it at any time.

                    `messagesPerDayCap` is your own ceiling for this customer and can never raise
                    anything — the effective limit is the lower of it and what is left in your pool. Send
                    `clearCap: true` to remove it.""")
    public Map<String, Object> update(@PathVariable UUID id,
                                      @Valid @RequestBody UpdateRequest request) {
        return customerService.update(CurrentPrincipal.api(), id,
                new PartnerCustomerService.UpdateRequest(request.getName(),
                        request.getMessagesPerDayCap(), request.getClearCap(),
                        parseStatus(request.getStatus())));
    }

    // ------------------------------------------------------------------ setup links

    @PostMapping("/{id}/setup-links")
    @RequiredScope(Scope.CUSTOMERS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_customers:write')")
    @Operation(summary = "Generate a setup link",
            description = """
                    Returns a URL to send your customer. They open it, see your branding, connect their
                    own WhatsApp number through Meta's Embedded Signup, and are redirected to your
                    `successUrl` with `?customer_id=&phone_number_id=&status=connected`.

                    **The URL is returned once.** Only a hash of its token is stored, so it cannot be
                    read back — generate another if it is lost.

                    Generating a link **cancels the customer's previous pending one**. Two live links
                    would be two ways to connect the same number with no way to tell which was used.

                    You learn the outcome from the `customer.connected` webhook as well as from the
                    redirect, which matters because the customer may close the tab before it lands.""")
    public ResponseEntity<Map<String, Object>> createSetupLink(
            @PathVariable UUID id, @Valid @RequestBody SetupLinkRequest request) {
        ApiPrincipal principal = CurrentPrincipal.api();
        Map<String, Object> link = proxy(() -> setupLinkService.create(principal, id,
                request.getSuccessUrl(), request.getFailureUrl(), request.getExpiresInHours()));
        return ResponseEntity.status(HttpStatus.CREATED).body(link);
    }

    @GetMapping("/{id}/setup-links")
    @RequiredScope(Scope.CUSTOMERS_READ)
    @PreAuthorize("hasAuthority('SCOPE_customers:read')")
    @Operation(summary = "List a customer's setup links",
            description = "Newest first. The URLs are not included — they exist only in the response "
                    + "that created them.")
    public PagedResponse<Map<String, Object>> listSetupLinks(@PathVariable UUID id) {
        return PagedResponse.of(setupLinkService.list(CurrentPrincipal.api(), id), null);
    }

    @GetMapping("/{id}/setup-links/{linkId}")
    @RequiredScope(Scope.CUSTOMERS_READ)
    @PreAuthorize("hasAuthority('SCOPE_customers:read')")
    @Operation(summary = "Get a setup link's status",
            description = "`PENDING`, `COMPLETED` (with the number that was connected), `EXPIRED` or "
                    + "`CANCELLED`.")
    public Map<String, Object> getSetupLink(@PathVariable UUID id, @PathVariable UUID linkId) {
        return setupLinkService.get(CurrentPrincipal.api(), id, linkId);
    }

    @PostMapping("/{id}/setup-links/{linkId}/cancel")
    @RequiredScope(Scope.CUSTOMERS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_customers:write')")
    @Operation(summary = "Cancel a setup link",
            description = "Only a `PENDING` link can be cancelled; anything else is a 409.")
    public Map<String, Object> cancelSetupLink(@PathVariable UUID id, @PathVariable UUID linkId) {
        ApiPrincipal principal = CurrentPrincipal.api();
        return proxy(() -> setupLinkService.cancel(principal, id, linkId));
    }

    // ------------------------------------------------------------------ numbers

    @PostMapping("/{id}/phone-numbers")
    @RequiredScope(Scope.CUSTOMERS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_customers:write')")
    @Operation(summary = "Connect a number directly",
            description = """
                    The alternative to a setup link, for a customer that already has a WhatsApp Business
                    Account and can give you a system-user access token for it.

                    Before anything is stored we check three things with Meta, using the token you sent:
                    that the token can read the number, that the number belongs to the WABA you named,
                    and — the one that catches most attempts — that the WABA has actually granted the
                    WaSparks app access. A number that could send but never receive a reply is worse
                    than no number, so we never store one.

                    A failure comes back as `422 waba_not_shared`, `422 number_not_in_waba` or
                    `401 token_invalid`. The first carries a `docsUrl` to the guide that explains how
                    the customer shares its WABA with us.

                    The token is encrypted the moment it is stored and is never returned or logged.""")
    public ResponseEntity<JsonNode> mapPhoneNumber(@PathVariable UUID id,
                                                   @RequestBody JsonNode body) {
        ApiPrincipal principal = CurrentPrincipal.api();
        requireMappingBody(body);
        JsonNode mapped = proxy(() -> customerService.mapPhoneNumber(principal, id, body));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapped);
    }

    @GetMapping("/{id}/phone-numbers")
    @RequiredScope(Scope.CUSTOMERS_READ)
    @PreAuthorize("hasAuthority('SCOPE_customers:read')")
    @Operation(summary = "List a customer's numbers")
    public PagedResponse<Object> listPhoneNumbers(@PathVariable UUID id) {
        Map<String, Object> customer = customerService.get(CurrentPrincipal.api(), id);
        @SuppressWarnings("unchecked")
        List<Object> numbers = (List<Object>) customer.get("phoneNumbers");
        return PagedResponse.of(numbers, null);
    }

    // ------------------------------------------------------------------ client keys

    @Data
    public static class ClientKeyRequest {
        @NotBlank
        @Size(max = 100)
        private String name;
        /** `LIVE` or `TEST`. Defaults to LIVE. */
        private String mode;
        /** Omit for every scope. */
        private List<String> scopes;
    }

    @PostMapping("/{id}/keys")
    @RequiredScope(Scope.CUSTOMERS_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_customers:write')")
    @Operation(summary = "Issue a key for a customer",
            description = """
                    Creates an ordinary `wsk_live_` key scoped to one customer, for when you want to hand
                    a client a credential of its own rather than acting for it yourself.

                    Returned **once**, like any key.

                    It behaves exactly as a normal WaSparks API key: no `X-Tenant-Id`, and it runs on the
                    customer's own plan rather than drawing on your pool. If you want your pooled limits,
                    use your partner key with `X-Tenant-Id` instead.

                    It counts against **your** key allowance, not the customer's.""")
    public ResponseEntity<Map<String, Object>> issueClientKey(
            @PathVariable UUID id, @Valid @RequestBody ClientKeyRequest request) {
        ApiPrincipal principal = CurrentPrincipal.api();
        ApiKeyService.IssuedKey issued = customerService.issueClientKey(principal, id,
                request.getName().trim(), parseMode(request.getMode()),
                request.getScopes() == null ? null : Set.copyOf(request.getScopes()), null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", issued.key().getId().toString());
        body.put("customerId", id.toString());
        body.put("name", issued.key().getName());
        body.put("prefix", issued.key().getPrefix());
        body.put("mode", issued.key().getMode().name());
        body.put("scopes", issued.key().getScopes());
        body.put("key", issued.plaintext());
        body.put("warning", "This is the only time the key is shown. Store it now.");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}/keys")
    @RequiredScope(Scope.CUSTOMERS_READ)
    @PreAuthorize("hasAuthority('SCOPE_customers:read')")
    @Operation(summary = "List a customer's keys",
            description = "Prefixes only — the keys themselves exist only in the response that created "
                    + "them.")
    public PagedResponse<Map<String, Object>> listClientKeys(@PathVariable UUID id) {
        List<Map<String, Object>> keys = customerService.clientKeys(CurrentPrincipal.api(), id)
                .stream()
                .map(key -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", key.getId().toString());
                    body.put("name", key.getName());
                    body.put("prefix", key.getPrefix());
                    body.put("mode", key.getMode().name());
                    body.put("status", key.getStatus().name());
                    body.put("lastUsedAt", key.getLastUsedAt());
                    body.put("createdAt", key.getCreatedAt());
                    return body;
                })
                .toList();
        return PagedResponse.of(keys, null);
    }

    // ------------------------------------------------------------------ helpers

    private ApiKeyMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ApiKeyMode.LIVE;
        }
        try {
            return ApiKeyMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "`mode` must be LIVE or TEST.");
        }
    }

    /**
     * The three fields the direct-map body must carry, checked here so that a missing one is a
     * validation error naming the field rather than an upstream Graph call that fails confusingly.
     *
     * <p>The token is <b>never</b> echoed back into the error message, and there is no log line for this
     * body anywhere on the path.
     */
    private void requireMappingBody(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Send `phoneNumberId`, `wabaId` and `accessToken`.");
        }
        for (String field : List.of("phoneNumberId", "wabaId", "accessToken")) {
            JsonNode value = body.get(field);
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "`" + field + "` is required.");
            }
        }
    }

    private PartnerTenantStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PartnerTenantStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`status` must be ACTIVE or SUSPENDED.");
        }
    }

    private <T> T proxy(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }
}
