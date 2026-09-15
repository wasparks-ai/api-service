package com.wasparks.api.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The second internal client: admin-service (api-partner epic §B7, partners-contract.md §1).
 *
 * <p><b>Why a second service and not more tenants-service.</b> Creating a tenant means
 * {@code TenantService.createTenant} plus {@code createTenantOwner} plus the {@code api_partner_tenants}
 * row, and all three of those already exist, audited and tested, in admin-service. Re-implementing them
 * in tenants-service so that api-service had one upstream would have produced a second tenant-creation
 * path, and two paths that create tenants differently is how a platform ends up with tenants that are
 * subtly not like the others (§0.3).
 *
 * <p>It shares {@code INTERNAL_API_SECRET} and the header scheme with tenants-service, with <b>one
 * difference the contract is explicit about</b> (amendment 12, partners-contract.md §1):
 * {@code X-Tenant-Id} is <b>optional</b> here, because this surface is addressed by partner id rather
 * than by tenant — and a partner key creating its very first client has no client tenant to name. It is
 * sent when there is one, because admin-service carries it into the audit context, and omitted when
 * there is not. Sending a malformed one is a 401, so it is omitted rather than blanked.
 *
 * <p>Failures split the same two ways as tenants-service's: {@link UpstreamRejectedException} for a 4xx
 * the caller must be told about, {@link UpstreamUnavailableException} for anything else.
 *
 * <p>admin-service's error envelope is <em>not</em> tenants-service's: it carries {@code errorCode}
 * (SCREAMING_CASE) rather than {@code code} (snake_case), except on the internal chain's own 401. Both
 * are read here and mapped to our vocabulary, so a partner sees one dialect no matter which service
 * refused it.
 */
@Component
@Slf4j
public class AdminInternalClient {

    /** SCREAMING_CASE, admin-service's own (partners-contract.md §4), mapped to our wire codes. */
    private static final Map<String, String> ERROR_CODES = Map.of(
            "VALIDATION_ERROR", "validation_failed",
            "BAD_REQUEST", "invalid_request",
            "UNAUTHORIZED", "unauthorized",
            "RESOURCE_NOT_FOUND", "not_found",
            "CONFLICT", "conflict",
            "DUPLICATE_RESOURCE", "duplicate");

    private final String baseUrl;
    private final String internalSecret;
    private final int timeoutMs;
    private WebClient webClient;

