package com.wasparks.api.entity;

import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.enums.ApiKeyStatus;
import io.hypersistence.utils.hibernate.type.json.JsonType;
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
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A tenant-issued API key (epic §0.6). Written by this service.
 *
 * <p><b>The key itself is never stored.</b> {@code keyHash} is the SHA-256 hex of the full
 * {@code wsk_live_…} string and is the only way to find the row; the plaintext is returned exactly once,
 * at creation. {@code prefix} is the first 16 characters, kept solely so the UI can show a tenant which
 * key is which.
 *
 * <p>A key is a <b>distinct actor</b>, never an impersonated user: audit rows written through the
 * internal surface carry {@code actor: "API_KEY"} and this id, and {@code created_by} records only who
 * pressed the button in tenant-web.
 */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** P2 white-label. Nullable from day one so partner keys are a feature flag, not a migration (§0.8). */
    @Column(name = "partner_id")
    private UUID partnerId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** First 16 chars of the key, e.g. {@code wsk_live_a1b2c3} — display only, never a lookup on its own. */
    @Column(name = "prefix", nullable = false, length = 16)
    private String prefix;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 8)
    private ApiKeyMode mode;

    @Type(JsonType.class)
    @Column(name = "scopes", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Set<String> scopes = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ApiKeyStatus status;

    /** Touched at most once a minute per key (Redis SETNX guard) — not a write per request. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * A short-lived key minted for a tenant-web session rather than issued to a tenant (§D.3, 020a).
     *
     * <p>tenant-web's Developer section reads webhooks, account and usage, all of which are
     * key-authenticated — and a real key in the browser is a real key in an XSS payload. So the UI gets
     * a one-hour TEST key with two scopes and holds it in memory only. The row authenticates through the
     * identical path (that is the point), so this flag is the only thing separating it from a key the
     * tenant owns: it is hidden from {@code GET /v1/keys}, excluded from the plan's {@code max_keys},
     * and purged daily once expired.
     */
    @Column(name = "ui_session", nullable = false)
    @Builder.Default
    private boolean uiSession = false;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false, insertable = false)
    private Instant updatedAt;

    /** ACTIVE and not past {@code expires_at}. Tenant status is checked separately, against the tenant row. */
    public boolean isUsable(Instant now) {
        return status == ApiKeyStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }
}
