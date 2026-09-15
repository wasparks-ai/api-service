package com.wasparks.api.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.entity.ApiPartnerTenant;
import com.wasparks.api.entity.ApiSetupLink;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.repository.ApiSetupLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hosted setup links (§B2, §C3): the URL a partner hands its customer so the customer can connect their
 * own WhatsApp number without ever having a WaSparks account.
 *
 * <h2>This service does not mint the token</h2>
 * tenants-service does, and hands it back exactly once (internal.md, Setup links). All that happens here
 * is that the token becomes a URL — {@code {APP_PUBLIC_BASE_URL}/setup/{token}} — and is returned to the
 * partner. The token is never stored here, never logged, and never appears in a later read: {@code GET
 * /v1/customers/{id}/setup-links/{linkId}} returns status and nothing else, because a link that could be
 * read back would be a link an attacker with read access to this API could use.
 *
 * <p>Splitting it that way is deliberate. Only its SHA-256 is persisted, in tenants-service's own table,
 * so a dump of {@code api_setup_links} yields no working link — the same shape as password resets (017).
 * A partner that loses a link generates another; there is nothing to recover and that is the point.
 *
 * <h2>One live link per customer</h2>
 * Creating a link cancels the customer's previous PENDING one, upstream, as part of the same call. Two
 * live links would be two ways to connect the same number with no way to tell afterwards which one the
 * customer used — and, since the partner's {@code successUrl} may differ between them, no way to know
 * where the customer was sent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SetupLinkService {

    /** The tenant-web route that serves a link (epic §E1). */
    private static final String SETUP_PATH = "/setup/";

    /** Ceiling on a caller-supplied lifetime. Beyond this a link is a credential nobody remembers. */
    private static final int MAX_TTL_HOURS = 24 * 30;

    private final InternalTenantsClient tenantsClient;
    private final ApiSetupLinkRepository setupLinkRepository;
    private final PartnerCustomerService customerService;
    private final ObjectMapper objectMapper;

    @Value("${app.partner.app-base-url}")
    private String appBaseUrl;

    @Value("${app.partner.setup-link-ttl-hours}")
    private int defaultTtlHours;

    /**
     * Mint a link for one customer.
     *
     * <p>The customer is verified first, and the verification is what makes the upstream call safe:
     * tenants-service takes the customer's tenant id <b>in the body</b> here — the one endpoint where it
     * does — precisely because it does not map {@code api_partner_tenants} and cannot check the
     * relationship itself. If this check were skipped, a partner could mint a working connect-your-number
     * link for any tenant on the platform.
     */
    @Transactional
    public Map<String, Object> create(ApiPrincipal principal, UUID customerId, String successUrl,
                                      String failureUrl, Integer expiresInHours) {
        ApiPartnerTenant link = customerService.requireLink(principal, customerId);
        requireRedirect(successUrl, "successUrl");
        requireRedirect(failureUrl, "failureUrl");

        int ttlHours = ttlHours(expiresInHours);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", customerId.toString());
        body.put("partnerId", link.getPartnerId().toString());
        body.put("successUrl", successUrl.trim());
        body.put("failureUrl", failureUrl.trim());
        body.put("expiresAt", Instant.now().plus(Duration.ofHours(ttlHours)).toString());
        body.put("createdByKey", principal.keyId() == null ? null : principal.keyId().toString());

        JsonNode created = tenantsClient.createSetupLink(principal, objectMapper.valueToTree(body));

        String token = text(created, "token");
        if (token == null || token.isBlank()) {
            // Without the token there is no link to hand the customer, and it is returned once — so
            // there is no second chance to ask for it. Better an honest 503 than a response with a URL
            // that ends in "null".
            log.error("tenants-service created setup link {} but returned no token",
                    text(created, "id"));
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE,
                    "The setup link could not be generated. Try again.");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", text(created, "id"));
        response.put("customerId", customerId.toString());
        response.put("url", url(token));
        response.put("status", text(created, "status"));
        response.put("expiresAt", text(created, "expiresAt"));
        response.put("createdAt", text(created, "createdAt"));
        return response;
    }

    /**
     * A link's current state. <b>Never the URL</b> — the token exists only in the create response.
     *
     * <p>Read from our own mapping of {@code api_setup_links} rather than from upstream: the row is
     * already in the shared database, the partner is only ever asking "has my customer connected yet?",
     * and a round trip per row would make the console's client table N+1 upstream calls wide.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get(ApiPrincipal principal, UUID customerId, UUID linkId) {
        UUID partnerId = customerService.requireLink(principal, customerId).getPartnerId();
        ApiSetupLink link = setupLinkRepository.findByIdAndPartnerId(linkId, partnerId)
                .filter(row -> row.getTenantId().equals(customerId))
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "No such setup link."));
        return toPublic(link);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(ApiPrincipal principal, UUID customerId) {
        UUID partnerId = customerService.requireLink(principal, customerId).getPartnerId();
        return setupLinkRepository
                .findByPartnerIdAndTenantIdOrderByCreatedAtDesc(partnerId, customerId).stream()
                .map(this::toPublic)
                .toList();
    }

    /** Cancel a PENDING link. Upstream answers {@code 409 link_unusable} for anything else. */
    @Transactional
    public Map<String, Object> cancel(ApiPrincipal principal, UUID customerId, UUID linkId) {
        UUID partnerId = customerService.requireLink(principal, customerId).getPartnerId();
        setupLinkRepository.findByIdAndPartnerId(linkId, partnerId)
                .filter(row -> row.getTenantId().equals(customerId))
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "No such setup link."));

        JsonNode cancelled = tenantsClient.cancelSetupLink(principal, linkId.toString());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", linkId.toString());
        response.put("customerId", customerId.toString());
        response.put("status", text(cancelled, "status"));
        response.put("expiresAt", text(cancelled, "expiresAt"));
        return response;
    }

    /** {@code https://app.wasparks.com/setup/{token}} — the base is environment, the path is fixed. */
    String url(String token) {
        String base = appBaseUrl == null ? "" : appBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + SETUP_PATH + token;
    }

    private int ttlHours(Integer requested) {
        if (requested == null) {
            return defaultTtlHours;
        }
        if (requested < 1 || requested > MAX_TTL_HOURS) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`expiresInHours` must be between 1 and " + MAX_TTL_HOURS + ".");
        }
        return requested;
    }

    /**
     * The redirect targets must be https URLs we are willing to send a customer to.
     *
     * <p>This is the one place in the partner flow where we hand a real person's browser to a URL a
     * partner supplied, with the customer's id and phone number id in the query string. An unvalidated
     * value here is an open redirect with data attached; {@code javascript:} would be worse still. The
     * loopback exemption matches the webhook rule, so a partner can develop against localhost.
     */
    private void requireRedirect(String url, String field) {
        if (url == null || url.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "`" + field + "` is required.");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`" + field + "` is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`" + field + "` must be an absolute URL including a host.");
        }
        boolean loopback = "localhost".equalsIgnoreCase(host) || host.startsWith("127.")
                || "[::1]".equals(host);
        if ("https".equals(scheme) || ("http".equals(scheme) && loopback)) {
            return;
        }
        throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                "`" + field + "` must be https (http is accepted only for localhost).");
    }

    private Map<String, Object> toPublic(ApiSetupLink link) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", link.getId().toString());
        body.put("customerId", link.getTenantId().toString());
        body.put("status", link.getStatus().name());
        body.put("expiresAt", link.getExpiresAt());
        body.put("completedAt", link.getCompletedAt());
        body.put("phoneNumberId", link.getPhoneNumberId());
        body.put("wabaId", link.getWabaId());
        body.put("successUrl", link.getSuccessUrl());
        body.put("failureUrl", link.getFailureUrl());
        body.put("createdAt", link.getCreatedAt());
        return body;
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
