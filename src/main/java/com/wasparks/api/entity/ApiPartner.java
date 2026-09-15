package com.wasparks.api.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A white-label partner: a tenant with one of these rows pointing at it (api-partner epic §0.1).
 *
 * <p><b>Read-only here.</b> admin-service owns every write — an admin flags a tenant as a partner in
 * admin-webapp, and a partner cannot make itself one. This service reads the row on every partner-key
 * request (to resolve the owner tenant and the branding) and on the Partner console's "am I a partner"
 * check, which goes to admin-service's {@code /internal/v1/partners/by-owner/{tenantId}} rather than
 * being derived here, so that one service decides what a partner is.
 *
 * <p>{@code branding} is the five fixed keys admin-service validates — {@code productName},
 * {@code logoUrl}, {@code primaryColor}, {@code supportEmail}, {@code supportUrl} — with absent keys
 * omitted rather than stored null. It is rendered on the hosted setup page and on partner-scoped emails,
 * never here.
 */
@Entity
@Table(name = "api_partners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiPartner {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Type(JsonType.class)
    @Column(name = "branding", columnDefinition = "jsonb")
    private Map<String, Object> branding;

    @Column(name = "billing_mode", nullable = false, length = 16)
    private String billingMode;

    @Column(name = "meta_app_id", length = 64)
    private String metaAppId;

    @Column(name = "meta_es_config_id", length = 64)
    private String metaEsConfigId;

    /** The tenant this partner <em>is</em> (021 §1). UNIQUE: a tenant is at most one partner. */
    @Column(name = "owner_tenant_id", nullable = false)
    private UUID ownerTenantId;

    @Column(name = "support_email", length = 255)
    private String supportEmail;

    /**
     * Where this partner's events go by default: {@code PARTNER} (one set of endpoints for every client,
     * the shape both launch partners asked for) or {@code CLIENT} (per-client endpoints only). Held as a
     * String rather than an enum because admin-service owns the vocabulary and this service only reports
     * it — a value added there must not stop a partner-key request resolving here.
     */
    @Column(name = "webhook_scope", nullable = false, length = 16)
    private String webhookScope;

    /** The admin who flagged the tenant. Null for a row created before the column existed. */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false, insertable = false)
    private Instant updatedAt;
}
