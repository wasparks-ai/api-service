package com.wasparks.api.internal;

import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import lombok.Getter;

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

    public UpstreamRejectedException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /**
     * Translate to the client-facing rejection, preserving upstream's message.
     *
     * <p>The status comes from our {@link ApiErrorCode} table rather than from upstream's response, so
     * that one rejection renders identically whether this service caught it locally in preflight or
     * upstream caught it during the real send — a client must not be able to tell which layer said no.
     */
    public ApiException toApiException() {
        return ApiException.of(ApiErrorCode.fromUpstream(code), getMessage());
    }
}