    public AdminInternalClient(@Value("${app.admin.base-url}") String baseUrl,
                               @Value("${app.internal.secret}") String internalSecret,
                               @Value("${app.internal.timeout-ms}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.internalSecret = internalSecret;
        this.timeoutMs = timeoutMs;
    }

    @PostConstruct
    void init() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs))
                .doOnConnected(c -> c.addHandlerLast(
                        new ReadTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS)));

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(InternalTenantsClient.HEADER_INTERNAL_TOKEN, internalSecret)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    /**
     * Provision one client tenant for a partner.
     *
     * <p><b>Idempotent on {@code (partnerId, externalRef)}</b>, and the contract is blunt about the
     * consequence of omitting the ref: without one there is nothing to be idempotent on and every call
     * creates a new tenant. So {@code CustomersController} refuses a create without an {@code externalRef}
     * rather than passing the omission along — a partner retrying a timed-out request must not end up
     * with two of its customer.
     *
     * <p>A replay answers 200 with the existing row where a first call answers 201; both are returned
     * here and the caller distinguishes them by {@code created}.
     */
    public Created createClientTenant(UUID partnerId, UUID actingTenantId, UUID apiKeyId,
                                      Map<String, Object> body) {
        Response response = exchange(HttpMethod.POST,
                uri -> uri.path("/internal/v1/partners/{partnerId}/tenants").build(partnerId),
                actingTenantId, apiKeyId, body);
        return new Created(response.body(), response.status() == 201);
    }

    /**
     * The partner a tenant <em>is</em> — the Partner console's "am I a partner" resolve (§B6).
     *
     * <p>Asked of admin-service rather than read from our own {@code api_partners} mapping on purpose:
     * admin-service owns what a partner is, including the SUSPENDED case, and a second opinion derived
     * from a table we only read would be a second definition to keep in step.
     *
     * <p>Empty rather than throwing on a 404: "this tenant is not a partner" is the ordinary answer for
     * almost every tenant in the estate, and the caller turns it into {@code 404 not_a_partner}.
     */
    public Optional<JsonNode> findPartnerByOwnerTenant(UUID ownerTenantId, UUID apiKeyId) {
        try {
            return Optional.of(exchange(HttpMethod.GET,
                    uri -> uri.path("/internal/v1/partners/by-owner/{tenantId}").build(ownerTenantId),
                    ownerTenantId, apiKeyId, null).body());
        } catch (UpstreamRejectedException e) {
            if (e.getStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** The partner record: branding, status, slug, {@code clientCount}. */
    public Optional<JsonNode> findPartner(UUID partnerId, UUID actingTenantId, UUID apiKeyId) {
        try {
            return Optional.of(exchange(HttpMethod.GET,
                    uri -> uri.path("/internal/v1/partners/{partnerId}").build(partnerId),
                    actingTenantId, apiKeyId, null).body());
        } catch (UpstreamRejectedException e) {
            if (e.getStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** The provisioned client, and whether this call is what created it (201) or a replay (200). */
    public record Created(JsonNode tenant, boolean created) {
    }

    private record Response(JsonNode body, int status) {
    }

    private Response exchange(HttpMethod method, Function<UriBuilder, URI> uriFunction,
                              UUID tenantId, UUID apiKeyId, Object body) {
        WebClient.RequestBodySpec spec = webClient.method(method)
                .uri(uriFunction::apply)
                .header(InternalTenantsClient.HEADER_ACTOR_API_KEY,
                        apiKeyId == null ? "" : apiKeyId.toString());

        // Optional here, unlike tenants-service (amendment 12). Present when we have one, absent when we
        // do not — never blank, because a present-but-unparseable value is a 401 before any controller.
        if (tenantId != null) {
            spec = spec.header(InternalTenantsClient.HEADER_TENANT_ID, tenantId.toString());
        }

        WebClient.RequestHeadersSpec<?> headersSpec = body == null ? spec : spec.bodyValue(body);

        try {
            return headersSpec.exchangeToMono(response -> {
                int status = response.statusCode().value();
                if (response.statusCode().is4xxClientError()) {
                    return response.bodyToMono(JsonNode.class)
                            .defaultIfEmpty(NullNode.getInstance())
                            .flatMap(json -> Mono.error(toRejection(json, status)));
                }
                if (response.statusCode().is5xxServerError()) {
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(text -> Mono.error(new UpstreamUnavailableException(
                                    "admin-service returned " + status
                                            + (text.isBlank() ? "" : ": " + trim(text)))));
                }
                return response.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(NullNode.getInstance())
                        .map(json -> new Response(json, status));
            }).block(Duration.ofMillis(timeoutMs + 1000L));
        } catch (UpstreamRejectedException | UpstreamUnavailableException e) {
            throw e;
        } catch (WebClientRequestException e) {
            throw new UpstreamUnavailableException(
                    "Could not reach admin-service at " + baseUrl + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new UpstreamUnavailableException(
                    "Internal call to admin-service failed: " + e.getMessage(), e);
        }
    }

    /**
     * Map admin-service's envelope onto ours. It answers {@code errorCode} on the JWT-shaped handlers and
     * a compact {@code code}-less 401 on the internal chain, so both are looked for and the HTTP status
     * is the last resort — the same fallback tenants-service's client uses, for the same reason.
     */
    private UpstreamRejectedException toRejection(JsonNode body, int status) {
        String code = null;
        if (body != null && body.hasNonNull("errorCode")) {
            code = ERROR_CODES.get(body.get("errorCode").asText());
        }
        if (code == null && body != null && body.hasNonNull("code")) {
            code = body.get("code").asText();
        }
        if (code == null) {
            code = switch (status) {
                case 401 -> "unauthorized";
                case 404 -> "not_found";
                case 409 -> "conflict";
                default -> "invalid_request";
            };
        }
        String message = body != null && body.hasNonNull("message")
                ? body.get("message").asText()
                : "admin-service rejected the request with HTTP " + status;

        Map<String, Object> details = new LinkedHashMap<>();
        if (body != null && body.has("validationErrors") && !body.get("validationErrors").isNull()) {
            details.put("fieldErrors", body.get("validationErrors"));
        }
        return new UpstreamRejectedException(code, message, status, details);
    }

    private String trim(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200);
    }
}
