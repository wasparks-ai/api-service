package com.wasparks.api.internal;

/**
 * tenants-service answered 5xx, timed out, or could not be reached. The request may still be valid —
 * this says nothing about the message, only about the moment.
 *
 * <p>On the synchronous preflight path this becomes {@code 503 upstream_unavailable}. Inside the send
 * worker it is the one condition that earns a retry (2s / 10s / 30s, epic §B6); every other failure is
 * terminal.
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message) {
        super(message);
    }

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
