package com.wasparks.api.repository;

import com.wasparks.api.entity.ApiPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only in this service — admin-service owns plan writes (epic §0.5). No save/delete is called here;
 * the inherited methods exist because {@code JpaRepository} brings them, not because they are used.
 */
public interface ApiPlanRepository extends JpaRepository<ApiPlan, UUID> {

    /** The fallback for a tenant with no assignment. A partial unique index guarantees at most one row. */
    Optional<ApiPlan> findByIsDefaultTrue();

    Optional<ApiPlan> findByCode(String code);
}
