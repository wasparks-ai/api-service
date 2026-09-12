package com.wasparks.api.plans;

import com.wasparks.api.entity.ApiPlan;
import com.wasparks.api.entity.TenantApiPlan;
import com.wasparks.api.enums.OveragePolicy;
import com.wasparks.api.repository.ApiPlanRepository;
import com.wasparks.api.repository.TenantApiPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a tenant's {@link EffectiveLimits}: plan row ⊕ per-tenant overrides, falling back to the
 * plan flagged {@code is_default} when the tenant has no assignment (epic §0.5).
 *
 * <p>An assignment whose {@code ends_at} has passed is treated as absent — the tenant drops back to the
 * default plan rather than keeping limits that have expired. {@code starts_at} is checked the same way,
 * so admin-webapp can schedule an upgrade ahead of time. Both dates are compared against the
 * <b>database's</b> clock rather than this process's; see {@code TenantApiPlanRepository.findInForce}.
 *
 * <p>There is no caching here on purpose: the result is cached one level up, inside the API-key principal
 * that {@code ApiKeyService} stores in Redis, so a resolved plan costs nothing on the hot path and the
 * two caches cannot disagree about how stale they are.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanResolver {

    private final TenantApiPlanRepository tenantApiPlanRepository;
    private final ApiPlanRepository apiPlanRepository;

    @Transactional(readOnly = true)
    public EffectiveLimits resolve(UUID tenantId) {
        // The date window is evaluated by PostgreSQL, not here — see the repository method for why.
        Optional<TenantApiPlan> assignment = tenantApiPlanRepository.findInForce(tenantId);

        Optional<ApiPlan> plan = assignment
                .flatMap(a -> apiPlanRepository.findById(a.getPlanId()))
                .filter(ApiPlan::isActive)
                .or(apiPlanRepository::findByIsDefaultTrue);

        if (plan.isEmpty()) {
            log.warn("No API plan resolved for tenant {} — no active assignment and no is_default plan. "
                    + "Falling back to conservative limits; seed a default plan in admin-webapp.", tenantId);
            return EffectiveLimits.fallback();
        }

        Map<String, Object> overrides = assignment.map(TenantApiPlan::getOverrides).orElse(Map.of());
        return apply(plan.get(), overrides == null ? Map.of() : overrides);
    }

    /**
     * Layer the overrides map over the plan. Keys are the camelCase field names
     * ({@code requestsPerMinute}, {@code overagePolicy}, …); anything absent or unparseable keeps the
     * plan's value, because a typo in a hand-edited JSON blob must not silently hand a tenant zero
     * requests per minute.
     */
    private EffectiveLimits apply(ApiPlan plan, Map<String, Object> overrides) {
        return new EffectiveLimits(
                plan.getCode(),
                intOr(overrides, "requestsPerMinute", plan.getRequestsPerMinute()),
                intOr(overrides, "messagesPerDay", plan.getMessagesPerDay()),
                intOr(overrides, "messagesPerMonth", plan.getMessagesPerMonth()),
                intOr(overrides, "templateCreatesPerDay", plan.getTemplateCreatesPerDay()),
                intOr(overrides, "maxKeys", plan.getMaxKeys()),
                intOr(overrides, "maxWebhookEndpoints", plan.getMaxWebhookEndpoints()),
                policyOr(overrides, plan.getOveragePolicy()),
                boolOr(overrides, "sandboxOnly", plan.isSandboxOnly()));
    }

    private int intOr(Map<String, Object> overrides, String key, Integer planValue) {
        int fallback = planValue == null ? 0 : planValue;
        Object raw = overrides.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                log.warn("Ignoring unparseable plan override {}={}", key, s);
            }
        }
        return fallback;
    }

    private boolean boolOr(Map<String, Object> overrides, String key, boolean planValue) {
        Object raw = overrides.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        return planValue;
    }

    private OveragePolicy policyOr(Map<String, Object> overrides, OveragePolicy planValue) {
        Object raw = overrides.get("overagePolicy");
        if (raw instanceof String s) {
            try {
                return OveragePolicy.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                log.warn("Ignoring unknown plan override overagePolicy={}", s);
            }
        }
        return planValue == null ? OveragePolicy.BLOCK : planValue;
    }
}
