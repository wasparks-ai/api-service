package com.wasparks.api.internal;

import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tenants-service answered 4xx: the request was <b>understood and refused</b> — a closed 24h window, an
 * unapproved template, a suppressed recipient, a number that is not this tenant's.
 *
 * <p>The distinction from {@link UpstreamUnavailableException} decides the caller's whole strategy: a
 * rejection is final and must be told to the client now, while an outage is worth retrying. Collapsing
 * the two would mean either retrying a message that will never be accepted, or failing a message that
 * would have gone out a second later.
 */
@Getter
public class UpstreamRejectedException extends RuntimeException {

    private final String code;
    private final int status;
    /**
     * The extra top-level keys of upstream's error body (amendment 6).
     *
     * <p>tenants-service renders {@code ApiErrorException.details} as <b>siblings</b> of {@code code} and
     * {@code message}, not under a nested object — so {@code 422 waba_not_shared} arrives with
     * {@code docsUrl} at the top level and {@code 410 link_unusable} with {@code status}. Those keys are
     * the actionable half of the rejection (the guide that explains how to share a WABA; whether a link
     * expired or was cancelled), and dropping them would leave a partner with a code and nothing to do
     * about it.
     */
    private final Map<String, Object> details = new LinkedHashMap<>();

    public UpstreamRejectedException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public UpstreamRejectedException(String code, String message, int status,
                                     Map<String, Object> details) {
        this(code, message, status);
        if (details != null) {
            this.details.putAll(details);
        }
    }

    /**
     * Translate to the client-facing rejection, preserving upstream's message.
     *
     * <p>The status comes from our {@link ApiErrorCode} table rather than from upstream's response, so
     * that one rejection renders identically whether this service caught it locally in preflight or
     * upstream caught it during the real send — a client must not be able to tell which layer said no.
     */
    public ApiException toApiException() {
        ApiException api = ApiException.of(ApiErrorCode.fromUpstream(code), getMessage());
        // Upstream's top-level extras become our {@code error.details}. The house envelope nests them
        // (§B7) while tenants-service's does not; re-shaping here rather than at each call site keeps
        // one dialect on the public API and still hands the partner every key it needs.
        details.forEach(api::withDetail);
        return api;
    }
}
