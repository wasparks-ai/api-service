package com.wasparks.api.v1;

import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.CurrentPrincipal;
import com.wasparks.api.auth.RequiredScope;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.internal.InternalDtos;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import com.wasparks.api.plans.EffectiveLimits;
import com.wasparks.api.quota.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /v1/account} (epic §B7) — who am I, what can I send, and how much is left.
 *
 * <p>One call that answers the three questions an integration asks at start-up, and the endpoint a
 * developer hits first to confirm their key works. It joins two sources: the numbers and their health
 * come from tenants-service, while the plan and usage are this service's own and are never asked for
 * upstream.
 *
 * <p>{@code tokenStatus} is surfaced prominently because it is the failure everyone hits eventually. A
 * WhatsApp access token expires, sends start returning 409, and without this field an integration has no
 * way to see it coming. {@code EXPIRING} means inside seven days — enough warning to act.
 */
@RestController
@RequestMapping("/v1/account")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Account", description = "Your numbers, plan and usage.")
public class AccountController {

    private final InternalTenantsClient tenantsClient;
    private final QuotaService quotaService;

    @GetMapping
    @RequiredScope(Scope.ACCOUNT_READ)
    @PreAuthorize("hasAuthority('SCOPE_account:read')")
    @Operation(summary = "Get account, plan and usage",
            description = """
                    Your WhatsApp numbers with their quality rating, messaging tier and token status,
                    plus the plan limits in force and how much of today's and this month's message
                    allowance you have used.

                    Check `tokenStatus` here: `EXPIRING` means a number's access token expires within
                    seven days and sends will start failing with `409 account_disconnected` once it
                    does.""")
    public Map<String, Object> get() {
        ApiPrincipal principal = CurrentPrincipal.api();

        InternalDtos.AccountResponse upstream;
        try {
            upstream = tenantsClient.getAccount(principal);
        } catch (UpstreamRejectedException rejected) {
            throw rejected.toApiException();
        } catch (UpstreamUnavailableException unavailable) {
            throw ApiException.of(ApiErrorCode.UPSTREAM_UNAVAILABLE);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant", Map.of("name", upstream.tenantName() == null ? "" : upstream.tenantName()));
        body.put("tokenStatus", upstream.tokenStatus());
        body.put("phoneNumbers", phoneNumbers(upstream));
        body.put("plan", plan(principal.limits()));
        body.put("mode", principal.mode().name());
        body.put("usage", usage(principal));
        return body;
    }

    private List<Map<String, Object>> phoneNumbers(InternalDtos.AccountResponse upstream) {
        List<Map<String, Object>> numbers = new ArrayList<>();
        if (upstream.phoneNumbers() == null) {
            return numbers;
        }
        for (InternalDtos.PhoneNumber number : upstream.phoneNumbers()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("phoneNumberId", number.phoneNumberId());
            entry.put("display", number.display());
            entry.put("displayName", number.displayName());
            entry.put("status", number.status());
            entry.put("tokenStatus", number.tokenStatus());
            entry.put("tokenExpiresAt", number.tokenExpiresAt());

            // Flatten the two health fields an API client can act on. The rest of the health blob
            // (warm-up day, fetch times) is operational detail that belongs in the app, not here.
            Map<String, Object> health = number.health();
            entry.put("qualityRating", health == null ? null : health.get("qualityRating"));
            entry.put("messagingTier", health == null ? null : health.get("messagingTier"));
            numbers.add(entry);
        }
        return numbers;
    }

    private Map<String, Object> plan(EffectiveLimits limits) {
        Map<String, Object> planLimits = new LinkedHashMap<>();
        planLimits.put("requestsPerMinute", limits.requestsPerMinute());
        planLimits.put("messagesPerDay", limits.messagesPerDay());
        planLimits.put("messagesPerMonth", limits.messagesPerMonth());
        planLimits.put("templateCreatesPerDay", limits.templateCreatesPerDay());
        planLimits.put("maxKeys", limits.maxKeys());
        planLimits.put("maxWebhookEndpoints", limits.maxWebhookEndpoints());
        planLimits.put("overagePolicy", limits.overagePolicy().name());

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("code", limits.planCode());
        plan.put("sandboxOnly", limits.sandboxOnly());
        plan.put("limits", planLimits);
        return plan;
    }

    /**
     * Usage straight from the Redis counters, not from {@code api_usage_daily}.
     *
     * <p>The database table lags by up to ten minutes because that is the flush interval; a developer
     * watching their own quota while testing needs the number that the next send will actually be
     * checked against, not the one from the last flush.
     */
    private Map<String, Object> usage(ApiPrincipal principal) {
        QuotaService.Usage current = quotaService.peek(principal.tenantId());
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("messagesToday", current.today());
        usage.put("messagesThisMonth", current.month());
        usage.put("messagesRemainingToday",
                Math.max(0, principal.limits().messagesPerDay() - current.today()));
        usage.put("messagesRemainingThisMonth",
                Math.max(0, principal.limits().messagesPerMonth() - current.month()));
        return usage;
    }
}
