package com.wasparks.api.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Wraps a request in {@link CachedBodyHttpServletRequest} when, and only when, it carries an
 * {@code Idempotency-Key} and has a body to hash.
 *
 * <p>Registered <b>after</b> Spring Security's chain (see {@code WebMvcConfig}), so an unauthenticated
 * request is rejected before its body is ever buffered — otherwise an anonymous caller could make this
 * service hold arbitrary bytes in memory just by sending them.
 */
public class BodyCachingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (shouldCache(request)) {
            chain.doFilter(new CachedBodyHttpServletRequest(request), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private boolean shouldCache(HttpServletRequest request) {
        String method = request.getMethod();
        boolean hasBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        String idempotencyKey = request.getHeader(IdempotencyService.HEADER);
        return hasBody && idempotencyKey != null && !idempotencyKey.isBlank();
    }
}
