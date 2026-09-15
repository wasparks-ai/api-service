package com.wasparks.api.enums;

/**
 * Lifecycle of a hosted setup link ({@code api_setup_links.status}, 021 §3).
 *
 * <p>Only {@code PENDING} is usable. tenants-service moves a link to {@code COMPLETED} when the customer
 * finishes Embedded Signup and to {@code EXPIRED} on the hourly sweep; this service sets
 * {@code CANCELLED} — explicitly, or implicitly when a newer link supersedes it (§B2: at most one live
 * link per customer).
 */
public enum SetupLinkStatus {
    PENDING,
    COMPLETED,
    EXPIRED,
    CANCELLED
}
