package com.wasparks.api.error;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Renders an {@link ApiException} into whichever error dialect the path speaks. Both shapes are produced
 * here and nowhere else, so the Meta-compatibility promise (§0.4) is one function to audit.
 */
public final class ErrorBodies {

    /** Our code, echoed on both dialects so support can read it off a Meta-shaped response. */
    public static final String HEADER_ERROR = "X-WaSparks-Error";

    /** Everything under this prefix answers in Meta's error shape. */
    public static final String META_PREFIX = "/meta/";

    private ErrorBodies() {
    }

    /** True when the request path must be answered in Meta's dialect rather than the house envelope. */
    public static boolean isMetaPath(String requestUri) {
        return requestUri != null && requestUri.startsWith(META_PREFIX);
    }

    /**
     * The house envelope (epic §B7): {@code {"error":{"code","message","details"}}}. {@code details} is
     * omitted rather than sent empty — a client should not have to distinguish {@code {}} from absent.
     */
    public static Map<String, Object> v1(ApiException ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", ex.getCode().wire());
        error.put("message", ex.getMessage());
        if (!ex.getDetails().isEmpty()) {
            error.put("details", ex.getDetails());
        }
        return Map.of("error", error);
    }

    /**
     * Meta's Cloud API error shape. {@code fbtrace_id} is synthesised — we are not Meta and have no real
     * trace id, but the field is present in every genuine Cloud API error and client libraries read it,
     * so omitting it breaks deserialisers that treat it as required. Ours is prefixed {@code WS} so a
     * support ticket cannot mistake it for something Meta could look up.
     */
    public static Map<String, Object> meta(ApiException ex) {
        ApiErrorCode code = ex.getCode();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", ex.getMessage());
        error.put("type", code.getMetaType());
        error.put("code", code.getMetaCode());
        if (code.getMetaSubcode() != null) {
            error.put("error_subcode", code.getMetaSubcode());
        }
        error.put("fbtrace_id", traceId());
        if (!ex.getDetails().isEmpty()) {
            // Meta puts extra context under error_data; keeping ours there rather than at the top level
            // means a strict Meta-shaped deserialiser still parses the body.
            error.put("error_data", ex.getDetails());
        }
        return Map.of("error", error);
    }

    private static String traceId() {
        return "WS" + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }
}
