package com.wasparks.api.repository;

import com.wasparks.api.entity.TenantRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Read-only projection over the shared {@code tenants} table. This service never writes it. */
public interface TenantRefRepository extends JpaRepository<TenantRef, UUID> {
}
