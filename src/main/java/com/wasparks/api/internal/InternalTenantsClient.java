package com.wasparks.api.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.wasparks.api.auth.ApiPrincipal;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The only way this service reaches tenants-service (epic §0.2, §C1).
 *
 * <p>Three headers authenticate and scope every call: {@code X-Internal-Token} (the shared
 * {@code INTERNAL_API_SECRET}, compared in constant time upstream), {@code X-Tenant-Id}, and
 * {@code X-Actor-Api-Key} — which upstream records as the audit actor. <b>The tenant id comes from the
 * resolved key principal and never from the request</b>, so a caller cannot name a tenant it does not
 * hold a key for even if it controls every byte of the body (epic §0.8).
 *
 * <p>Failures are split into exactly two kinds, because callers need to act differently on them: a 4xx
 * is a {@link UpstreamRejectedException} (final, tell the client), anything else is
 * {@link UpstreamUnavailableException} (transient, worth a retry). The 10s timeout is deliberately
 * shorter than the client's patience and longer than a Meta round trip.
 *
 * <p>Calls are made {@code .block()}ing on purpose. This is a servlet application, the send path is
 * synchronous by design (the client is waiting for a 202 or a 4xx), and a reactive chain bolted onto a
 * blocking request thread buys nothing but a harder stack trace.
 *
 * <p>Template endpoints pass {@link JsonNode} straight through rather than modelling tenants-service's
 * template DTO. That DTO is large, is owned upstream, and would become a second copy to keep in step for
 * no benefit — this service adds nothing to a template payload but the scope check and the audit actor.
 */
@Component
@Slf4j
public class InternalTenantsClient {

    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_ACTOR_API_KEY = "X-Actor-Api-Key";

    /** A CSV has to reach GCS before upstream answers, so it gets its own, longer ceiling (§B3). */
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(60);

    /**
     * The house error envelope's own keys — everything else in the body is a detail (amendment 6).
     *
     * <p>{@code status} is <b>not</b> among them, and deliberately so: the envelope's {@code status} is
     * the numeric HTTP code, while {@code 410 link_unusable} uses a <em>string</em> {@code status} to say
     * whether a setup link expired, was completed or was cancelled (setup-links.md) — which is the one
     * thing the caller actually needs. {@link #isEnvelopeKey} separates them by type rather than by name.
     */
    private static final Set<String> ENVELOPE_KEYS =
            Set.of("timestamp", "error", "code", "message", "path", "fieldErrors");

    private final String baseUrl;
    private final String internalSecret;
    private final int timeoutMs;
    private WebClient webClient;

