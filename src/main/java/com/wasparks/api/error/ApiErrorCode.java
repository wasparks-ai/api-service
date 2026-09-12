package com.wasparks.api.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Optional;

/**
 * <b>The single table that maps one failure to both of this service's error shapes.</b>
 *
 * <p>The API speaks two dialects. {@code /v1/**} returns the house envelope
 * {@code {"error":{"code","message","details"}}}; {@code /meta/**} must return Meta's Cloud API shape
 * {@code {"error":{"message","type","code","error_subcode","fbtrace_id"}}} so that a client's existing
 * Cloud API error handling keeps working after it changes base URL (epic §0.4). The same underlying
 * rejection has to render as both, and the Meta numbers are the part a client's code actually branches
 * on — 131047 and 132001 are load-bearing constants, not decoration.
 *
 * <p>Keeping the pairing in one enum is deliberate: the alternative, a switch in the Meta controller and
 * another in the {@code /v1} advice, drifts the first time a code is added. Every rejection in the
 * service names a constant here, and {@code X-WaSparks-Error} carries {@link #wire()} on both dialects so
 * support can read our code off a Meta-shaped response.
 *
 * <p>The status/code pairs mirror tenants-service's internal contract (internal.md) so a rejection that
 * originates upstream and one we make locally are indistinguishable to the client — which matters,
 * because the preflight (§B5) is precisely the same check made in two places.
 */
@Getter
public enum ApiErrorCode {

