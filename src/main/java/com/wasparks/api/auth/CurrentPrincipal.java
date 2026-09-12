package com.wasparks.api.auth;

import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The one way to reach the authenticated caller. Controllers, interceptors and services all go through
 * here rather than each reaching into {@code SecurityContextHolder} and unwrapping the token themselves —
 * so "where does {@code tenantId} come from?" has exactly one answer (epic §0.8).
 */
public final class CurrentPrincipal {

    /**
     * Request attribute holding the {@link ApiPrincipal}. The interceptors run inside the request but
     * outside the security filter chain's reach in some error paths; stashing it on the request means a
     * later stage cannot be surprised by a cleared {@code SecurityContext}.
     */
    public static final String ATTRIBUTE = "wasparks.apiPrincipal";

    private CurrentPrincipal() {
    }

    /** The key-authenticated caller, or empty if this request is not key-authenticated. */
    public static ApiPrincipal apiOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof ApiKeyAuthentication keyAuth) {
            return keyAuth.getPrincipal();
        }
        return null;
    }

    /** The key-authenticated caller, or {@code 401 invalid_api_key}. */
    public static ApiPrincipal api() {
        ApiPrincipal principal = apiOrNull();
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.INVALID_API_KEY);
        }
        return principal;
    }

    /** As {@link #apiOrNull()}, but reading the request attribute the auth filter stashed. */
    public static ApiPrincipal fromRequest(HttpServletRequest request) {
        Object stashed = request.getAttribute(ATTRIBUTE);
        if (stashed instanceof ApiPrincipal principal) {
            return principal;
        }
        ApiPrincipal principal = apiOrNull();
        if (principal != null) {
            request.setAttribute(ATTRIBUTE, principal);
        }
        return principal;
    }

    /** The tenant-web user on {@code /v1/keys/**}, or {@code 401}. */
    public static TenantUserPrincipal tenantUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantUserPrincipal user) {
            return user;
        }
        throw ApiException.of(ApiErrorCode.UNAUTHORIZED);
    }
}
