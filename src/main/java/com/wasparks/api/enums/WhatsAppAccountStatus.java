package com.wasparks.api.enums;

/** Mirrors the shared whatsapp_account_status PG enum. DISCONNECTED means Meta rejected the token (190). */
public enum WhatsAppAccountStatus {
    ACTIVE,
    INACTIVE,
    DISCONNECTED
}
