package com.wasparks.api.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The wire shapes of {@code /internal/v1/**} (tenants-service {@code API/internal.md}).
 *
 * <p>Deliberately hand-written rather than shared as a jar: a shared DTO module would couple the two
 * services' release cycles, and the epic's whole argument for a gateway (§0.2) is that they stay
 * independently deployable. The cost is that these records must follow internal.md by hand — so every
 * one names the section it mirrors, and the contract test asserts the field names against a fixture.
 *
 * <p>Every response record ignores unknown properties: tenants-service adding a field must never be a
 * breaking change for this service.
 */
public final class InternalDtos {

    private InternalDtos() {
    }

    /**
     * Body of {@code POST /internal/v1/messages/send} and, byte for byte, of
     * {@code POST /internal/v1/messages/validate} — validate is defined as "same body, zero side
     * effects", so sharing one record is what guarantees the preflight checks the request that will
     * actually be sent.
     *
     * <p>{@code messageId} is minted here (UUIDv7) and becomes {@code messages.id} upstream, so there is
     * exactly one id for a message across both services and the public {@code msg_} id is derived from
     * it rather than being a second identifier to reconcile (amendment 2).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SendRequest(
            String messageId,
            String phoneNumberId,
            String to,
            String type,
            String text,
            Template template,
            Media media,
            String mode,
            String clientRef) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Template(
            String name,
            String language,
            List<String> bodyParams,
            HeaderParam headerParam,
            List<ButtonParam> buttonParams) {
    }

    /** {@code type} may be omitted; values are literal ({@code source} is ignored upstream). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HeaderParam(String type, String value) {
    }

    /** {@code subType} may be omitted. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ButtonParam(Integer index, String subType, String value) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Media(String link, String caption, String filename) {
    }

    /**
     * Response of {@code /send}. A Meta rejection is a <b>result</b>, not an HTTP error — the row exists,
     * it simply failed — so a 200 carrying {@code status: FAILED} is normal and must not be treated as
     * an outage by the worker.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendResponse(String messageId, String status, String wamid, UpstreamError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpstreamError(String code, String message) {
    }

    /** Response of {@code POST /internal/v1/messages/validate}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidateResponse(Boolean ok) {
    }

    /**
     * Body of {@code POST /internal/v1/messages/{id}/fail}.
     *
     * <p>{@code to} and {@code phoneNumberId} are not optional in practice (amendment 6): a message
     * rejected during preflight was never persisted upstream, and without those two fields the fail call
     * 404s and {@code GET /v1/messages/{id}} has nothing to return — the client would be told its
     * message does not exist rather than why it failed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FailRequest(
            String code,
            String message,
            String to,
            String type,
            String phoneNumberId,
            String clientRef,
            String templateName) {
    }

    /** The status projection returned by {@code GET /internal/v1/messages/{id}} and by {@code /fail}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageProjection(
            String messageId,
            String status,
            String wamid,
            String to,
            String type,
            String templateName,
            String clientRef,
            String apiKeyId,
            UpstreamError error,
            Map<String, Object> timestamps) {
    }

    /** {@code GET /internal/v1/account} — phone numbers with cached health and token status. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountResponse(
            String tenantId,
            String tenantName,
            String tokenStatus,
            List<PhoneNumber> phoneNumbers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PhoneNumber(
            String accountId,
            String phoneNumberId,
            String wabaId,
            String display,
            String displayName,
            String status,
            String tokenStatus,
            String tokenExpiresAt,
            String tokenLastVerifiedAt,
            String tokenErrorCode,
            Map<String, Object> health) {
    }
}
