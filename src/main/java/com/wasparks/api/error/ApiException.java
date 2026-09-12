package com.wasparks.api.error;

import lombok.Getter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every deliberate rejection this service makes. Carries the {@link ApiErrorCode} (which fixes the HTTP
 * status and both error dialects), an optional override message, structured {@code details} for the
 * {@code /v1} envelope, and any response headers the rejection owes the caller —
 * {@code Retry-After} on a 429, {@code X-Quota-Scope} on a quota block.
 *
 * <p>Headers travel on the exception rather than being set at the throw site because the throw site is
 * often an interceptor or a service several frames below the response; the advice that renders the
 * error is the only place guaranteed to still have the response in hand.
 */
@Getter
public class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final Map<String, Object> details = new LinkedHashMap<>();
    private final Map<String, String> headers = new HashMap<>();

    public ApiException(ApiErrorCode code) {
        this(code, code.getDefaultMessage());
    }

    public ApiException(ApiErrorCode code, String message) {
        super(message == null ? code.getDefaultMessage() : message);
        this.code = code;
    }

    public static ApiException of(ApiErrorCode code) {
        return new ApiException(code);
    }

    public static ApiException of(ApiErrorCode code, String message) {
        return new ApiException(code, message);
    }

    public ApiException withDetail(String key, Object value) {
        this.details.put(key, value);
        return this;
    }

    public ApiException withHeader(String name, String value) {
        this.headers.put(name, value);
        return this;
    }
}
