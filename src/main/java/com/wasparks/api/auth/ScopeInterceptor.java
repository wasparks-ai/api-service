package com.wasparks.api.auth;

import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiredScope} second in the pipeline, before idempotency and quota can consume
 * anything on behalf of a request that is not allowed to make it.
 *
 * <p>See {@link RequiredScope} for why this sits alongside {@code @PreAuthorize} rather than replacing
 * it.
 */
@Component
public class ScopeInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiredScope required = method.getMethodAnnotation(RequiredScope.class);
        if (required == null) {
            return true;
        }

        ApiPrincipal principal = CurrentPrincipal.fromRequest(request);
        if (principal == null) {
            // A scoped endpoint reached without a key. Security would refuse it anyway; answering here
            // keeps the message consistent with every other key failure.
            throw ApiException.of(ApiErrorCode.INVALID_API_KEY);
        }

        String scope = required.value().wire();
        if (!principal.hasScope(scope)) {
            throw ApiException.of(ApiErrorCode.INSUFFICIENT_SCOPE,
                            "This API key is missing the " + scope + " scope.")
                    .withDetail("requiredScope", scope);
        }
        return true;
    }
}
