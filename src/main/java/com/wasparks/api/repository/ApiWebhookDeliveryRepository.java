package com.wasparks.api.repository;

import com.wasparks.api.entity.ApiWebhookDelivery;
import com.wasparks.api.enums.DeliveryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApiWebhookDeliveryRepository extends JpaRepository<ApiWebhookDelivery, UUID> {

    /**
     * Due deliveries, claimed the same skip-locked way as the outbox so a second replica cannot pick up
     * a delivery this one is already POSTing — a duplicate webhook is visible to the customer.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT d FROM ApiWebhookDelivery d WHERE d.status = :status AND d.nextAttemptAt <= :now "
            + "ORDER BY d.nextAttemptAt ASC")
    List<ApiWebhookDelivery> claimDue(@Param("status") DeliveryStatus status,
                                      @Param("now") Instant now,
                                      Limit limit);

    /** The tenant-facing deliveries list: last N for one endpoint, newest first (epic §B7). */
    List<ApiWebhookDelivery> findByEndpointIdOrderByCreatedAtDesc(UUID endpointId, Limit limit);

    boolean existsByEndpointIdAndEventId(UUID endpointId, UUID eventId);
}
