package com.wasparks.api.idempotency;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.queue.SendQueue;
import com.wasparks.api.util.Hashing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Idempotency-Key handling (epic §B4, §0.9).
 *
 * <p>The problem it solves is specific: a client POSTs a send, the connection drops before the 202
 * arrives, and the client retries. Without a record of the first attempt the customer receives the
 * message twice — and for a WhatsApp business message, a duplicate is a real cost and a real annoyance
 * to the recipient, not a cosmetic glitch. With one, the retry gets the original response back and no
 * second message exists.
 *
 * <p>The record is keyed by <b>key id and the caller's idempotency key together</b>. Scoping to the API
 * key is what stops one tenant's choice of key string (often a sequential order number) colliding with
 * another's.
 *
 * <h2>The three outcomes</h2>
 * <ul>
 *   <li><b>No record</b> — reserve one, run the handler, store the response.</li>
 *   <li><b>Record with the same body hash</b> — replay the stored response verbatim, with
 *       {@code Idempotent-Replayed: true}. Same key, same request, same answer.</li>
 *   <li><b>Record with a different body hash</b> — {@code 422 idempotency_conflict}. The client has
 *       reused a key for a different request, which is a bug on their side that we must not paper over
 *       by silently sending something different from what the key promised.</li>
 * </ul>
 *
 * <p>An in-flight record (reserved, no response yet) answers {@code 409 conflict}. That case is not in
 * the epic; the alternative — letting the second request through — would send twice under exactly the
 * concurrency the feature exists to prevent.
 *
 * <h2>Redis down</h2>
 * The limiters fail open; this does not. {@link #beginOrReplay} throws
 * {@code 503 queue_unavailable} with {@code Retry-After: 5} when the store cannot be reached, because
 * accepting a send it cannot deduplicate is how a customer receives the same message twice. Storing and
 * releasing a record, by contrast, are still best-effort: by then the send has been accepted, and the
 * worst a lost record causes is a retry that is refused as in-flight or runs again — never a silent
 * duplicate promised as deduplicated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String HEADER_REPLAYED = "Idempotent-Replayed";
    /** Request attribute carrying the reservation from the interceptor to the controller. */
    public static final String ATTRIBUTE = "wasparks.idempotency";

    /** Epic §B4: "≤128 chars". Longer is a client bug, and unbounded keys are a Redis memory hazard. */
    public static final int MAX_KEY_LENGTH = 128;

    private static final String PREFIX = "idem:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${app.api.idempotency.ttl-hours}")
    private long ttlHours;

    /**
     * A stored idempotency record.
     *
     * @param bodyHash     sha256 of the exact request bytes
     * @param status       HTTP status of the original response
     * @param responseBody the original response body, verbatim JSON
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Record(String bodyHash, int status, String responseBody) {

        /** A reservation: the request is running, the response is not known yet. */
        static Record inFlight(String bodyHash) {
            return new Record(bodyHash, 0, null);
        }

        public boolean isInFlight() {
            return status == 0 || responseBody == null;
        }
    }

    /** What the interceptor hands to the controller so it can store its response under the same key. */
    public record Reservation(String redisKey, String bodyHash) {
    }

    /**
     * Check for a prior record and reserve the key if there is none.
     *
     * @return the stored record to replay, or empty when the handler should run
     */
    public Optional<Record> beginOrReplay(ApiPrincipal principal, String idempotencyKey, byte[] body,
                                          java.util.function.Consumer<Reservation> onReserved) {
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be " + MAX_KEY_LENGTH + " characters or fewer.");
        }

        String redisKey = PREFIX + principal.keyId() + ":" + Hashing.sha256Hex(idempotencyKey);
        String bodyHash = Hashing.sha256Hex(new String(body, StandardCharsets.UTF_8));

        Boolean reserved;
        try {
            reserved = redis.opsForValue().setIfAbsent(redisKey,
                    serialize(Record.inFlight(bodyHash)), Duration.ofHours(ttlHours));
        } catch (Exception e) {
            // Redis down. Refuse the send rather than proceeding without replay protection. The earlier
            // ruling was to fail open here, matching the limiters; it was wrong. Degrading to
            // at-least-once on a WhatsApp send means a client retrying after a dropped connection puts a
            // second message in front of a real customer — and with Redis gone the job could not have
            // been enqueued anyway, so the request was going to be refused one step later regardless.
            // Idempotency therefore never degrades: it is enforced, or the send is refused.
            log.error("Idempotency store unavailable — refusing the send rather than risking a "
                    + "duplicate: {}", e.getMessage());
            throw SendQueue.queueUnavailable(
                    "The send queue is unavailable, so this request could not be made idempotent. "
                            + "The message was not accepted — retry shortly.");
        }

        if (Boolean.TRUE.equals(reserved)) {
            onReserved.accept(new Reservation(redisKey, bodyHash));
            return Optional.empty();
        }

        Record existing = read(redisKey).orElse(null);
        if (existing == null) {
            // The record expired between SETNX and GET. Treat as fresh and take the reservation.
            onReserved.accept(new Reservation(redisKey, bodyHash));
            return Optional.empty();
        }

        if (!existing.bodyHash().equals(bodyHash)) {
            throw ApiException.of(ApiErrorCode.IDEMPOTENCY_CONFLICT)
                    .withDetail("idempotencyKey", idempotencyKey);
        }
        if (existing.isInFlight()) {
            throw ApiException.of(ApiErrorCode.CONFLICT,
                            "A request with this Idempotency-Key is still in flight. "
                                    + "Retry once it completes.")
                    .withDetail("idempotencyKey", idempotencyKey);
        }
        return Optional.of(existing);
    }

    /** Store the handler's response so a later retry with the same key replays it. */
    public void complete(Reservation reservation, int status, Object responseBody) {
        if (reservation == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(responseBody);
            redis.opsForValue().set(reservation.redisKey(),
                    serialize(new Record(reservation.bodyHash(), status, json)),
                    Duration.ofHours(ttlHours));
        } catch (Exception e) {
            log.warn("Could not store idempotency response: {}", e.getMessage());
        }
    }

    /**
     * Release a reservation whose handler failed.
     *
     * <p>Without this, a send that errored would leave an in-flight record for 24 hours and every retry
     * with the same key — exactly what a well-behaved client does after a 500 — would be refused. A
     * failed attempt should not consume the key.
     */
    public void release(Reservation reservation) {
        if (reservation == null) {
            return;
        }
        try {
            redis.delete(reservation.redisKey());
        } catch (Exception e) {
            log.warn("Could not release idempotency reservation: {}", e.getMessage());
        }
    }

    private Optional<Record> read(String redisKey) {
        try {
            String raw = redis.opsForValue().get(redisKey);
            return raw == null ? Optional.empty()
                    : Optional.of(objectMapper.readValue(raw, Record.class));
        } catch (Exception e) {
            log.warn("Unreadable idempotency record at {}: {}", redisKey, e.getMessage());
            return Optional.empty();
        }
    }

    private String serialize(Record record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize idempotency record", e);
        }
    }
}
