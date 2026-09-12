package com.wasparks.api.entity;

import com.wasparks.api.enums.WhatsAppAccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * <b>Read-only projection</b> over the shared {@code whatsapp_accounts} table — admin-service and
 * tenants-service own the writes (shared-contracts §5, epic §C3).
 *
 * <p>It backs exactly one decision: does this {@code phone_number_id} belong to an ACTIVE account of the
 * key's tenant (epic §B5)? That check is local and cached for 60s, so the common case costs no upstream
 * call. The token columns are mapped so the cheap {@code tokenStatus} in {@code GET /v1/account} can be
 * derived without one either.
 *
 * <p><b>{@code access_token} is deliberately NOT mapped.</b> This service never calls Meta (epic §0.1)
 * and so never needs to decrypt a tenant's token; not mapping the column means it cannot leak through
 * a stray log line or serializer here.
 *
 * <p>No setters: nothing here may write this table.
 */
@Entity
@Table(name = "whatsapp_accounts")
@Getter
public class WhatsAppAccountRef {

    /** An account whose token is within this window of expiry reports EXPIRING (internal.md). */
    public static final int EXPIRING_WITHIN_DAYS = 7;

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "waba_id", nullable = false)
    private String wabaId;

    @Column(name = "phone_number_id", nullable = false)
    private String phoneNumberId;

    @Column(name = "phone_number", nullable = false, length = 50)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "status", nullable = false, columnDefinition = "whatsapp_account_status")
    private WhatsAppAccountStatus status;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "token_last_verified_at")
    private Instant tokenLastVerifiedAt;

    @Column(name = "token_error_code", length = 16)
    private String tokenErrorCode;
}
