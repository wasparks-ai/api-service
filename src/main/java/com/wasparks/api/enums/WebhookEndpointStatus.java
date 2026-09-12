package com.wasparks.api.enums;

/** PAUSED is set automatically after too many consecutive failures; DISABLED is the tenant turning it off. */
public enum WebhookEndpointStatus {
    ACTIVE,
    PAUSED,
    DISABLED
}
