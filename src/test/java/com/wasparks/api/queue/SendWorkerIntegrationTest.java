package com.wasparks.api.queue;

import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.internal.InternalDtos;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import com.wasparks.api.quota.QuotaService;
import com.wasparks.api.util.Uuid7;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The send worker (epic §B6).
 *
 * <p>The worker pool is configured to zero threads in the test profile and {@code handle()} is driven
 * directly, so each assertion sees exactly one processing pass. A background pool would race the test
 * for the same stream entries and turn every assertion into a timing question.
 *
 * <p>What matters here is what gets acknowledged. An entry that is acknowledged when it should not be
 * is a message silently dropped; one that is not acknowledged when it should be is a message sent twice
 * after the reclaim sweep picks it up.
 */
class SendWorkerIntegrationTest extends BaseIntegrationTest {

    private static final String STREAM = "sends-test";
    private static final String GROUP = "api-send-workers";

    @MockBean
    private InternalTenantsClient tenantsClient;

    @Autowired
    private SendWorker sendWorker;
    @Autowired
    private SendQueue sendQueue;
    @Autowired
    private QuotaService quotaService;

    private UUID apiKeyId;

    @BeforeEach
    void ensureGroupExists() {
        sendQueue.ensureGroup();
        apiKeyId = UUID.randomUUID();
    }

    private SendJob job(UUID messageId) {
        InternalDtos.SendRequest payload = new InternalDtos.SendRequest(
                messageId.toString(), phoneNumberId, "+919876543210", "text", "Hi",
                null, null, "LIVE", "order-1");
        return new SendJob(messageId, tenantId, apiKeyId, "LIVE", payload, "order-1");
    }

    /** Enqueue, then read the entry back as the worker would. */
    private MapRecord<String, Object, Object> enqueueAndRead(SendJob job) {
        sendQueue.enqueue(job);
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(GROUP, "test-consumer"),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        assertNotNull(records);
        assertEquals(1, records.size());
        return records.get(0);
    }

    private long pendingCount() {
        PendingMessages pending = redis.opsForStream()
                .pending(STREAM, GROUP, Range.unbounded(), 100L);
        return pending == null ? 0 : pending.size();
    }

