package com.wasparks.api.repository;

import com.wasparks.api.entity.TenantApiPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Read-only in this service — admin-service assigns plans (epic §0.5). */
public interface TenantApiPlanRepository extends JpaRepository<TenantApiPlan, UUID> {

    /**
     * The tenant's assignment, if one is in force right now.
     *
     * <p>The window is evaluated with {@code CURRENT_TIMESTAMP} — <b>the database's clock, not the
     * JVM's</b>. That matters because {@code starts_at} is written by admin-service with the database's
     * own {@code CURRENT_TIMESTAMP} default, so comparing it against {@code Instant.now()} here compares
     * two different clocks. A few seconds of skew between the application server and PostgreSQL is
     * ordinary, and the symptom would be silent and baffling: a plan an admin had just assigned would
     * appear not to exist, and the tenant would quietly fall back to the default plan's limits for as
     * long as the skew lasted. Asking one clock both questions removes the class of bug entirely.
     */
    @Query("SELECT a FROM TenantApiPlan a WHERE a.tenantId = :tenantId "
            + "AND (a.startsAt IS NULL OR a.startsAt <= CURRENT_TIMESTAMP) "
            + "AND (a.endsAt IS NULL OR a.endsAt > CURRENT_TIMESTAMP)")
    Optional<TenantApiPlan> findInForce(@Param("tenantId") UUID tenantId);
}
