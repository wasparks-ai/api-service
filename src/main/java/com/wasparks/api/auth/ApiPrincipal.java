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
 * <p><b>A partner key narrows this, it does not widen it.</b> The key row still fixes what the request
 * may reach — every tenant in {@code api_partner_tenants} for that partner. {@code X-Tenant-Id} picks
 * one of them, and {@code PartnerTenantResolver} has already proved the pick before {@link #partner} is
 * set, at which point {@code tenantId} <em>is</em> the acting tenant and every downstream caller — the
 * internal client, the quota counters, the template proxy — keeps reading the one field it always read.
 *
 * @param keyId     {@code api_keys.id}; the audit actor upstream, never a user id
 * @param tenantId  the only tenant this request may touch — the acting client on a partner key
 * @param partnerId {@code api_keys.partner_id}: set on a partner key, null on an ordinary tenant key
 * @param mode      LIVE or TEST (sandbox)
 * @param scopes    wire-form scope strings, e.g. {@code messages:send}
 * @param limits    plan ⊕ tenant overrides — the <b>partner's</b> plan on a partner key (§B1)
 * @param partner   the resolved partner context, or null on an ordinary tenant key
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiPrincipal(
        UUID keyId,
        UUID tenantId,
        UUID partnerId,
        ApiKeyMode mode,
        Set<String> scopes,
        EffectiveLimits limits,
        PartnerPrincipal partner) {

    /**
     * True when this key belongs to a partner. Checked against {@link #partner} rather than
     * {@link #partnerId}, because the two are only both set once the header has been resolved — a
     * principal straight out of the key cache has {@code partnerId} but no context yet, and treating it
     * as a partner request at that point would act on an unverified tenant.
     */
    public boolean isPartner() {
        return partner != null;
    }

    /** The partner's own tenant on a partner key; the key's tenant otherwise. Where the plan is read. */
    public UUID poolTenantId() {
        return partner == null ? tenantId : partner.ownerTenantId();
    }

    /** The same principal acting on {@code actingTenantId} instead of the key's own tenant. */
    public ApiPrincipal actingAs(PartnerPrincipal resolved) {
        return new ApiPrincipal(keyId, resolved.actingTenantId(), partnerId, mode, scopes, limits,
                resolved);
    }

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
