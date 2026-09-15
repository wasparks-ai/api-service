package com.wasparks.api.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.internal.InternalDtos;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import com.wasparks.api.quota.QuotaService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drains the {@code sends} stream and performs the real send through tenants-service (epic §B6).
 *
 * <p>N threads share one consumer group, each with its own consumer name, so Redis hands each entry to
 * exactly one of them. The read blocks for two seconds rather than spinning, which keeps an idle service
 * at roughly nothing while still reacting to a send within milliseconds.
 *
 * <h2>What counts as terminal</h2>
 * An entry is acknowledged when the message has reached a state it will not leave by retrying here:
 * <ul>
 *   <li><b>upstream 200</b> — SENT or FAILED, both terminal. A Meta rejection is a result, not an
 *       outage; retrying it would send the same doomed message again;</li>
 *   <li><b>upstream 4xx</b> — the send was refused (state changed between preflight and now). The
 *       message is marked failed upstream, which emits {@code message.failed} to the client's webhook,
 *       and the tenant's quota reservation is given back — nothing was sent, so nothing is charged;</li>
 *   <li><b>{@code unknown_api_key}</b> — upstream has no {@code api_keys} row for the key that
 *       authenticated the request (amendment 8). Logged at error level as a provisioning drift, then
 *       treated as any other refusal;</li>
 *   <li><b>{@code duplicate_message_id}</b> — this job already ran and upstream already has the row.
 *       Treated as success (amendment 2). This is the case that makes redelivery safe: a worker that
 *       died after sending but before acknowledging must not produce a second message;</li>
 *   <li><b>retries exhausted</b> — three 5xx or timeouts, then failed as {@code upstream_unavailable}.</li>
 * </ul>
 * Anything not acknowledged stays pending and is reclaimed by the sweep below.
 *
 * <h2>The reclaim sweep</h2>
 * An entry delivered to a worker that then died sits in the pending list forever, because Redis has no
 * idea the consumer is gone. Every 60 seconds the sweep claims entries idle for more than five minutes
 * and re-processes them. Without it, a deploy in the middle of a burst would silently strand exactly the
 * messages that were in flight.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SendWorker {

    /** How many pending entries the reclaim sweep inspects per pass. */
    private static final int PENDING_SCAN_BATCH = 100;

    private final SendQueue sendQueue;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final InternalTenantsClient tenantsClient;
    private final QuotaService quotaService;

    @Value("${app.queue.workers}")
    private int workerCount;

    @Value("${app.queue.block-ms}")
    private long blockMs;

    @Value("${app.queue.claim-idle-ms}")
    private long claimIdleMs;

    @Value("${app.queue.retry-backoff-ms}")
    private String retryBackoffMs;

    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private List<Long> backoffs = List.of(2000L, 10000L, 30000L);
    private final String consumerBase = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    @PostConstruct
    public void start() {
        this.backoffs = parseBackoffs(retryBackoffMs);

        if (workerCount <= 0) {
            // A legitimate deployment shape, not just a test convenience: an instance behind the load
            // balancer can serve the API while a separate instance drains the queue, and separating
            // them is how a slow upstream stops competing with request threads for CPU. The reclaim
            // sweep and the direct handle() path still work.
            log.info("SEND_WORKERS is {} — this instance accepts sends but does not drain the queue",
                    workerCount);
            return;
        }

        running.set(true);
        executor = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("send-worker-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < workerCount; i++) {
            String consumerName = consumerBase + "-" + i;
            executor.submit(() -> loop(consumerName));
        }
        log.info("Started {} send workers on stream {} (group {})",
                workerCount, sendQueue.stream(), sendQueue.group());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
            try {
                // A blocked XREADGROUP returns within blockMs; give it a little more than that so an
                // in-flight send is not abandoned mid-call on a normal shutdown.
                if (!executor.awaitTermination(blockMs + 3000, TimeUnit.MILLISECONDS)) {
                    log.warn("Send workers did not stop within the grace period");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop(String consumerName) {
        Consumer consumer = Consumer.from(sendQueue.group(), consumerName);
        StreamReadOptions options = StreamReadOptions.empty()
                .block(Duration.ofMillis(blockMs))
                .count(1);

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                        .read(consumer, options,
                                StreamOffset.create(sendQueue.stream(), ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    handle(record);
                }
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                // Redis hiccup or a group that vanished. Re-ensure the group and pause briefly so a
                // hard failure does not become a hot spin against a struggling Redis.
                log.warn("Send worker {} read failed: {}", consumerName, e.getMessage());
                sendQueue.ensureGroup();
                sleep(1000);
            }
        }
    }

    /** Visible for tests: process one stream entry end to end and acknowledge it when terminal. */
    void handle(MapRecord<String, Object, Object> record) {
        RecordId recordId = record.getId();
        SendJob job = null;
        try {
            Object raw = record.getValue().get(SendQueue.FIELD_JOB);
            if (raw == null) {
                log.error("Stream entry {} has no job payload — acknowledging to drain it", recordId);
                ack(recordId);
                return;
            }
            job = objectMapper.readValue(raw.toString(), SendJob.class);
        } catch (Exception e) {
            // An entry we cannot parse will never become parseable. Acknowledge it rather than letting
            // it be reclaimed forever, and log enough to find it in the stream by hand.
            log.error("Unparseable stream entry {} — acknowledging: {}", recordId, e.getMessage());
            ack(recordId);
            return;
        }

        try {
            process(job);
        } catch (Exception e) {
            log.error("Send job {} failed unexpectedly", job.messageId(), e);
        } finally {
            ack(recordId);
        }
    }

    private void process(SendJob job) {
        for (int attempt = 0; attempt <= backoffs.size(); attempt++) {
            try {
                InternalDtos.SendResponse response =
                        tenantsClient.send(job.tenantId(), job.apiKeyId(), job.payload());
                log.debug("Send {} -> {} ({})", job.messageId(), response.status(), response.wamid());
                return;
            } catch (UpstreamRejectedException rejected) {
                handleRejection(job, rejected);
                return;
            } catch (UpstreamUnavailableException unavailable) {
                if (attempt == backoffs.size()) {
                    log.error("Send {} gave up after {} attempts: {}",
                            job.messageId(), attempt + 1, unavailable.getMessage());
                    failUpstream(job, "upstream_unavailable",
                            (attempt + 1) + " attempts failed: " + unavailable.getMessage());
                    return;
                }
                long waitMs = backoffs.get(attempt);
                log.warn("Send {} attempt {} failed ({}), retrying in {}ms",
                        job.messageId(), attempt + 1, unavailable.getMessage(), waitMs);
                sleep(waitMs);
            }
        }
    }

    /**
     * Upstream refused the send. Two cases, and telling them apart is what keeps redelivery safe.
     */
    private void handleRejection(SendJob job, UpstreamRejectedException rejected) {
        if (ApiErrorCode.UNKNOWN_API_KEY.wire().equals(rejected.getCode())) {
            // tenants-service does not have an api_keys row for the key that authenticated this send
            // (amendment 8). That is a provisioning drift between the two services, not something the
            // tenant did — error level, because the fail call below carries the same key header and will
            // very likely be refused too, leaving the message with no terminal state anywhere.
            log.error("Send {} refused: tenants-service does not know api key {} — the two services' "
                    + "api_keys tables have drifted", job.messageId(), job.apiKeyId());
        }

        if ("duplicate_message_id".equals(rejected.getCode())) {
            // This job already ran — a redelivery after a crash between the send and the ack. The row
            // exists upstream and the message went out; doing anything else here would double-send.
            log.info("Send {} was already processed upstream — treating redelivery as success",
                    job.messageId());
            return;
        }

        log.info("Send {} rejected upstream: {} ({})",
                job.messageId(), rejected.getCode(), rejected.getMessage());
        failUpstream(job, rejected.getCode(), rejected.getMessage());

        // The message never left, so the tenant must not be charged for it (epic §B3). Only a
        // synchronous refusal releases quota — a Meta-side failure means the send did happen.
        quotaService.release(job.tenantId(), job.partner(), 1);
    }

    /**
     * Mark the message failed upstream, which writes the row if preflight never created one and emits
     * {@code message.failed} to the tenant's webhooks.
     *
     * <p>{@code to} and {@code phoneNumberId} are sent because a preflight rejection persists nothing
     * upstream (amendment 6) — without them the fail call 404s and {@code GET /v1/messages/{id}} would
     * tell the client its message does not exist instead of why it failed.
     */
    private void failUpstream(SendJob job, String code, String message) {
        InternalDtos.SendRequest payload = job.payload();
        try {
            tenantsClient.fail(job.tenantId(), job.apiKeyId(), job.messageId(),
                    new InternalDtos.FailRequest(
                            code,
                            message,
                            payload.to(),
                            payload.type(),
                            payload.phoneNumberId(),
                            job.clientRef(),
                            payload.template() == null ? null : payload.template().name()));
        } catch (Exception e) {
            // Nothing further to try: the message is lost to the client's webhook stream, so this is
            // logged at error level as an operational gap rather than swallowed.
            log.error("Could not mark message {} as failed upstream: {}",
                    job.messageId(), e.getMessage());
        }
    }

    private void ack(RecordId recordId) {
        try {
            redis.opsForStream().acknowledge(sendQueue.stream(), sendQueue.group(), recordId);
        } catch (Exception e) {
            log.warn("Could not acknowledge stream entry {}: {}", recordId, e.getMessage());
        }
    }

    /**
     * Reclaim entries a dead worker never acknowledged (the {@code XAUTOCLAIM} sweep, epic §B6).
     *
     * <p>Scheduled from {@code SendReclaimJob} rather than annotated here, so the ShedLock name and the
     * cadence live with the other scheduled work.
     */
    public int reclaimStale() {
        int reclaimed = 0;
        try {
            PendingMessages pending = redis.opsForStream().pending(
                    sendQueue.stream(),
                    sendQueue.group(),
                    Range.unbounded(),
                    PENDING_SCAN_BATCH);
            if (pending == null || pending.isEmpty()) {
                return 0;
            }

            String sweeperName = consumerBase + "-sweeper";
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery().toMillis() < claimIdleMs) {
                    continue;
                }
                // XCLAIM with a minimum idle time: if another worker has touched the entry since the
                // pending list was read, the claim returns nothing and we leave it alone rather than
                // stealing work that is genuinely in progress.
                List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                        sendQueue.stream(), sendQueue.group(), sweeperName,
                        Duration.ofMillis(claimIdleMs), message.getId());
                if (claimed != null) {
                    for (MapRecord<String, Object, Object> record : claimed) {
                        log.warn("Reclaiming stranded send entry {} (idle {}ms)",
                                record.getId(), message.getElapsedTimeSinceLastDelivery().toMillis());
                        handle(record);
                        reclaimed++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Pending-entry sweep failed: {}", e.getMessage());
        }
        return reclaimed;
    }

    private List<Long> parseBackoffs(String csv) {
        try {
            return Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException e) {
            log.warn("Unparseable send retry backoffs '{}', using 2s/10s/30s", csv);
            return List.of(2000L, 10000L, 30000L);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
