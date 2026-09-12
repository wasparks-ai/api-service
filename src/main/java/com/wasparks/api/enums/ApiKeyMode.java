package com.wasparks.api.enums;

/** Sandbox switch. A TEST key behaves identically end to end but tenants-service records a DRYRUN- wamid and never calls Meta (epic 0.10). */
public enum ApiKeyMode {
    LIVE,
    TEST
}
