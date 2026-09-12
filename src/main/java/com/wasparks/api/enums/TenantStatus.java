package com.wasparks.api.enums;

/** Mirrors the shared tenant_status PG enum. Only an ACTIVE tenant may use the API. */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED,
    INACTIVE
}