    @Test
    @DisplayName("a successful send is acknowledged")
    void happyPath() {
        UUID messageId = Uuid7.generate();
        Mockito.when(tenantsClient.send(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(new InternalDtos.SendResponse(
                        messageId.toString(), "SENT", "wamid.HBgLtest", null));

        sendWorker.handle(enqueueAndRead(job(messageId)));

        Mockito.verify(tenantsClient).send(Mockito.eq(tenantId), Mockito.eq(apiKeyId), Mockito.any());
        assertEquals(0, pendingCount(), "a terminal outcome must be acknowledged");
    }

    @Test
    @DisplayName("a 200 carrying FAILED is terminal, not a retry")
    void metaFailureIsTerminal() {
        // A Meta rejection is a result, not an outage. Retrying it would send the same doomed message
        // again and again.
        UUID messageId = Uuid7.generate();
        Mockito.when(tenantsClient.send(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(new InternalDtos.SendResponse(messageId.toString(), "FAILED", null,
                        new InternalDtos.UpstreamError("131026", "Message Undeliverable.")));

        sendWorker.handle(enqueueAndRead(job(messageId)));

        Mockito.verify(tenantsClient, Mockito.times(1))
                .send(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(tenantsClient, Mockito.never())
                .fail(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        assertEquals(0, pendingCount());
    }

    @Test
    @DisplayName("a 5xx is retried three times, then failed upstream_unavailable")
    void retriesThenFails() {
        UUID messageId = Uuid7.generate();
        Mockito.when(tenantsClient.send(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new UpstreamUnavailableException("502 from tenants-service"));

        sendWorker.handle(enqueueAndRead(job(messageId)));

        // Four attempts: the first plus the three configured backoffs (5/10/15ms in the test profile).
        Mockito.verify(tenantsClient, Mockito.times(4))
                .send(Mockito.any(), Mockito.any(), Mockito.any());

        ArgumentCaptor<InternalDtos.FailRequest> failure =
                ArgumentCaptor.forClass(InternalDtos.FailRequest.class);
        Mockito.verify(tenantsClient).fail(Mockito.eq(tenantId), Mockito.eq(apiKeyId),
                Mockito.eq(messageId), failure.capture());
        assertEquals("upstream_unavailable", failure.getValue().code());
        assertEquals(0, pendingCount(), "a message that has given up is still terminal");
    }

    @Test
    @DisplayName("a retry that succeeds does not fail the message")
    void retrySucceeds() {
        UUID messageId = Uuid7.generate();
        Mockito.when(tenantsClient.send(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new UpstreamUnavailableException("blip"))
                .thenReturn(new InternalDtos.SendResponse(
                        messageId.toString(), "SENT", "wamid.HBgLtest", null));

        sendWorker.handle(enqueueAndRead(job(messageId)));

        Mockito.verify(tenantsClient, Mockito.times(2))
                .send(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(tenantsClient, Mockito.never())
                .fail(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("the fail call carries `to` and `phoneNumberId` so the message can still be looked up")
    void failCarriesRecoveryFields() {
        // Amendment 6: a preflight rejection persists nothing upstream. Without these two fields the
        // fail call 404s and GET /v1/messages/{id} tells the client its message does not exist.
        UUID messageId = Uuid7.generate();
        Mockito.when(tenantsClient.send(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new UpstreamRejectedException("window_closed", "closed", 400));

        sendWorker.handle(enqueueAndRead(job(messageId)));

        ArgumentCaptor<InternalDtos.FailRequest> failure =
                ArgumentCaptor.forClass(InternalDtos.FailRequest.class);
        Mockito.verify(tenantsClient).fail(Mockito.any(), Mockito.any(), Mockito.any(),
                failure.capture());
        assertEquals("+919876543210", failure.getValue().to());
        assertEquals(phoneNumberId, failure.getValue().phoneNumberId());
        assertEquals("text", failure.getValue().type());
        assertEquals("order-1", failure.getValue().clientRef());
    }

    @Test
    @DisplayName("an upstream rejection gives the tenant's quota back")
    void rejectionReleasesQuota() {
        // The message never left, so the customer must not be charged for it.
        UUID messageId = Uuid7.generate();
        redis.opsForValue().set(quotaDayKey(), "5");

        Mockito.when(tenantsClient.send(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new UpstreamRejectedException("recipient_opted_out", "opted out", 403));

        sendWorker.handle(enqueueAndRead(job(messageId)));

        assertEquals("4", redis.opsForValue().get(quotaDayKey()));
    }

    @Test
    @DisplayName("a Meta-side failure does NOT give quota back")
    void metaFailureKeepsQuota() {
        // The message did leave the building; the customer is charged for it.
        UUID messageId = Uuid7.generate();
        redis.opsForValue().set(quotaDayKey(), "5");

        Mockito.when(tenantsClient.send(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(new InternalDtos.SendResponse(messageId.toString(), "FAILED", null,
                        new InternalDtos.UpstreamError("131026", "Undeliverable")));

        sendWorker.handle(enqueueAndRead(job(messageId)));

        assertEquals("5", redis.opsForValue().get(quotaDayKey()));
    }

    @Test
    @DisplayName("a redelivered job that upstream already has is treated as success")
    void duplicateIsSuccess() {
        // The crash-between-send-and-ack case. Anything other than treating this as success would
        // produce a second message for the recipient.
        UUID messageId = Uuid7.generate();
        Mockito.when(tenantsClient.send(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new UpstreamRejectedException("duplicate_message_id", "exists", 409));

        sendWorker.handle(enqueueAndRead(job(messageId)));

        Mockito.verify(tenantsClient, Mockito.never())
                .fail(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        assertEquals(0, pendingCount());
    }

    @Test
    @DisplayName("an unparseable entry is acknowledged rather than reclaimed forever")
    void unparseableEntryIsDrained() {
        redis.opsForStream().add(
                org.springframework.data.redis.connection.stream.StreamRecords
                        .mapBacked(java.util.Map.of("job", "{not json"))
                        .withStreamKey(STREAM));

        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(GROUP, "test-consumer"),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        assertNotNull(records);
        sendWorker.handle(records.get(0));

        assertEquals(0, pendingCount(), "a poison entry must not accumulate in the pending list");
        Mockito.verifyNoInteractions(tenantsClient);
    }

    @Test
    @DisplayName("an entry a dead worker never acknowledged is reclaimed and processed")
    void reclaimsStranded() throws Exception {
        // The deploy-during-a-burst case: without the sweep, exactly the messages that were in flight
        // are the ones lost.
        UUID messageId = Uuid7.generate();
        Mockito.when(tenantsClient.send(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(new InternalDtos.SendResponse(
                        messageId.toString(), "SENT", "wamid.HBgLtest", null));

        sendQueue.enqueue(job(messageId));
        // Read it as a consumer that then "dies" — the entry stays pending, unacknowledged.
        redis.opsForStream().read(
                Consumer.from(GROUP, "doomed-worker"),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        assertEquals(1, pendingCount());

        // claim-idle-ms is 1000 in the test profile.
        Thread.sleep(Duration.ofMillis(1200).toMillis());
        int reclaimed = sendWorker.reclaimStale();

        assertTrue(reclaimed >= 1, "the stranded entry should have been reclaimed");
        Mockito.verify(tenantsClient).send(Mockito.any(), Mockito.any(), Mockito.any());
        assertEquals(0, pendingCount());
    }

    @Test
    @DisplayName("a freshly delivered entry is left alone by the sweep")
    void doesNotStealFreshWork() {
        sendQueue.enqueue(job(Uuid7.generate()));
        redis.opsForStream().read(
                Consumer.from(GROUP, "busy-worker"),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));

        assertEquals(0, sendWorker.reclaimStale(),
                "work in progress must not be stolen from a live worker");
        Mockito.verifyNoInteractions(tenantsClient);
    }

    private String quotaDayKey() {
        return "q:" + tenantId + ":d:"
                + java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
}
