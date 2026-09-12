package com.wasparks.api.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.auth.ApiPrincipal;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
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

    // ------------------------------------------------------------------ account

    public InternalDtos.AccountResponse getAccount(ApiPrincipal principal) {
        return exchange(HttpMethod.GET, uri -> uri.path("/internal/v1/account").build(),
                principal.tenantId(), principal.keyId(), null, InternalDtos.AccountResponse.class);
    }

    // ------------------------------------------------------------------ plumbing

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
        if (code == null) {
            code = switch (status) {
                case 404 -> "not_found";
                case 409 -> "conflict";
                case 403 -> "forbidden";
                case 429 -> "tier_cap_reached";
                default -> "invalid_request";
            };
        }
        return new UpstreamRejectedException(code, message, status);
    }

    private String trim(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200);
    }
}