    public InternalTenantsClient(@Value("${app.internal.base-url}") String baseUrl,
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
                .defaultHeader(HEADER_INTERNAL_TOKEN, internalSecret)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                        httpClient))
                // A single send body is small; a template list is not. 4 MB is generous for both and
                // still bounded, so a pathological upstream response cannot exhaust heap.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    // ------------------------------------------------------------------ messages

    /**
     * The synchronous preflight (§B5). Zero side effects upstream: it runs the same checks the real send
     * runs, so a client never receives a 202 for something we could have rejected outright.
     *
     * <p>Returns normally when upstream said yes. A rejection throws, and the caller maps it to the
     * synchronous Meta-shaped 4xx.
     */
    public void validateSend(ApiPrincipal principal, InternalDtos.SendRequest request) {
        exchange(HttpMethod.POST, uri -> uri.path("/internal/v1/messages/validate").build(),
                principal.tenantId(), principal.keyId(), request, InternalDtos.ValidateResponse.class);
    }

    /** The real send, called only from {@code SendWorker}. */
    public InternalDtos.SendResponse send(UUID tenantId, UUID apiKeyId,
                                          InternalDtos.SendRequest request) {
        return exchange(HttpMethod.POST, uri -> uri.path("/internal/v1/messages/send").build(),
                tenantId, apiKeyId, request, InternalDtos.SendResponse.class);
    }

    /**
     * Mark a message failed after the worker gave up. Upstream creates the row when it does not exist
     * and the body carries {@code to} and {@code phoneNumberId} (amendment 6), which is what keeps
     * {@code GET /v1/messages/{id}} answering for a message that never reached the database.
     */
    public InternalDtos.MessageProjection fail(UUID tenantId, UUID apiKeyId, UUID messageId,
                                               InternalDtos.FailRequest request) {
        return exchange(HttpMethod.POST,
                uri -> uri.path("/internal/v1/messages/{id}/fail").build(messageId),
                tenantId, apiKeyId, request, InternalDtos.MessageProjection.class);
    }

    public InternalDtos.MessageProjection getMessage(ApiPrincipal principal, UUID messageId) {
        return exchange(HttpMethod.GET,
                uri -> uri.path("/internal/v1/messages/{id}").build(messageId),
                principal.tenantId(), principal.keyId(), null, InternalDtos.MessageProjection.class);
    }

    // ------------------------------------------------------------------ templates (pass-through)

    public JsonNode listTemplates(ApiPrincipal principal, MultiValueMap<String, String> query) {
        return exchange(HttpMethod.GET,
                uri -> uri.path("/internal/v1/templates").queryParams(query).build(),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    public JsonNode getTemplate(ApiPrincipal principal, String id) {
        return exchange(HttpMethod.GET, uri -> uri.path("/internal/v1/templates/{id}").build(id),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    public JsonNode createTemplate(ApiPrincipal principal, JsonNode body) {
        return exchange(HttpMethod.POST, uri -> uri.path("/internal/v1/templates").build(),
                principal.tenantId(), principal.keyId(), body, JsonNode.class);
    }

    public JsonNode updateTemplate(ApiPrincipal principal, String id, JsonNode body) {
        return exchange(HttpMethod.PATCH, uri -> uri.path("/internal/v1/templates/{id}").build(id),
                principal.tenantId(), principal.keyId(), body, JsonNode.class);
    }

    public JsonNode submitTemplate(ApiPrincipal principal, String id) {
        return exchange(HttpMethod.POST,
                uri -> uri.path("/internal/v1/templates/{id}/submit").build(id),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    public JsonNode refreshTemplate(ApiPrincipal principal, String id) {
        return exchange(HttpMethod.POST,
                uri -> uri.path("/internal/v1/templates/{id}/refresh").build(id),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    public void deleteTemplate(ApiPrincipal principal, String id) {
        exchange(HttpMethod.DELETE, uri -> uri.path("/internal/v1/templates/{id}").build(id),
                principal.tenantId(), principal.keyId(), null, Void.class);
    }

    // ------------------------------------------------------------------ campaigns (api-partner §C1)

    /**
     * Campaigns, audiences and uploads pass {@link JsonNode} through, for the same reason templates do:
     * these are upstream's DTOs, this service adds only scope checks, the inline cap, quota reservation
     * and a {@code clientRef} echo, and re-declaring the bodies here would be a second copy of the
     * campaign engine's surface to keep in step for no benefit to anyone.
     *
     * <p>The one shape this service does read out of a response is {@code counts}, because the daily
     * quota reservation is computed from it (§B3).
     */
    public JsonNode createCampaign(ApiPrincipal principal, JsonNode body) {
        return exchange(HttpMethod.POST, uri -> uri.path("/internal/v1/campaigns").build(),
                principal.tenantId(), principal.keyId(), body, JsonNode.class);
    }

    public JsonNode listCampaigns(UUID tenantId, UUID apiKeyId, MultiValueMap<String, String> query) {
        return exchange(HttpMethod.GET,
                uri -> uri.path("/internal/v1/campaigns").queryParams(query).build(),
                tenantId, apiKeyId, null, JsonNode.class);
    }

    public JsonNode getCampaign(UUID tenantId, UUID apiKeyId, String id) {
        return exchange(HttpMethod.GET, uri -> uri.path("/internal/v1/campaigns/{id}").build(id),
                tenantId, apiKeyId, null, JsonNode.class);
    }

    public JsonNode campaignRecipients(ApiPrincipal principal, String id,
                                       MultiValueMap<String, String> query) {
        return exchange(HttpMethod.GET,
                uri -> uri.path("/internal/v1/campaigns/{id}/recipients").queryParams(query).build(id),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    public JsonNode appendCampaignRecipients(ApiPrincipal principal, String id, JsonNode body) {
        return exchange(HttpMethod.POST,
                uri -> uri.path("/internal/v1/campaigns/{id}/recipients").build(id),
                principal.tenantId(), principal.keyId(), body, JsonNode.class);
    }

    /**
     * {@code start} | {@code pause} | {@code resume} | {@code cancel} — the names upstream uses.
     *
     * <p>Takes ids rather than a principal because the scheduled-campaign quota poller (§B3) calls it
     * with no request in flight: it watches SCHEDULED campaigns across every client of every partner and
     * has a tenant and a key id but no principal to speak of.
     */
    public JsonNode transitionCampaign(UUID tenantId, UUID apiKeyId, String id, String transition) {
        return exchange(HttpMethod.POST,
                uri -> uri.path("/internal/v1/campaigns/{id}/{transition}").build(id, transition),
                tenantId, apiKeyId, null, JsonNode.class);
    }

    // ------------------------------------------------------------------ audiences (api-partner §B3a)

    public JsonNode createAudience(ApiPrincipal principal, JsonNode body) {
        return exchange(HttpMethod.POST, uri -> uri.path("/internal/v1/audiences").build(),
                principal.tenantId(), principal.keyId(), body, JsonNode.class);
    }

    public JsonNode listAudiences(ApiPrincipal principal, MultiValueMap<String, String> query) {
        return exchange(HttpMethod.GET,
                uri -> uri.path("/internal/v1/audiences").queryParams(query).build(),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    public JsonNode getAudience(ApiPrincipal principal, String id) {
        return exchange(HttpMethod.GET, uri -> uri.path("/internal/v1/audiences/{id}").build(id),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    public JsonNode audienceMembers(ApiPrincipal principal, String id,
                                    MultiValueMap<String, String> query) {
        return exchange(HttpMethod.GET,
                uri -> uri.path("/internal/v1/audiences/{id}/members").queryParams(query).build(id),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    public JsonNode addAudienceMembers(ApiPrincipal principal, String id, JsonNode body) {
        return exchange(HttpMethod.POST,
                uri -> uri.path("/internal/v1/audiences/{id}/members").build(id),
                principal.tenantId(), principal.keyId(), body, JsonNode.class);
    }

    /**
     * Member removal carries a body on a DELETE, which is unusual but is upstream's contract and the
     * honest shape: the alternative puts a customer's phone number in a query string, and a phone number
     * does not belong in an access log.
     */
    public JsonNode removeAudienceMembers(ApiPrincipal principal, String id, JsonNode body) {
        return exchange(HttpMethod.DELETE,
                uri -> uri.path("/internal/v1/audiences/{id}/members").build(id),
                principal.tenantId(), principal.keyId(), body, JsonNode.class);
    }

    public void deleteAudience(ApiPrincipal principal, String id) {
        exchange(HttpMethod.DELETE, uri -> uri.path("/internal/v1/audiences/{id}").build(id),
                principal.tenantId(), principal.keyId(), null, Void.class);
    }

    // ------------------------------------------------------------------ CSV upload (api-partner §B3)

    /**
     * Stream a recipient CSV through to {@code POST /internal/v1/uploads/csv}, which stores it in GCS and
     * returns {@code {uploadId, rows, columns, fileName}}.
     *
     * <p><b>Streamed, never buffered.</b> The part is wrapped as an {@link InputStreamResource} over the
     * servlet container's own spooled file, so a 10 MB list of recipients travels from the socket to GCS
     * without a copy of it existing on this service's heap. Reading it into a {@code byte[]} first would
     * be a megabyte of garbage per upload and, with a few partners bulk-loading at once, the thing that
     * pushes an otherwise idle gateway into a full GC.
     *
     * <p>The JSON content type every other call defaults to is overridden here; the multipart boundary is
     * generated by the encoder.
     */
    public JsonNode uploadCsv(ApiPrincipal principal, String fileName, long size,
                              InputStream content) {
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("file", new InputStreamResource(content) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }

                    /**
                     * Known length, so the encoder can send a {@code Content-Length} rather than
                     * chunking. {@code InputStreamResource} answers "unknown" by default, and upstream's
                     * size check reads better against a declared length than against a stream that turns
                     * out to be too long halfway through.
                     */
                    @Override
                    public long contentLength() {
                        return size;
                    }
                })
                .contentType(MediaType.TEXT_PLAIN);

        return exchangeMultipart(principal.tenantId(), principal.keyId(), parts.build());
    }

    // ------------------------------------------------------------------ setup links (api-partner §C3)

    /**
     * Mint a setup link. <b>The only call whose body carries a tenant id</b>, and the only one where the
     * body and the {@code X-Tenant-Id} header disagree on purpose: the link is for the partner's
     * CUSTOMER, while the header stays the acting tenant so the audit row names the right actor.
     * api-service has already proved the customer belongs to the partner (§0.4); upstream does not map
     * {@code api_partner_tenants} and deliberately does not re-derive it.
     *
     * <p>The response carries the 32-character token <b>once</b>. It is turned into a URL and never
     * stored, logged or returned again.
     */
    public JsonNode createSetupLink(ApiPrincipal principal, JsonNode body) {
        return exchange(HttpMethod.POST, uri -> uri.path("/internal/v1/setup-links").build(),
                principal.tenantId(), principal.keyId(), body, JsonNode.class);
    }

    public JsonNode getSetupLink(ApiPrincipal principal, String id) {
        return exchange(HttpMethod.GET, uri -> uri.path("/internal/v1/setup-links/{id}").build(id),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    public JsonNode cancelSetupLink(ApiPrincipal principal, String id) {
        return exchange(HttpMethod.POST,
                uri -> uri.path("/internal/v1/setup-links/{id}/cancel").build(id),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    // ------------------------------------------------------------------ direct mapping + media

    /**
     * Direct number mapping (§0.5 / §C2). Three Graph calls happen upstream before a row is written; this
     * service contributes the ownership check on the customer and the error pass-through, nothing else.
     *
     * <p>The body carries a Meta access token. It is never logged here — not at debug level, not in a
     * failure message — and is encrypted the moment it reaches tenants-service.
     */
    public JsonNode mapAccount(ApiPrincipal principal, JsonNode body) {
        return exchange(HttpMethod.POST, uri -> uri.path("/internal/v1/accounts/map").build(),
                principal.tenantId(), principal.keyId(), body, JsonNode.class);
    }

    /** A fresh 1-hour signed URL for an inbound message's stored media (§0.11). */
    public JsonNode mediaUrl(ApiPrincipal principal, String messageId) {
        return exchange(HttpMethod.GET,
                uri -> uri.path("/internal/v1/media/{id}/url").build(messageId),
                principal.tenantId(), principal.keyId(), null, JsonNode.class);
    }

    // ------------------------------------------------------------------ tenant settings

    /**
     * Write a tenant setting the Partner console owns — today only the marketing frequency guard
     * ({@code tenants.min_days_between_marketing}, 021 §8).
     *
     * <p><b>This endpoint is not in internal.md yet.</b> The hand-off anticipated that and says to request
     * it; it is implemented here against the agreed path so the console works the moment tenants-service
     * ships it, and a 404 from upstream surfaces as a plain "not available yet" rather than as a
     * confusing generic failure. See the hand-off report §9.
     */
    public JsonNode updateTenantSettings(ApiPrincipal principal, Object body) {
        return exchange(HttpMethod.PATCH,
                uri -> uri.path("/internal/v1/tenants/{id}/settings").build(principal.tenantId()),
                principal.tenantId(), principal.keyId(), body, JsonNode.class);
    }

    // ------------------------------------------------------------------ account

    public InternalDtos.AccountResponse getAccount(ApiPrincipal principal) {
        return exchange(HttpMethod.GET, uri -> uri.path("/internal/v1/account").build(),
                principal.tenantId(), principal.keyId(), null, InternalDtos.AccountResponse.class);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The multipart variant of {@link #exchange}. Separate rather than a flag on it because the two
     * differ in three places at once — content type, body encoding and the absence of a retryable body —
     * and a shared method with three branches would be harder to read than the duplication is to keep.
     */
    private JsonNode exchangeMultipart(UUID tenantId, UUID apiKeyId,
                                       MultiValueMap<String, HttpEntity<?>> parts) {
        try {
            return webClient.post()
                    .uri(uri -> uri.path("/internal/v1/uploads/csv").build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header(HEADER_TENANT_ID, tenantId.toString())
                    .header(HEADER_ACTOR_API_KEY, apiKeyId == null ? "" : apiKeyId.toString())
                    .body(BodyInserters.fromMultipartData(parts))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, response ->
                            response.bodyToMono(JsonNode.class)
                                    .defaultIfEmpty(NullNode.getInstance())
                                    .flatMap(json -> Mono.error(toRejection(json,
                                            response.statusCode().value()))))
                    .onStatus(HttpStatusCode::is5xxServerError, response ->
                            Mono.error(new UpstreamUnavailableException(
                                    "tenants-service returned " + response.statusCode().value()
                                            + " for a CSV upload")))
                    .bodyToMono(JsonNode.class)
                    // An upload is the one call that can legitimately outlast the standard timeout: the
                    // bytes have to reach GCS before upstream answers. A minute is generous for 10 MB
                    // and still bounded, so a stalled upload cannot pin a request thread indefinitely.
                    .block(UPLOAD_TIMEOUT);
        } catch (UpstreamRejectedException | UpstreamUnavailableException e) {
            throw e;
        } catch (WebClientRequestException e) {
            throw new UpstreamUnavailableException(
                    "Could not reach tenants-service at " + baseUrl + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new UpstreamUnavailableException("CSV upload to tenants-service failed: "
                    + e.getMessage(), e);
        }
    }

    private <T> T exchange(HttpMethod method,
                           Function<UriBuilder, java.net.URI> uriFunction,
                           UUID tenantId, UUID apiKeyId,
                           Object body, Class<T> responseType) {
        WebClient.RequestBodySpec spec = webClient.method(method)
                .uri(uriFunction::apply)
                .header(HEADER_TENANT_ID, tenantId.toString())
                .header(HEADER_ACTOR_API_KEY, apiKeyId == null ? "" : apiKeyId.toString());

        WebClient.RequestHeadersSpec<?> headersSpec = body == null ? spec : spec.bodyValue(body);

        try {
            return headersSpec.retrieve()
                    .onStatus(status -> status.is4xxClientError(), response ->
                            response.bodyToMono(JsonNode.class)
                                    .defaultIfEmpty(com.fasterxml.jackson.databind.node.NullNode
                                            .getInstance())
                                    .flatMap(json -> Mono.error(toRejection(json,
                                            response.statusCode().value()))))
                    .onStatus(status -> status.is5xxServerError(), response ->
                            response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(text -> Mono.error(new UpstreamUnavailableException(
                                            "tenants-service returned "
                                                    + response.statusCode().value()
                                                    + (text.isBlank() ? "" : ": " + trim(text))))))
                    .bodyToMono(responseType)
                    .block(Duration.ofMillis(timeoutMs + 1000L));
        } catch (UpstreamRejectedException | UpstreamUnavailableException e) {
            throw e;
        } catch (WebClientRequestException e) {
            // Connection refused, DNS failure, read timeout — tenants-service is not answering.
            throw new UpstreamUnavailableException(
                    "Could not reach tenants-service at " + baseUrl + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new UpstreamUnavailableException(
                    "Internal call to tenants-service failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build the rejection from upstream's error envelope. internal.md guarantees a machine {@code code}
     * on every handler; if one is ever missing we fall back to the HTTP status rather than inventing a
     * code, because {@link com.wasparks.api.error.ApiErrorCode#fromUpstream} maps an unknown string to a
     * generic bad request and that is the honest answer.
     */
    private UpstreamRejectedException toRejection(JsonNode body, int status) {
        String code = body != null && body.hasNonNull("code") ? body.get("code").asText() : null;
        String message = body != null && body.hasNonNull("message")
                ? body.get("message").asText()
                : "tenants-service rejected the request with HTTP " + status;
        Map<String, Object> details = extraKeys(body);
        if (code == null) {
            code = switch (status) {
                case 404 -> "not_found";
                case 409 -> "conflict";
                case 403 -> "forbidden";
                case 429 -> "tier_cap_reached";
                default -> "invalid_request";
            };
        }
        return new UpstreamRejectedException(code, message, status, details);
    }

    /**
     * Upstream's error {@code details} arrive as top-level keys beside {@code code} and {@code message}
     * (amendment 6) — {@code docsUrl} on a 422, {@code status} on a 410. Everything that is not part of
     * the envelope itself is one of those, so the envelope's own keys are named and the rest is taken.
     * Naming what to <em>drop</em> rather than what to keep means a detail tenants-service adds later
     * reaches the partner without a change here.
     */
    private Map<String, Object> extraKeys(JsonNode body) {
        if (body == null || !body.isObject()) {
            return Map.of();
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        body.fields().forEachRemaining(entry -> {
            if (isEnvelopeKey(entry.getKey(), entry.getValue()) || entry.getValue().isNull()) {
                return;
            }
            JsonNode value = entry.getValue();
            extras.put(entry.getKey(), value.isValueNode()
                    ? (value.isNumber() ? value.numberValue() : value.asText())
                    : value);
        });
        return extras;
    }

    /** A numeric {@code status} is the HTTP code; a string one is the setup link's own state. */
    private boolean isEnvelopeKey(String name, JsonNode value) {
        return ENVELOPE_KEYS.contains(name) || ("status".equals(name) && value.isNumber());
    }

    private String trim(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200);
    }
}
