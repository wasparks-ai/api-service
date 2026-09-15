package com.wasparks.api.enums;

/**
 * The partner's own switch on one of its clients ({@code api_partner_tenants.status}, 021 §2).
 *
 * <p>Independent of {@code tenants.status}, which is the platform's switch and is admin-owned. A partner
 * suspending a client that stopped paying <em>it</em> must not require us to suspend the tenant, and a
 * tenant we suspend must not become the partner's to un-suspend.
 */
public enum PartnerTenantStatus {
    ACTIVE,
    SUSPENDED
}
