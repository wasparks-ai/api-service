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
 * White-label partner — <b>P2, schema only</b> (epic §1). Mapped now for one reason: {@code api_keys} and
 * {@code api_webhook_endpoints} carry a nullable {@code partner_id} FK from day one, and a partner key
 * resolving its partner must not wait on a migration. Nothing in P1 writes this table (admin-service will).
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

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false, insertable = false)
    private Instant updatedAt;
}
