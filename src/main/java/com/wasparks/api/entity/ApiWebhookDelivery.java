package com.wasparks.api.entity;

import com.wasparks.api.enums.DeliveryStatus;
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
 * One attempt-bearing delivery of one outbox event to one endpoint (epic §B8). Written by this service.
 *
 * <p>There is a single row per (event, endpoint) pair; {@code attempt} counts up in place rather than
 * inserting a row per try, so the tenant-facing deliveries list stays one line per event and the
 * {@code next_attempt_at} partial index stays small. {@code error} is truncated to fit the 500-char
 * column — a stack trace is not what the tenant needs there.
 */
@Entity
@Table(name = "api_webhook_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiWebhookDelivery {

    /** Longest error text the column takes (020: VARCHAR(500)). */
    public static final int ERROR_MAX = 500;

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "endpoint_id", nullable = false)
    private UUID endpointId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "attempt", nullable = false)
    private Integer attempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeliveryStatus status;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "error", length = ERROR_MAX)
    private String error;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    /** Clip whatever the HTTP layer threw so a long upstream message cannot fail the UPDATE. */
    public void setErrorTruncated(String message) {
        if (message == null) {
            this.error = null;
        } else {
            this.error = message.length() <= ERROR_MAX ? message : message.substring(0, ERROR_MAX);
        }
    }
}
