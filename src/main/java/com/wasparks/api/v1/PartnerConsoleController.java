package com.wasparks.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.auth.ApiKeyService;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.TenantUserPrincipal;
import com.wasparks.api.entity.ApiKey;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.enums.CreatedVia;
import com.wasparks.api.enums.PartnerTenantStatus;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import com.wasparks.api.partner.CustomerSettingsService;
import com.wasparks.api.partner.PartnerConsoleService;
import com.wasparks.api.partner.PartnerCustomerService;
import com.wasparks.api.partner.SetupLinkService;
import com.wasparks.api.repository.ApiKeyRepository;
import com.wasparks.api.webhook.WebhookEndpointService;
import com.wasparks.api.webhook.WebhookEvents;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Partner console backend (§B6) — the same operations as {@code /v1/customers}, authenticated by the
 * partner's ordinary tenant-web session rather than by a partner key.
 *
 * <p>It exists for the bootstrap reason {@code /v1/keys} exists: a partner has to be able to create its
 * first partner key, and it cannot authenticate with a key it does not have. Beyond that first key, it is
 * simply the better place for a human to do this work — a browser holding a partner key would be a
 * partner key in an XSS payload, and a partner key is the most powerful credential in the estate.
 *
 * <p>Every handler resolves the partner first and 404s if the tenant is not one, so the whole section is
 * invisible to the rest of the platform. There are no scope annotations: a session token carries no
 * scopes, and the JWT chain has already established that the caller is an OWNER or ADMIN.
 */
@RestController
@RequestMapping("/v1/partner")
@RequiredArgsConstructor
@Tag(name = "Partner console",
        description = "The backend for the Partner section of WaSparks. Authenticated with your "
                + "WaSparks session, not with an API key.")
@SecurityRequirement(name = "TenantJwt")
public class PartnerConsoleController {

    private final PartnerConsoleService consoleService;
    private final PartnerCustomerService customerService;
    private final SetupLinkService setupLinkService;
    private final WebhookEndpointService endpointService;
    private final InternalTenantsClient tenantsClient;
    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;
    private final CustomerSettingsService settingsService;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ identity

    @GetMapping
    @Operation(summary = "Am I a partner?",
            description = "Your partner record — name, slug, branding, client count — or `404 "
                    + "not_a_partner`. WaSparks hides the Partner section entirely on that 404.")
    public Map<String, Object> me() {
        PartnerConsoleService.Console console = console();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partner", console.partner());
        body.putAll(consoleService.planAndPool(console));
        return body;
    }

    // ------------------------------------------------------------------ customers

    @GetMapping("/customers")
    @Operation(summary = "List customers")
    public PagedResponse<Map<String, Object>> customers() {
        return PagedResponse.of(customerService.list(console().principal()), null);
    }

    @GetMapping("/customers/{id}")
    @Operation(summary = "Get a customer")
    public Map<String, Object> customer(@PathVariable UUID id) {
        return customerService.get(console().principal(), id);
    }

