package com.wasparks.api.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The {@code sends} Redis Stream — producer side, and the group bootstrap (epic §B6).
 *
 * <p>A stream rather than a list because the work must survive a worker dying mid-send. A consumer group
 * tracks what has been handed out and not yet acknowledged, so an entry read by a process that never
 * came back is still there to be reclaimed; a {@code LPOP} would have lost it the moment it was read.
 *
 * <p>{@code MAXLEN ~ 100000} trims the stream approximately (to the nearest node boundary), which is
 * dramatically cheaper than exact trimming and is the right trade for a cap that exists to bound memory
 * rather than to hold an exact number. The cap is far above any real backlog — if it is ever reached,
 * the queue is so far behind that losing the oldest entries is not the problem.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SendQueue {

    /** Field name inside the stream entry; the value is the JSON job. */
    public static final String FIELD_JOB = "job";

    /** {@code Retry-After} on a {@code queue_unavailable} refusal — long enough for a Redis failover. */
    public static final String RETRY_AFTER_SECONDS = "5";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${app.queue.stream}")
    private String stream;

    @Value("${app.queue.group}")
    private String group;

    @Value("${app.queue.max-len}")
    private long maxLen;

    /**
     * Create the consumer group if it is not there.
     *
     * <p>{@code MKSTREAM} matters: on a fresh Redis the stream does not exist yet, and creating the group
     * against a missing key fails. Creating it at {@code $} (not {@code 0}) means a brand-new group does
     * not try to replay history it has no business processing.
     *
     * <p>An existing group makes this a no-op — {@code BUSYGROUP} is the expected answer on every restart
     * after the first, not an error.
     */
    @PostConstruct
    public void ensureGroup() {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("$"), group);
            log.info("Created consumer group {} on stream {}", group, stream);
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("BUSYGROUP")) {
                log.debug("Consumer group {} already exists on stream {}", group, stream);
            } else {
                // Redis may simply not be up yet at startup. The workers retry, and a genuinely broken
                // Redis will make itself known on the first send.
                log.warn("Could not create consumer group {} on {}: {}", group, stream, message);
            }
        }
    }

    /**
     * Enqueue an accepted send.
     *
     * <p>This is the last step of accepting a message and the point of no return: once the entry is on
     * the stream the client has been told 202 and the message <em>will</em> be attempted. A failure here
     * therefore has to reach the client as a 503 rather than a 202, which is why it throws rather than
     * logging — the alternative is a message that was promised and never sent.
     *
     * <p>The code is {@code queue_unavailable} with {@code Retry-After: 5}, distinct from
     * {@code upstream_unavailable}: nothing is wrong with tenants-service or with Meta, our own Redis is
     * unreachable, and five seconds is the honest hint for a Redis that is restarting or failing over.
     */
    public RecordId enqueue(SendJob job) {
        try {
            String json = objectMapper.writeValueAsString(job);
            MapRecord<String, String, String> record = StreamRecords
                    .mapBacked(Map.of(FIELD_JOB, json))
                    .withStreamKey(stream);
            RecordId id = redis.opsForStream().add(record);
            log.debug("Enqueued send {} as {}", job.messageId(), id);
            return id;
        } catch (Exception e) {
            log.error("Could not enqueue send {}", job.messageId(), e);
            throw queueUnavailable(
                    "The send queue is unavailable. The message was not accepted — retry shortly.");
        }
    }

    /**
     * The one rejection the send path makes when Redis is gone, shared with
     * {@link com.wasparks.api.idempotency.IdempotencyService} so the enqueue failure and the
     * idempotency-store failure are indistinguishable to a client — they are the same outage, and both
     * mean the same thing: nothing was accepted, retry.
     */
    public static ApiException queueUnavailable(String message) {
        return ApiException.of(ApiErrorCode.QUEUE_UNAVAILABLE, message)
                .withHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    }

    /**
     * Trim the stream back to roughly {@code app.queue.max-len} entries.
     *
     * <p>Approximate trimming ({@code XTRIM MAXLEN ~}) stops at a node boundary instead of counting
     * exactly, which makes it O(1) rather than proportional to the overshoot. That is the right trade
     * for a cap whose job is to bound memory, not to hold a precise number.
     *
     * <p>Called from the reclaim sweep once a minute rather than on every enqueue: trimming per send
     * would add a second Redis round trip to the hot path to enforce a limit that is nowhere near being
     * reached. If the stream ever does approach 100,000 entries, the queue is hours behind and losing
     * the oldest entries is not the pressing problem.
     */
    public void trim() {
        try {
            redis.opsForStream().trim(stream, maxLen, true);
        } catch (Exception e) {
            log.debug("Could not trim stream {}: {}", stream, e.getMessage());
        }
    }

    public String stream() {
        return stream;
    }

    public String group() {
        return group;
    }
}
