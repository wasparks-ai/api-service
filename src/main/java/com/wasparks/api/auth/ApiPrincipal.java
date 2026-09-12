package com.wasparks.api.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.plans.EffectiveLimits;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller on every key-authenticated request (epic §B2).
 *
 * <p><b>{@code tenantId} comes from the key row and from nowhere else</b> (epic §0.8) — never from a
 * path, body or header. That single rule is what makes cross-tenant access impossible by construction
 * rather than by remembering to check; a request cannot name a tenant it does not hold a key for.
 *
 * <p>Serialized into Redis as the cached key lookup, so it is a record (immutable, safely shared across
 * request threads) and carries the resolved limits with it — resolving the plan is part of resolving the
 * key, so a cache hit costs no database work at all.
 *
 * @param keyId     {@code api_keys.id}; the audit actor upstream, never a user id
 * @param tenantId  the only tenant this request may touch
 * @param partnerId P2 white-label, null in P1
 * @param mode      LIVE or TEST (sandbox)
 * @param scopes    wire-form scope strings, e.g. {@code messages:send}
 * @param limits    plan ⊕ tenant overrides
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiPrincipal(
        UUID keyId,
        UUID tenantId,
        UUID partnerId,
        ApiKeyMode mode,
        Set<String> scopes,
        EffectiveLimits limits) {

    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }

    /** TEST keys, and any key on a sandbox-only plan, never reach Meta (epic §0.10). */
    public boolean isSandbox() {
        return mode == ApiKeyMode.TEST || (limits != null && limits.sandboxOnly());
    }

    /** The mode sent upstream: a sandbox-only plan downgrades a LIVE key to a dry run. */
    public ApiKeyMode effectiveMode() {
        return isSandbox() ? ApiKeyMode.TEST : ApiKeyMode.LIVE;
    }
}