    // ---- authentication / authorisation (epic §B2) ----
    INVALID_API_KEY("invalid_api_key", HttpStatus.UNAUTHORIZED, 0, null, "OAuthException",
            "The API key is missing, malformed, revoked, expired, or its tenant is not active."),
    INSUFFICIENT_SCOPE("insufficient_scope", HttpStatus.FORBIDDEN, 0, null, "OAuthException",
            "The API key does not carry the scope this endpoint requires."),
    UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED, 0, null, "OAuthException",
            "Authentication is required."),
    FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, 0, null, "OAuthException",
            "This token may not perform that action."),
    /**
     * tenants-service does not know the key id we sent as {@code X-Actor-Api-Key} (amendment 8: upstream
     * checks the {@code api_keys} row exists in {@code preflight()} and answers 400 rather than blowing up
     * on the {@code messages.api_key_id} foreign key). It means the two services' key tables have drifted
     * — a row deleted on one side, or a key minted against a different database. Rendered as a 401 because
     * the only thing the client can do about it is obtain a working key; the drift itself is logged at
     * error level for us, not explained to them.
     */
    UNKNOWN_API_KEY("unknown_api_key", HttpStatus.UNAUTHORIZED, 0, null, "OAuthException",
            "This API key is not usable. Create a new one."),

    // ---- limits (epic §B3, §B4) ----
    RATE_LIMITED("rate_limited", HttpStatus.TOO_MANY_REQUESTS, 130429, null, "OAuthException",
            "Too many requests. Retry after the window resets."),
    QUOTA_EXCEEDED("quota_exceeded", HttpStatus.TOO_MANY_REQUESTS, 130429, null, "OAuthException",
            "The message quota for this plan has been used up."),
    IDEMPOTENCY_CONFLICT("idempotency_conflict", HttpStatus.UNPROCESSABLE_ENTITY, 100, 2494010,
            "OAuthException",
            "This Idempotency-Key was already used with a different request body."),

    // ---- send preflight, local and upstream (epic §B5 / internal.md steps 1-5) ----
    INVALID_REQUEST("invalid_request", HttpStatus.BAD_REQUEST, 100, null, "OAuthException",
            "The request body is not valid for this message type."),
    UNSUPPORTED_TYPE("unsupported_type", HttpStatus.BAD_REQUEST, 100, null, "OAuthException",
            "That message type is not supported by this API version."),
    ACCOUNT_NOT_FOUND("account_not_found", HttpStatus.NOT_FOUND, 100, 33, "OAuthException",
            "No WhatsApp number with that phone_number_id belongs to this tenant."),
    ACCOUNT_DISCONNECTED("account_disconnected", HttpStatus.CONFLICT, 190, null, "OAuthException",
            "The WhatsApp account's access token is no longer valid. Reconnect the number."),
    ACCOUNT_INACTIVE("account_inactive", HttpStatus.CONFLICT, 100, null, "OAuthException",
            "The WhatsApp number is not active."),
    RECIPIENT_OPTED_OUT("recipient_opted_out", HttpStatus.FORBIDDEN, 131050, null, "OAuthException",
            "The recipient has opted out of messages from this business."),
    WINDOW_CLOSED("window_closed", HttpStatus.BAD_REQUEST, 131047, null, "OAuthException",
            "More than 24 hours have passed since the recipient last replied. Send a template instead."),
    TEMPLATE_NOT_APPROVED("template_not_approved", HttpStatus.BAD_REQUEST, 132001, null, "OAuthException",
            "The template does not exist or is not approved for this number and language."),
    TIER_CAP_REACHED("tier_cap_reached", HttpStatus.TOO_MANY_REQUESTS, 130429, null, "OAuthException",
            "The number's daily messaging limit has been reached."),
    DUPLICATE_MESSAGE_ID("duplicate_message_id", HttpStatus.CONFLICT, 100, null, "OAuthException",
            "A message with this id already exists."),

    // ---- generic (internal.md error envelope) ----
    VALIDATION_FAILED("validation_failed", HttpStatus.BAD_REQUEST, 100, null, "OAuthException",
            "One or more fields are invalid."),
    MALFORMED_BODY("malformed_body", HttpStatus.BAD_REQUEST, 100, null, "OAuthException",
            "The request body could not be parsed."),
    NOT_FOUND("not_found", HttpStatus.NOT_FOUND, 100, null, "OAuthException",
            "No such resource."),
    CONFLICT("conflict", HttpStatus.CONFLICT, 100, null, "OAuthException",
            "The resource is not in a state that allows this."),
    DUPLICATE("duplicate", HttpStatus.CONFLICT, 100, null, "OAuthException",
            "A resource with those identifying values already exists."),
    PLAN_LIMIT_REACHED("plan_limit_reached", HttpStatus.CONFLICT, 100, null, "OAuthException",
            "This plan does not allow any more of those."),

    // ---- upstream (epic §B6) ----
    UPSTREAM_UNAVAILABLE("upstream_unavailable", HttpStatus.SERVICE_UNAVAILABLE, 131026, null,
            "OAuthException",
            "The messaging service is temporarily unavailable. Retry shortly."),
    /**
     * Redis is unreachable, so the send cannot be enqueued and — the reason this is a distinct code — the
     * {@code Idempotency-Key} record cannot be read or written either.
     *
     * <p>The limiters fail open when Redis is down: an unreachable counter must not stop a customer
     * sending. The send path does not. Accepting a send without the idempotency store means a client
     * retrying after a dropped connection sends the customer a second WhatsApp message, and the job could
     * not have been queued anyway. So idempotency never degrades: it is either enforced or the send is
     * refused, with {@code Retry-After: 5}.
     */
    QUEUE_UNAVAILABLE("queue_unavailable", HttpStatus.SERVICE_UNAVAILABLE, 131026, null,
            "OAuthException",
            "The send queue is unavailable, so the message was not accepted. Retry shortly."),
    INTERNAL_ERROR("internal_error", HttpStatus.INTERNAL_SERVER_ERROR, 0, null, "OAuthException",
            "Something went wrong on our side.");

    /** The snake_case code in the {@code /v1} envelope and in {@code X-WaSparks-Error}. */
    private final String wire;
    private final HttpStatus status;
    /** Meta's numeric error code; {@code 0} means "no Meta equivalent" and renders as 0 like Meta does. */
    private final int metaCode;
    private final Integer metaSubcode;
    private final String metaType;
    private final String defaultMessage;

    ApiErrorCode(String wire, HttpStatus status, int metaCode, Integer metaSubcode, String metaType,
                 String defaultMessage) {
        this.wire = wire;
        this.status = status;
        this.metaCode = metaCode;
        this.metaSubcode = metaSubcode;
        this.metaType = metaType;
        this.defaultMessage = defaultMessage;
    }

    public String wire() {
        return wire;
    }

    /**
     * Resolve a code coming back from tenants-service's internal surface. An unknown string is mapped to
     * {@link #INVALID_REQUEST} rather than thrown on: upstream is allowed to add codes without this
     * service failing closed on a 500, and the upstream message is preserved either way.
     */
    public static ApiErrorCode fromUpstream(String upstreamCode) {
        return fromWire(upstreamCode).orElse(INVALID_REQUEST);
    }

    public static Optional<ApiErrorCode> fromWire(String value) {
        return Arrays.stream(values()).filter(c -> c.wire.equals(value)).findFirst();
    }
}
