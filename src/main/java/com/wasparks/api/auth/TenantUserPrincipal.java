package com.wasparks.api.auth;

import java.util.UUID;

/**
 * The caller on {@code /v1/keys/**} — a real tenant-web user holding a tenants-service access token
 * (epic §0.12). This is the <b>only</b> surface on this service that accepts a JWT.
 *
 * <p>Nothing is loaded from the database to build it: the claims are the whole principal. tenants-service
 * signed them, the signature has been verified, and re-reading {@code tenant_users} here would mean this
 * service owning an opinion about tenant user state that it has no business having.
 *
 * @param userId   {@code sub} — recorded as {@code api_keys.created_by}
 * @param tenantId {@code tenantId} claim — the tenant whose keys may be managed
 * @param role     {@code role} claim, already checked to be OWNER or ADMIN
 * @param email    {@code email} claim, for log lines only
 */
public record TenantUserPrincipal(UUID userId, UUID tenantId, String role, String email) {
}
