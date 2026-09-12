package com.wasparks.api.quota;

import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.usage.UsageService;
import com.wasparks.api.webhook.WebhookEventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Last in the pipeline (epic hand-off §4), on send endpoints only: reserve one message against the
 * tenant's daily and monthly quota.
 *
 * <p>Reserving <b>before</b> the handler runs is what makes the limit real — the alternative, counting
 * after a successful enqueue, lets a burst of concurrent sends all pass the check and overshoot. The
 * reservation is released in {@code afterCompletion} whenever the request did not end in a 2xx, so a
 * send rejected by the upstream preflight costs the customer nothing.
 *
 * <p>Running after idempotency means a replayed request does not reserve a second message — which is the
 * entire point of the ordering the epic specifies.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuotaInterceptor implements HandlerInterceptor {

    /** Request attribute marking that this request holds a reservation to release. */
    public static final String ATTRIBUTE_RESERVED = "wasparks.quotaReserved";

    /** One {@code quota.exceeded} webhook per tenant per day, guarded by this Redis key. */
    private static final String WARNED_PREFIX = "qwarn:";

    private final QuotaService quotaService;
    private final UsageService usageService;
    private final WebhookEventPublisher eventPublisher;
    private final StringRedisTemplate redis;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ApiPrincipal principal = CurrentPrincipal.fromRequest(request);
        if (principal == null) {
            return true;
        }

        QuotaService.Decision decision = quotaService.reserve(principal);
        if (!decision.allowed()) {
            throw decision.toException();
        }

        request.setAttribute(ATTRIBUTE_RESERVED, Boolean.TRUE);

        if (decision.warning()) {
            response.setHeader(QuotaService.HEADER_QUOTA_WARNING, "exceeded");
            response.setHeader(QuotaService.HEADER_QUOTA_SCOPE, decision.scope());
            emitWarningOncePerDay(principal.tenantId(), decision);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        if (!Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE_RESERVED))) {
            return;
        }
        ApiPrincipal principal = CurrentPrincipal.fromRequest(request);
        if (principal == null) {
            return;
        }

        boolean accepted = ex == null && response.getStatus() >= 200 && response.getStatus() < 300;
        if (accepted) {
            usageService.increment(principal.tenantId(), principal.keyId(),
                    UsageService.FIELD_MESSAGES_ACCEPTED);
        } else {
            // The send was reserved but never accepted — give the slot back.
            quotaService.release(principal.tenantId());
        }
    }

    /**
     * Fire {@code quota.exceeded} at most once per tenant per calendar day.
     *
     * <p>A WARN-plan tenant that is over its allowance is over it for every remaining send of the day.
     * Without the guard, an integration doing a thousand sends would deliver a thousand identical
     * webhooks — which is how a helpful notification turns into the thing that takes the customer's
     * endpoint down.
     */
    private void emitWarningOncePerDay(UUID tenantId, QuotaService.Decision decision) {
        try {
            String key = WARNED_PREFIX + tenantId + ":" + decision.scope() + ":"
                    + LocalDate.now(ZoneOffset.UTC);
            Boolean first = redis.opsForValue().setIfAbsent(key, "1", Duration.ofDays(2));
            if (Boolean.TRUE.equals(first)) {
                eventPublisher.publishQuotaExceeded(tenantId, decision.scope(), decision.limit());
            }
        } catch (Exception e) {
            log.warn("Could not emit quota.exceeded for tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
