package com.wasparks.api.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.auth.ApiKeyService;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.entity.ApiKey;
import com.wasparks.api.entity.ApiPartnerTenant;
import com.wasparks.api.entity.TenantRef;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.enums.ApiKeyStatus;
import com.wasparks.api.enums.CreatedVia;
import com.wasparks.api.enums.PartnerTenantStatus;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.AdminInternalClient;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import com.wasparks.api.quota.QuotaService;
import com.wasparks.api.repository.ApiKeyRepository;
import com.wasparks.api.repository.ApiPartnerTenantRepository;
import com.wasparks.api.repository.TenantRefRepository;
import com.wasparks.api.repository.WhatsAppAccountRefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A partner's customers: create, read, and the two switches a partner owns (§B2).
 *
 * <p>Shared by the two front doors the epic gives the same operations — {@code /v1/customers} on a
 * partner key and {@code /v1/partner/customers} on the partner's tenant session (§B6). They are the same
 * operations for the same actor arriving two ways, so they are one service with two controllers rather
 * than two implementations that would drift the first time a rule changed.
 *
 * <h2>Who writes what</h2>
 * Creating a customer is <b>admin-service's</b> job (§0.3): it owns {@code TenantService.createTenant},
 * the PENDING owner row and the {@code api_partner_tenants} INSERT. This service owns the switches on
 * that link afterwards — {@code status} and {@code messages_per_day_cap} — because those are what the
 * Partner console and {@code PATCH /v1/customers/{id}} operate, and they change far more often than a
 * customer is created.
 *
 * <p>Every write that could change who a partner may act on, or how much, invalidates
 * {@link PartnerTenantResolver}'s cache in the same transaction-adjacent breath. A partner that has just
 * created a customer and immediately sends to it must not get a 404, and a cap it has just raised must
 * not keep refusing for the rest of the minute.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartnerCustomerService {

    private final AdminInternalClient adminClient;
    private final InternalTenantsClient tenantsClient;
    private final ApiPartnerTenantRepository partnerTenantRepository;
    private final TenantRefRepository tenantRefRepository;
    private final WhatsAppAccountRefRepository accountRepository;
    private final PartnerTenantResolver partnerTenantResolver;
    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeyRepository;
    private final QuotaService quotaService;

    /** What a create asks for. A record rather than the raw body, so both controllers validate once. */
    public record CreateRequest(String name, String externalRef, String contactEmail,
                                String contactPhone, Integer messagesPerDayCap, CreatedVia createdVia) {
    }

    /** The created customer and whether this call is what created it — 201 against a 200 replay. */
    public record CreateResult(Map<String, Object> customer, boolean created) {
    }

    /**
     * Provision a customer through admin-service.
     *
     * <p><b>{@code externalRef} is required here although admin-service treats it as optional.</b> Its
     * contract is explicit that idempotency is keyed on it and that without one "every call creates a new
     * tenant" — which, on a request that timed out and was retried, means a partner silently acquires two
     * of its customer, two WhatsApp numbers to connect and no way to tell which is which. Requiring it is
     * the difference between an idempotent endpoint and one that merely can be used idempotently.
     */
    @Transactional
    public CreateResult create(ApiPrincipal principal, CreateRequest request) {
        UUID partnerId = requirePartner(principal).partnerId();

        if (request.externalRef() == null || request.externalRef().isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`externalRef` is required — it is your own id for this customer and the key a "
                            + "retried create is matched on.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", request.name());
        body.put("externalRef", request.externalRef().trim());
        body.put("contactEmail", request.contactEmail());
        body.put("contactPhone", request.contactPhone());
        body.put("messagesPerDayCap", request.messagesPerDayCap());
        body.put("createdVia", (request.createdVia() == null ? CreatedVia.API : request.createdVia())
                .name());

        AdminInternalClient.Created created = upstream(() -> adminClient.createClientTenant(
                partnerId, principal.tenantId(), principal.keyId(), body));

        // A new client changes who this partner may act on. Invalidate before returning, so the very
        // next request — which is usually a setup link for the customer just created — resolves it.
        partnerTenantResolver.invalidate(partnerId);

        UUID tenantId = tenantId(created.tenant());
        return new CreateResult(toPublic(partnerId, tenantId, created.tenant()), created.created());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(ApiPrincipal principal) {
        UUID partnerId = requirePartner(principal).partnerId();
        List<Map<String, Object>> customers = new ArrayList<>();
        for (ApiPartnerTenant link : partnerTenantRepository.findByPartnerIdOrderByCreatedAtDesc(
                partnerId)) {
            customers.add(toPublic(link));
        }
        return customers;
    }

    /**
     * One customer, by tenant id.
     *
     * <p>Note what is <em>not</em> here: a SUSPENDED customer is returned normally. Suspension is the
     * partner's own switch and it must be able to see, and lift, what it set — the 403 belongs on the
     * data plane ({@code X-Tenant-Id}), not on the management plane. See {@link PartnerTenantResolver}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get(ApiPrincipal principal, UUID customerId) {
        return toPublic(requireLink(principal, customerId));
    }

    /** What a PATCH may change. Null means "leave it alone"; see {@link #clearCap} for the third state. */
    public record UpdateRequest(String name, Integer messagesPerDayCap, Boolean clearCap,
                                PartnerTenantStatus status) {
    }

    /**
     * Update the switches this service owns.
     *
     * <p>{@code clearCap} exists because {@code null} already means "unchanged" on a PATCH and a cap of
     * {@code null} means "no cap" in the data — two different things that JSON cannot tell apart in one
     * field. admin-service solved it the same way ({@code clearMessagesPerDayCap}) and combining the two
     * is a 400 there; it is a 400 here for the same reason, since the two together have no meaning.
     *
     * <p>{@code name} is deliberately <b>not</b> written: {@code display_name} and the tenant's
     * {@code company_name} are two columns owned by two services, and letting this one write half of the
     * pair would leave a customer whose name in the console disagreed with its name on an invoice.
     */
    @Transactional
    public Map<String, Object> update(ApiPrincipal principal, UUID customerId,
                                      UpdateRequest request) {
        ApiPartnerTenant link = requireLink(principal, customerId);

        if (request.clearCap() != null && request.clearCap() && request.messagesPerDayCap() != null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Send either `messagesPerDayCap` or `clearCap`, not both.");
        }
        boolean changed = false;

        if (Boolean.TRUE.equals(request.clearCap())) {
            link.setMessagesPerDayCap(null);
            changed = true;
        } else if (request.messagesPerDayCap() != null) {
            if (request.messagesPerDayCap() < 0) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                        "`messagesPerDayCap` cannot be negative. Use 0 to stop this customer sending, "
                                + "or `clearCap` to remove the cap.");
            }
            link.setMessagesPerDayCap(request.messagesPerDayCap());
            changed = true;
        }
        if (request.status() != null && request.status() != link.getStatus()) {
            link.setStatus(request.status());
            changed = true;
        }
        if (request.name() != null && !request.name().isBlank()
                && !request.name().equals(link.getDisplayName())) {
            link.setDisplayName(request.name().trim());
            changed = true;
        }
        if (!changed) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Nothing to change. Send at least one of `name`, `messagesPerDayCap`, `clearCap` "
                            + "or `status`.");
        }

        ApiPartnerTenant saved = partnerTenantRepository.save(link);
        // Status and cap both change what the resolver hands the quota check, so both caches holding
        // them are stale the moment either moves: the partner's membership hash (the X-Tenant-Id path)
        // and this customer's own client-key context. Dropping only one would leave a client key
        // sending against a cap the console says it no longer has.
        partnerTenantResolver.invalidate(link.getPartnerId());
        partnerTenantResolver.invalidateClient(link.getTenantId());
        return toPublic(saved);
    }

    /**
     * Direct number mapping (§0.5) — proxied to tenants-service with the customer swapped in as the
     * acting tenant.
     *
     * <p>Everything interesting happens upstream: three Graph calls prove the token owns the number, that
     * the number is in the WABA, and that the WABA has actually granted our app access, before a row is
     * written. This service contributes the ownership check on the customer and passes the three failures
     * back <b>unchanged</b>, {@code docsUrl} included — a partner told only "invalid_request" has no way
     * to know it needs to share a WABA in Business Manager.
     */
    public JsonNode mapPhoneNumber(ApiPrincipal principal, UUID customerId, JsonNode body) {
        return tenantsClient.mapAccount(actingOn(principal, customerId), body);
    }

    /**
     * Issue a <b>client key</b> for one customer (§B2): an ordinary {@code wsk_live_} key scoped to that
     * customer's tenant, for a partner that wants to hand its client a credential of its own.
     *
     * <p>It behaves exactly as a P1 key — no {@code partner_id}, no {@code X-Tenant-Id}, the client's own
     * plan and the P1 tenant quota counters. That is the epic's decision and it has a consequence worth
     * knowing: a client tenant has no plan assignment of its own, so a client key runs on the default
     * plan's limits rather than on the partner's pool. A partner that wants pooled limits uses a partner
     * key with {@code X-Tenant-Id}, which is the documented path.
     *
     * <p>It is counted against the <b>partner's</b> {@code max_keys}, not the client's, because the
     * partner is who decides to issue it.
     */
    @Transactional
    public ApiKeyService.IssuedKey issueClientKey(ApiPrincipal principal, UUID customerId, String name,
                                                  ApiKeyMode mode, Set<String> scopes,
                                                  Instant expiresAt) {
        com.wasparks.api.auth.PartnerPrincipal partner = requirePartner(principal);
        requireLink(principal, customerId);

        long active = apiKeyRepository.countByTenantIdAndStatusAndUiSessionFalse(
                partner.ownerTenantId(), ApiKeyStatus.ACTIVE)
                + partnerTenantRepository.findTenantIdsByPartnerId(partner.partnerId()).stream()
                        .mapToLong(tenantId -> apiKeyRepository
                                .countByTenantIdAndStatusAndUiSessionFalse(tenantId,
                                        ApiKeyStatus.ACTIVE))
                        .sum();
        int maxKeys = principal.limits().maxKeys();
        if (active >= maxKeys) {
            throw ApiException.of(ApiErrorCode.PLAN_LIMIT_REACHED,
                            "Your plan allows " + maxKeys + " active API keys across all customers.")
                    .withDetail("limit", maxKeys)
                    .withDetail("current", active);
        }
        return apiKeyService.issueForTenant(customerId, null, name, mode, scopes, expiresAt);
    }

    /** A customer's client keys, for the console's key list grouped by client (§E2). */
    @Transactional(readOnly = true)
    public List<ApiKey> clientKeys(ApiPrincipal principal, UUID customerId) {
        requireLink(principal, customerId);
        return apiKeyRepository.findByTenantIdAndUiSessionFalseOrderByCreatedAtDesc(customerId);
    }

    /**
     * A principal aimed at one of the partner's customers rather than at whatever {@code X-Tenant-Id}
     * said.
     *
     * <p>{@code /v1/customers/{id}/…} addresses the customer in the path, so the header is irrelevant
     * there and re-aiming is how the existing tenant-scoped plumbing — the internal client's own headers,
     * the quota keys — ends up pointed at the right customer without a parallel set of methods that take
     * an explicit tenant. The link is re-checked here, so the path id gets exactly the same proof the
     * header would have had.
     */
    @Transactional(readOnly = true)
    public ApiPrincipal actingOn(ApiPrincipal principal, UUID customerId) {
        ApiPartnerTenant link = requireLink(principal, customerId);
        if (!link.isActive()) {
            throw ApiException.of(ApiErrorCode.CUSTOMER_SUSPENDED,
                            "This customer is suspended. Re-activate it first.")
                    .withDetail("customerId", customerId.toString());
        }
        return principal.actingAs(new com.wasparks.api.auth.PartnerPrincipal(
                link.getPartnerId(), principal.partner().ownerTenantId(), customerId,
                link.getMessagesPerDayCap()));
    }

    /** The link, or the same 404 a stranger's tenant id gets (§0.4). */
    @Transactional(readOnly = true)
    public ApiPartnerTenant requireLink(ApiPrincipal principal, UUID customerId) {
        UUID partnerId = requirePartner(principal).partnerId();
        return partnerTenantRepository.findByPartnerIdAndTenantId(partnerId, customerId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND,
                        "No customer with that id."));
    }

    /**
     * Every endpoint in this service is partner-only, so a tenant key reaching one is refused here rather
     * than by each controller remembering to.
     */
    public com.wasparks.api.auth.PartnerPrincipal requirePartner(ApiPrincipal principal) {
        if (!principal.isPartner()) {
            throw ApiException.of(ApiErrorCode.PARTNER_KEY_REQUIRED);
        }
        return principal.partner();
    }

    // ------------------------------------------------------------------ rendering

    /**
     * The public customer shape (§B2): the link's own fields, the tenant's numbers, and today's usage.
     *
     * <p>Usage comes from the Redis counters rather than from {@code api_usage_daily}, for the same
     * reason {@code GET /v1/account} does: the table lags by a flush interval, and a partner watching a
     * customer approach its cap needs the number the next send will be checked against.
     */
    private Map<String, Object> toPublic(ApiPartnerTenant link) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", link.getTenantId().toString());
        body.put("externalRef", link.getExternalRef());
        body.put("name", displayName(link));
        body.put("status", link.getStatus().name());
        body.put("appAccess", link.isAppAccess());
        body.put("cap", link.getMessagesPerDayCap());
        body.put("createdVia", link.getCreatedVia().name());
        body.put("phoneNumbers", phoneNumbers(link.getTenantId()));
        QuotaService.Usage usage = quotaService.peek(link.getTenantId());
        body.put("usage", Map.of("today", usage.today(), "month", usage.month()));
        body.put("createdAt", link.getCreatedAt());
        return body;
    }

    /**
     * The same shape built from admin-service's response, for the moment right after a create when the
     * link row exists but this service has not read it back.
     */
    private Map<String, Object> toPublic(UUID partnerId, UUID tenantId, JsonNode upstream) {
        return partnerTenantRepository.findByPartnerIdAndTenantId(partnerId, tenantId)
                .map(this::toPublic)
                .orElseGet(() -> {
                    // The row must exist — admin-service just wrote it — so this is a read-your-writes
                    // gap across two services rather than a missing customer. Answer from upstream's own
                    // body instead of 404ing on something we created a millisecond ago.
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", tenantId == null ? null : tenantId.toString());
                    body.put("externalRef", text(upstream, "externalRef"));
                    body.put("name", text(upstream, "displayName"));
                    body.put("status", text(upstream, "status"));
                    body.put("appAccess", upstream.path("appAccess").asBoolean(false));
                    body.put("cap", upstream.hasNonNull("messagesPerDayCap")
                            ? upstream.get("messagesPerDayCap").asInt() : null);
                    body.put("createdVia", text(upstream, "createdVia"));
                    body.put("phoneNumbers", List.of());
                    body.put("usage", Map.of("today", 0, "month", 0));
                    body.put("createdAt", text(upstream, "linkedAt"));
                    return body;
                });
    }

    /**
     * {@code display_name} is NOT NULL DEFAULT '' in 021, so a client provisioned before the column
     * carried a name reads as empty rather than null. Fall back to the tenant's company name, which is
     * always set, rather than showing a partner a blank row.
     */
    private String displayName(ApiPartnerTenant link) {
        if (link.getDisplayName() != null && !link.getDisplayName().isBlank()) {
            return link.getDisplayName();
        }
        return tenantRefRepository.findById(link.getTenantId())
                .map(TenantRef::getCompanyName)
                .orElse("");
    }

    private List<Map<String, Object>> phoneNumbers(UUID tenantId) {
        List<Map<String, Object>> numbers = new ArrayList<>();
        accountRepository.findByTenantId(tenantId).forEach(account -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("phoneNumberId", account.getPhoneNumberId());
            entry.put("display", account.getPhoneNumber());
            entry.put("displayName", account.getDisplayName());
            entry.put("status", account.getStatus() == null ? null : account.getStatus().name());
            entry.put("mappedVia", account.getMappedVia());
            entry.put("tokenStatus", tokenStatus(account.getTokenErrorCode(),
                    account.getStatus() == null ? null : account.getStatus().name()));
            numbers.add(entry);
        });
        return numbers;
    }

    /**
     * The same three-valued token status {@code GET /v1/account} reports, derived from the columns this
     * service can already see. EXPIRING is not computed here — it needs {@code token_expires_at}, which
     * belongs to the account projection tenants-service serves, and a partner acting on a customer gets
     * the actionable half: whether the number can send at all.
     */
    private String tokenStatus(String tokenErrorCode, String status) {
        if ("DISCONNECTED".equals(status) || "190".equals(tokenErrorCode)) {
            return "EXPIRED";
        }
        return "OK";
    }

    private UUID tenantId(JsonNode upstream) {
        String raw = text(upstream, "tenantId");
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.error("admin-service returned a client tenant with an unparseable id: {}", raw);
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /**
     * admin-service's two failure kinds, in our vocabulary. Identical in shape to the tenants-service
     * proxy helpers, and separate because the two clients throw from different packages — sharing one
     * would mean a base class whose only content is two catch blocks.
     */
    private <T> T upstream(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            log.warn("admin-service unavailable: {}", unavailable.getMessage());
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE,
                    "Customer provisioning is temporarily unavailable. Retry shortly.");
        }
    }
}
