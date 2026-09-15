package com.wasparks.api.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * The partner half of a partner-key request (api-partner epic §B1).
 *
 * <p>Three ids, and the distinction between the last two is the whole of partner auth:
 *
 * <ul>
 *   <li>{@code partnerId} — who is calling. Fixed by the key row; a partner cannot name another.</li>
 *   <li>{@code ownerTenantId} — the partner's <em>own</em> tenant. Its plan is the pool every client
 *       draws on, its keys and webhooks are filed under it, and it is what an unqualified request acts
 *       on.</li>
 *   <li>{@code actingTenantId} — the client this one request is about, from {@code X-Tenant-Id}. Equal
 *       to {@code ownerTenantId} when the header is absent.</li>
 * </ul>
 *
 * <p>{@code actingTenantId} is <b>verified, not trusted</b>: {@link com.wasparks.api.partner
 * .PartnerTenantResolver} requires an ACTIVE {@code api_partner_tenants} row before it is ever put in
 * here, so by the time a controller holds this record the header has already been proved. That keeps
 * epic §0.8 true in its stronger form — the tenant still comes from the key, and the header can only
 * narrow it to something the key already reaches.
 *
 * <p>Serialized into Redis as part of {@link ApiPrincipal}, so it is a record and immutable.
 *
 * @param clientDailyCap the partner's own ceiling for this client
 *                       ({@code api_partner_tenants.messages_per_day_cap}), or null for "the pool is the
 *                       only limit". Carried here rather than looked up in the quota check because the
 *                       resolver has already read it out of the same cache entry that proved membership
 *                       — a second round trip on the hot path, for a number that changes twice a year,
 *                       would be work done per send for no gain.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerPrincipal(UUID partnerId, UUID ownerTenantId, UUID actingTenantId,
                               Integer clientDailyCap) {

    /** True when the request is about the partner's own tenant rather than one of its clients. */
    public boolean actingOnOwnTenant() {
        return ownerTenantId.equals(actingTenantId);
    }
}
