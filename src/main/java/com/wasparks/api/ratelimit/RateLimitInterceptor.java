package com.wasparks.api.ratelimit;

import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.usage.UsageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * First interceptor in the chain (epic §B3): count the request, set the headers, reject when over.
 *
 * <p>The headers are written on the way <b>in</b>, before the handler runs, which is what makes the
 * epic's "every response, including errors, carries {@code X-RateLimit-*}" true. Setting them after the
 * handler would miss every path that throws — and a client being told it is over quota most needs to
 * know how much of its request allowance is left.
 *
 * <p>Running first also means a rejected request is counted but does no other work: no scope lookup, no
 * idempotency record, no quota reservation to unwind. That ordering is why a client hammering a
 * rate-limited endpoint costs one Redis INCR rather than a transaction.
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;
    private final UsageService usageService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ApiPrincipal principal = CurrentPrincipal.fromRequest(request);
        if (principal == null) {
            // Not key-authenticated — a public path, or the JWT chain. Nothing to limit.
            return true;
        }

        RateLimitService.Decision decision = rateLimitService.check(principal);
        response.setHeader(RateLimitService.HEADER_LIMIT, String.valueOf(decision.limit()));
        response.setHeader(RateLimitService.HEADER_REMAINING, String.valueOf(decision.remaining()));
        response.setHeader(RateLimitService.HEADER_RESET, String.valueOf(decision.resetAt()));

        usageService.increment(principal.tenantId(), principal.keyId(), UsageService.FIELD_REQUESTS);

        if (!decision.allowed()) {
            usageService.increment(principal.tenantId(), principal.keyId(),
                    UsageService.FIELD_RATE_LIMITED);
            throw ApiException.of(ApiErrorCode.RATE_LIMITED,
                            "Rate limit of " + decision.limit() + " requests per minute exceeded.")
                    .withHeader(RateLimitService.HEADER_RETRY_AFTER,
                            String.valueOf(decision.retryAfter()))
                    .withDetail("limit", decision.limit())
                    .withDetail("resetAt", decision.resetAt());
        }
        return true;
    }
}
