package com.wasparks.api.enums;

/** Revocation is permanent; a revoked key is never reactivated, a new one is issued. */
public enum ApiKeyStatus {
    ACTIVE,
    REVOKED
}
