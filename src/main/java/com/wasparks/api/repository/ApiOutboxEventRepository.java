package com.wasparks.api.repository;

import com.wasparks.api.entity.ApiOutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ApiOutboxEventRepository extends JpaRepository<ApiOutboxEvent, UUID> {

    /**
     * The poller's claim query (epic §B8). Three things are load-bearing here:
     *
     * <ul>
     *   <li>{@code FOR UPDATE SKIP LOCKED} (the PESSIMISTIC_WRITE lock plus the skip-locked hint) means a
     *       second replica takes the <em>next</em> batch instead of blocking on this one. ShedLock already
     *       keeps the job single-instance, so this is the belt to that braces — and it is what makes the
     *       poller safe to scale out later without revisiting it.</li>
     *   <li>{@code created_at <= now} is what makes the sandbox synthesiser work: a post-dated synthetic
     *       event is invisible until its moment arrives (see {@link ApiOutboxEvent#getCreatedAt()}).</li>
     *   <li>Ordering by {@code created_at} keeps a tenant's message events in the order they happened, so
     *       a client does not see {@code delivered} before {@code sent}.</li>
     * </ul>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT e FROM ApiOutboxEvent e WHERE e.publishedAt IS NULL AND e.createdAt <= :now "
            + "ORDER BY e.createdAt ASC")
    List<ApiOutboxEvent> claimUnpublished(@Param("now") Instant now, Limit limit);

    /**
     * The sandbox synthesiser's idempotency guard (epic §0.10, amended): has this message already got a
     * delivery receipt, or already failed?
     *
     * <p>The poller claims and marks published in one transaction, so a {@code message.sent} is normally
     * processed once — but "normally" here would mean a duplicate webhook at the customer, and the same
     * check is what keeps the {@code never after message.failed} half of the rule true even if upstream
     * ever ordered the two events unexpectedly.
     */
    boolean existsByAggregateIdAndEventTypeIn(UUID aggregateId, Collection<String> eventTypes);

    long countByPublishedAtIsNull();
}
