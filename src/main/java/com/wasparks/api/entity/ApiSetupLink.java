package com.wasparks.api.entity;

import com.wasparks.api.enums.SetupLinkStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A hosted setup link (021 §3): the URL a partner hands its customer so the customer can connect their
 * own WhatsApp number without ever having a WaSparks account.
 *
 * <p><b>Read-only here.</b> tenants-service mints the row (its {@code POST /internal/v1/setup-links}
 * returns the 32-character token exactly once), completes it, and expires it on the hourly sweep. This
 * service asks for links to be created and cancelled over that same internal surface and maps the table
 * only so the Partner console and {@code GET /v1/customers/{id}/setup-links/{linkId}} can read a link's
 * state without a round trip per row.
 *
 * <p>{@code token_hash} is the SHA-256 of the token and the only form that is stored — a dump of this
 * table yields no working link (the 017 password-reset pattern). It is mapped because the column is
 * {@code NOT NULL} and Hibernate validates it, not because anything here reads it.
 */
@Entity
@Table(name = "api_setup_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiSetupLink {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "partner_id", nullable = false)
    private UUID partnerId;

    /** The <b>customer's</b> tenant, not the partner's — a link connects a client's number. */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "success_url", nullable = false, length = 2048)
    private String successUrl;

    @Column(name = "failure_url", nullable = false, length = 2048)
    private String failureUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SetupLinkStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Set by tenants-service when the customer finishes Embedded Signup. */
    @Column(name = "phone_number_id", length = 255)
    private String phoneNumberId;

    @Column(name = "waba_id", length = 255)
    private String wabaId;

    @Column(name = "created_by_key")
    private UUID createdByKey;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false, insertable = false)
    private Instant updatedAt;
}
