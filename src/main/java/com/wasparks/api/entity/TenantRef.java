package com.wasparks.api.entity;

import com.wasparks.api.enums.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.util.UUID;

/**
 * <b>Read-only projection</b> over the shared {@code tenants} table — admin-service owns every write
 * (shared-contracts §5). Only the two columns this service needs are mapped: the name, for
 * {@code GET /v1/account}, and the status, because a key belonging to a non-ACTIVE tenant must fail
 * authentication (epic §B2). {@code ddl-auto=validate} checks mapped columns exist and ignores the rest,
 * so mapping a narrow slice is safe and keeps this service out of tenant lifecycle concerns.
 *
 * <p>{@code status} is a real PostgreSQL enum type ({@code tenant_status}), not a VARCHAR — mapped
 * exactly as tenants-service maps it, or Hibernate's validation fails on the type mismatch.
 *
 * <p>No setters: nothing here may write this table.
 */
@Entity
@Table(name = "tenants")
@Getter
public class TenantRef {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "status", nullable = false, columnDefinition = "tenant_status")
    private TenantStatus status;
}
