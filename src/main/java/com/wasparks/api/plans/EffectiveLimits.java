package com.wasparks.api.plans;

import com.wasparks.api.enums.BillingModel;
import com.wasparks.api.enums.OveragePolicy;

/**
 * The limits actually in force for one tenant: the plan's columns with the tenant's
 * {@code tenant_api_plans.overrides} layered on top (epic §A).
 *
 * <p>A record, and immutable, because it is cached inside {@code ApiPrincipal} and read concurrently by
 * every request thread for that key.
 *
 * @param planCode             the plan the numbers came from, echoed in {@code GET /v1/account}
 * @param requestsPerMinute    fixed-window rate limit, per key (epic §B3)
 * @param messagesPerDay       message quota, per tenant
 * @param messagesPerMonth     message quota, per tenant
 * @param templateCreatesPerDay cap on {@code POST /v1/templates}
 * @param maxKeys              how many ACTIVE keys the tenant may hold
 * @param maxWebhookEndpoints  how many endpoints the tenant may register
 * @param overagePolicy        BLOCK rejects an over-quota send; WARN accepts and flags it
 * @param sandboxOnly          the plan forces every send down the DRYRUN path regardless of key mode
 * @param billingModel         FIXED (allowance) or METERED (per message) — api-partner epic §0.9
 * @param pricePerMessageMinor minor units on a METERED plan, null on a FIXED one
 * @param currency             ISO-4217, set with the price or not at all
 * @param partnerPlan          the allowance above <b>pools</b> across every client of the partner
 */
public record EffectiveLimits(
        String planCode,
        int requestsPerMinute,
        int messagesPerDay,
        int messagesPerMonth,
        int templateCreatesPerDay,
        int maxKeys,
        int maxWebhookEndpoints,
        OveragePolicy overagePolicy,
        boolean sandboxOnly,
        BillingModel billingModel,
        Long pricePerMessageMinor,
        String currency,
        boolean partnerPlan) {

    /**
     * Whether the day/month numbers mean anything to a human.
     *
     * <p>A METERED plan's allowance is set absurdly high precisely so it never binds, so reporting
     * "11 of 1,000,000" in the Partner console would be noise dressed as information. The console shows
     * a count on METERED and a meter on FIXED, and this is what it branches on.
     */
    public boolean hasAllowance() {
        return billingModel != BillingModel.METERED;
    }

    /**
     * The floor used when no plan can be resolved at all — no assignment <em>and</em> no row flagged
     * {@code is_default}, which means someone deleted the seeded FREE plan. Failing the request outright
     * would take the whole API down for a data-entry mistake in admin-webapp, so a request instead gets
     * the most conservative limits that still work, and {@code PlanResolver} logs a warning naming the
     * tenant so the misconfiguration is visible.
     */
    public static EffectiveLimits fallback() {
        return new EffectiveLimits("UNASSIGNED", 60, 200, 2000, 20, 3, 3, OveragePolicy.BLOCK, false,
                BillingModel.FIXED, null, null, false);
    }
}