    @PostMapping("/customers")
    @Operation(summary = "Create a customer",
            description = "Identical to `POST /v1/customers`, recorded as created through the console "
                    + "rather than through the API.")
    public ResponseEntity<Map<String, Object>> createCustomer(
            @Valid @RequestBody CustomersController.CreateRequest request) {
        PartnerCustomerService.CreateResult result = customerService.create(console().principal(),
                new PartnerCustomerService.CreateRequest(request.getName(), request.getExternalRef(),
                        request.getContactEmail(), request.getContactPhone(),
                        request.getMessagesPerDayCap(), CreatedVia.CONSOLE));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.customer());
    }

    @PatchMapping("/customers/{id}")
    @Operation(summary = "Update a customer")
    public Map<String, Object> updateCustomer(@PathVariable UUID id,
                                              @Valid @RequestBody CustomersController.UpdateRequest request) {
        return customerService.update(console().principal(), id,
                new PartnerCustomerService.UpdateRequest(request.getName(),
                        request.getMessagesPerDayCap(), request.getClearCap(),
                        parseStatus(request.getStatus())));
    }

    // ------------------------------------------------------------------ connecting numbers

    @PostMapping("/customers/{id}/setup-links")
    @Operation(summary = "Generate a setup link",
            description = "Returns the URL once. Generating one cancels the customer's previous "
                    + "pending link.")
    public ResponseEntity<Map<String, Object>> createSetupLink(
            @PathVariable UUID id, @Valid @RequestBody CustomersController.SetupLinkRequest request) {
        ApiPrincipal principal = console().principal();
        return ResponseEntity.status(HttpStatus.CREATED).body(proxy(() ->
                setupLinkService.create(principal, id, request.getSuccessUrl(),
                        request.getFailureUrl(), request.getExpiresInHours())));
    }

    @GetMapping("/customers/{id}/setup-links")
    @Operation(summary = "List a customer's setup links")
    public PagedResponse<Map<String, Object>> setupLinks(@PathVariable UUID id) {
        return PagedResponse.of(setupLinkService.list(console().principal(), id), null);
    }

    @PostMapping("/customers/{id}/setup-links/{linkId}/cancel")
    @Operation(summary = "Cancel a setup link")
    public Map<String, Object> cancelSetupLink(@PathVariable UUID id, @PathVariable UUID linkId) {
        ApiPrincipal principal = console().principal();
        return proxy(() -> setupLinkService.cancel(principal, id, linkId));
    }

    @PostMapping("/customers/{id}/phone-numbers")
    @Operation(summary = "Connect a number directly",
            description = "The three Meta checks run exactly as on the API path; a WABA that has not "
                    + "been shared with WaSparks comes back as `422 waba_not_shared` with a link to "
                    + "the guide.")
    public ResponseEntity<JsonNode> mapPhoneNumber(@PathVariable UUID id,
                                                   @RequestBody JsonNode body) {
        ApiPrincipal principal = console().principal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(proxy(() -> customerService.mapPhoneNumber(principal, id, body)));
    }

    // ------------------------------------------------------------------ the frequency guard

    @Data
    public static class FrequencyGuardRequest {
        /** Days between marketing templates to the same number. `0` turns the guard off. */
        private Integer minDaysBetweenMarketing;
    }

    @PatchMapping("/customers/{id}/settings")
    @Operation(summary = "Set a customer's marketing frequency guard",
            description = """
                    `minDaysBetweenMarketing` makes campaign creation skip recipients who already got a
                    MARKETING template from this customer within that many days, reporting them as
                    `counts.frequencySkipped`. `0` (the default) is off.

                    It protects the customer's quality rating when the same list is sent to repeatedly,
                    which is exactly the pattern partners use. The guard **fails open**: if it cannot be
                    evaluated the campaign sends to everyone, because a customer unable to send at all is
                    worse than one that over-sends once.""")
    public Map<String, Object> setFrequencyGuard(@PathVariable UUID id,
                                                 @RequestBody FrequencyGuardRequest request) {
        ApiPrincipal principal = console().principal();
        Integer days = request.getMinDaysBetweenMarketing();
        if (days == null || days < 0 || days > 365) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`minDaysBetweenMarketing` must be between 0 and 365.");
        }
        ApiPrincipal acting = customerService.actingOn(principal, id);
        proxy(() -> tenantsClient.updateTenantSettings(acting,
                Map.of("minDaysBetweenMarketing", days)));
        // The customer list reads this back through a cache; drop it now so the next screen shows what
        // was just set rather than what it replaced.
        settingsService.invalidate(id);
        return Map.of("customerId", id.toString(), "minDaysBetweenMarketing", days);
    }

    // ------------------------------------------------------------------ keys

    @Data
    public static class PartnerKeyRequest {
        @NotBlank
        @Size(max = 100)
        private String name;
        /** `LIVE` or `TEST`. Defaults to LIVE. */
        private String mode;
        private List<String> scopes;
    }

    @GetMapping("/keys")
    @Operation(summary = "List partner keys",
            description = "Your own keys. Client keys are listed per customer under "
                    + "`/v1/partner/customers/{id}/keys`.")
    public PagedResponse<Map<String, Object>> keys() {
        PartnerConsoleService.Console console = console();
        List<Map<String, Object>> keys = new ArrayList<>();
        for (ApiKey key : apiKeyRepository.findByTenantIdAndUiSessionFalseOrderByCreatedAtDesc(
                console.principal().partner().ownerTenantId())) {
            keys.add(toPublic(key));
        }
        return PagedResponse.of(keys, null);
    }

    @PostMapping("/keys")
    @Operation(summary = "Create a partner key",
            description = """
                    Returns the key **once** — it is stored only as a hash.

                    A partner key is prefixed `wsk_partner_live_` (or `wsk_partner_test_`) and acts on any
                    of your customers by naming one in `X-Tenant-Id`. Omit the header and it acts on your
                    own tenant.

                    Keep it server-side. It can send on behalf of every customer you have.""")
    public ResponseEntity<Map<String, Object>> createKey(
            @Valid @RequestBody PartnerKeyRequest request) {
        PartnerConsoleService.Console console = console();
        TenantUserPrincipal user = CurrentPrincipal.tenantUser();

        ApiKeyService.IssuedKey issued = apiKeyService.issuePartnerKey(
                console.principal().partner().ownerTenantId(), console.partnerId(), user.userId(),
                request.getName().trim(), parseMode(request.getMode()),
                request.getScopes() == null ? null : Set.copyOf(request.getScopes()));

        Map<String, Object> body = toPublic(issued.key());
        body.put("key", issued.plaintext());
        body.put("warning", "This is the only time the key is shown. Store it now.");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/keys/{id}")
    @Operation(summary = "Revoke a partner key",
            description = "Immediate — the cached lookup is evicted as the key is revoked.")
    public ResponseEntity<Void> revokeKey(@PathVariable UUID id) {
        PartnerConsoleService.Console console = console();
        apiKeyService.revoke(console.principal().partner().ownerTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/customers/{id}/keys")
    @Operation(summary = "List a customer's client keys")
    public PagedResponse<Map<String, Object>> clientKeys(@PathVariable UUID id) {
        List<Map<String, Object>> keys = customerService.clientKeys(console().principal(), id)
                .stream().map(this::toPublic).toList();
        return PagedResponse.of(keys, null);
    }

    @PostMapping("/customers/{id}/keys")
    @Operation(summary = "Issue a client key",
            description = "An ordinary key scoped to that one customer. Counts against your key "
                    + "allowance, not theirs.")
    public ResponseEntity<Map<String, Object>> issueClientKey(
            @PathVariable UUID id, @Valid @RequestBody CustomersController.ClientKeyRequest request) {
        ApiKeyService.IssuedKey issued = customerService.issueClientKey(console().principal(), id,
                request.getName().trim(), parseMode(request.getMode()),
                request.getScopes() == null ? null : Set.copyOf(request.getScopes()), null);

        Map<String, Object> body = toPublic(issued.key());
        body.put("customerId", id.toString());
        body.put("key", issued.plaintext());
        body.put("warning", "This is the only time the key is shown. Store it now.");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ------------------------------------------------------------------ webhooks

    @GetMapping("/webhooks")
    @Operation(summary = "List partner webhook endpoints")
    public PagedResponse<Map<String, Object>> webhooks() {
        PartnerConsoleService.Console console = console();
        List<Map<String, Object>> endpoints = endpointService
                .list(console.principal().partner().ownerTenantId()).stream()
                .map(endpoint -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", endpoint.getId().toString());
                    body.put("url", endpoint.getUrl());
                    body.put("events", endpoint.getEvents());
                    body.put("status", endpoint.getStatus().name());
                    body.put("scope", endpoint.getPartnerId() == null ? "TENANT" : "PARTNER");
                    body.put("consecutiveFailures", endpoint.getConsecutiveFailures());
                    body.put("lastSuccessAt", endpoint.getLastSuccessAt());
                    body.put("lastFailureAt", endpoint.getLastFailureAt());
                    body.put("createdAt", endpoint.getCreatedAt());
                    return body;
                })
                .toList();
        // The picker's vocabulary travels with the list, so the console renders the event checkboxes
        // without a second call — and can never offer an event this build does not actually deliver.
        return new PagedResponse<>(endpoints,
                Map.of("availableEvents", WebhookEvents.SUBSCRIBABLE));
    }

    @PostMapping("/webhooks")
    @Operation(summary = "Register a partner webhook endpoint",
            description = """
                    One endpoint for **every** customer you have. Each delivery carries `tenantId` and
                    `partnerId`, so you route on the payload rather than registering per customer.

                    Partner endpoints receive messages your customers' staff send from the WaSparks inbox
                    as well as the ones you sent over the API — you are the system of record for those
                    conversations, so a message you could not see would be a gap in your own product.

                    The signing `secret` is returned once.""")
    public ResponseEntity<Map<String, Object>> createWebhook(
            @Valid @RequestBody WebhooksController.CreateRequest request) {
        PartnerConsoleService.Console console = console();
        TenantUserPrincipal user = CurrentPrincipal.tenantUser();

        WebhookEndpointService.Created created = endpointService.create(
                console.principal().partner().ownerTenantId(), console.partnerId(), user.userId(),
                request.getUrl(), request.getEvents(), console.principal().limits());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", created.endpoint().getId().toString());
        body.put("url", created.endpoint().getUrl());
        body.put("events", created.endpoint().getEvents());
        body.put("status", created.endpoint().getStatus().name());
        body.put("scope", "PARTNER");
        body.put("secret", created.secret());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/webhooks/{id}")
    @Operation(summary = "Delete a partner webhook endpoint")
    public ResponseEntity<Void> deleteWebhook(@PathVariable UUID id) {
        PartnerConsoleService.Console console = console();
        endpointService.delete(console.principal().partner().ownerTenantId(), id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ usage and campaigns

    @GetMapping("/usage")
    @Operation(summary = "Usage per customer",
            description = "`?month=YYYY-MM`, defaulting to the current UTC month — the same bucketing "
                    + "the invoice export uses, so the two agree.")
    public Map<String, Object> usage(@RequestParam(required = false) String month) {
        YearMonth period = parseMonth(month);
        return consoleService.usage(console(), period.atDay(1), period.atEndOfMonth());
    }

    @GetMapping("/campaigns")
    @Operation(summary = "Campaigns across your customers",
            description = """
                    Read-only. You build your own campaign UI — this is here so that support can answer
                    "did that go out?" without asking you to look.

                    With no `customerId` you get **every** customer's campaigns merged into one list,
                    newest first, each row carrying `customerId` and `customerName` so the table can name
                    who it belongs to. Suspended customers are left out — their campaigns are not running
                    and cannot be started.

                    Pass `customerId` to narrow to one customer, or `customerId=self` for your own
                    account. Your own tenant has no customer id of its own, which is what `self` is for.""")
    public Object campaigns(@RequestParam(required = false) String customerId,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size) {
        PartnerConsoleService.Console console = console();
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        if (status != null && !status.isBlank()) {
            query.add("status", status);
        }
        if (page != null) {
            query.add("page", String.valueOf(page));
        }
        query.add("size", String.valueOf(PagedResponse.clampLimit(size)));

        UUID selected = consoleService.resolveCustomerSelector(console, customerId);
        if (selected == null) {
            return consoleService.campaignsAcross(console, query);
        }

        // A named customer goes through the ordinary per-tenant read, which re-proves the link — the
        // merged list proves it differently (it only ever asks for tenants it has already resolved), and
        // both paths have to prove it somehow.
        ApiPrincipal acting = selected.equals(console.principal().partner().ownerTenantId())
                ? console.principal()
                : customerService.actingOn(console.principal(), selected);
        return proxy(() -> tenantsClient.listCampaigns(acting.tenantId(), acting.keyId(), query));
    }

    @GetMapping("/campaigns/{id}/recipients")
    @Operation(summary = "A campaign's recipients",
            description = """
                    Every recipient with its outcome, **including the ones that were not sent to** —
                    filter with `status=SUPPRESSED`, `SKIPPED_FREQUENCY` or `INVALID` to see exactly who
                    and why, which is the usual reason support opens this screen.

                    The campaign must be yours or one of your customers'.

                    Pass the `customerId` from the campaigns list. Without it we work out which of your
                    customers the campaign belongs to, which costs a lookup per customer — fine for a
                    pasted id, wasteful for a screen that already knows.

                    `cursor` comes from `meta.next_cursor` on the previous page; omit it for the first.""")
    public PagedResponse<Object> campaignRecipients(
            @PathVariable String id,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        PartnerConsoleService.Console console = console();
        int size = PagedResponse.clampLimit(limit);
        int page = decodeCursor(cursor);

        UUID owner = consoleService.resolveCustomerSelector(console, customerId);
        ApiPrincipal acting = owner == null
                ? consoleService.findCampaignOwner(console, id)
                : ownerPrincipal(console, owner);

        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        if (status != null && !status.isBlank()) {
            query.add("status", status);
        }
        query.add("page", String.valueOf(page));
        query.add("size", String.valueOf(size));

        JsonNode upstream = proxy(() -> tenantsClient.campaignRecipients(
                acting.tenantId(), acting.keyId(), id, query));
        return recipientsPage(upstream, page, size);
    }

    // ------------------------------------------------------------------ helpers

    private PartnerConsoleService.Console console() {
        return consoleService.resolve(CurrentPrincipal.tenantUser());
    }

    /** The partner's own tenant needs no link check; anything else is re-proved. */
    private ApiPrincipal ownerPrincipal(PartnerConsoleService.Console console, UUID tenantId) {
        return tenantId.equals(console.principal().partner().ownerTenantId())
                ? console.principal()
                : customerService.actingOn(console.principal(), tenantId);
    }

    /**
     * The cursor is an opaque encoding of a page number.
     *
     * <p>Upstream pages this list by offset, and the {@code /v1} envelope promises a cursor
     * ({@code PagedResponse}) — so one of the two has to be translated, and translating here keeps the
     * public contract cursor-shaped while nothing upstream changes. It is base64 so that it reads as an
     * opaque token rather than inviting a caller to do arithmetic on it: the encoding is ours to change
     * the day this list gets a real keyset cursor.
     */
    private int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()),
                    StandardCharsets.UTF_8);
            int page = Integer.parseInt(decoded);
            if (page < 0) {
                throw new NumberFormatException(decoded);
            }
            return page;
        } catch (RuntimeException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`cursor` is not one we issued. Omit it to start from the first page.");
        }
    }

    private String encodeCursor(int page) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(page).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Upstream's page into the house envelope, with a {@code next_cursor} only when there is more.
     *
     * <p>"More" is decided from the row count rather than from a total, because a total is the one field
     * a paged response cannot be relied on to carry. A full page means there may be another; a short one
     * is the end. The cost is a single empty last page in the exact-multiple case, which a client that
     * loops until {@code next_cursor} is absent handles without noticing.
     */
    private PagedResponse<Object> recipientsPage(JsonNode upstream, int page, int size) {
        List<Object> rows = new ArrayList<>();
        JsonNode content = upstream == null ? null
                : (upstream.isArray() ? upstream : firstArray(upstream, "content", "data",
                        "recipients"));
        if (content != null) {
            content.forEach(row -> rows.add(objectMapper.convertValue(row, Object.class)));
        }
        String next = rows.size() >= size ? encodeCursor(page + 1) : null;
        return PagedResponse.of(rows, next);
    }

    private JsonNode firstArray(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isArray()) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Object> toPublic(ApiKey key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", key.getId().toString());
        body.put("name", key.getName());
        body.put("prefix", key.getPrefix());
        body.put("mode", key.getMode().name());
        body.put("scopes", key.getScopes());
        body.put("status", key.getStatus().name());
        body.put("partner", key.getPartnerId() != null);
        body.put("lastUsedAt", key.getLastUsedAt());
        body.put("expiresAt", key.getExpiresAt());
        body.put("revokedAt", key.getRevokedAt());
        body.put("createdAt", key.getCreatedAt());
        return body;
    }

    /** Defaults to the current UTC month, matching how {@code api_usage_daily.day} is bucketed. */
    private YearMonth parseMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return YearMonth.from(LocalDate.now(java.time.ZoneOffset.UTC));
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "`month` must be YYYY-MM.");
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
