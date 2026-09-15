package com.wasparks.api.webhook;

import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.entity.ApiOutboxEvent;
import com.wasparks.api.entity.ApiWebhookDelivery;
import com.wasparks.api.entity.ApiWebhookEndpoint;
import com.wasparks.api.enums.DeliveryStatus;
import com.wasparks.api.enums.WebhookEndpointStatus;
import com.wasparks.api.plans.EffectiveLimits;
import com.wasparks.api.util.Uuid7;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole webhook path (epic §B8): outbox row → fan-out → signed POST → retry schedule → pause.
 *
 * <p>A real HTTP server receives the deliveries, so the signature is verified from the bytes that
 * actually went over the wire rather than from a re-serialisation — which is the mistake that makes a
 * signature test pass while customers cannot verify anything.
 */
class WebhookPipelineIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OutboxPoller outboxPoller;
    @Autowired
    private WebhookDispatcher dispatcher;
    @Autowired
    private WebhookEndpointService endpointService;
    @Autowired
    private WebhookSigner signer;

    private MockWebServer receiver;
    private String receiverUrl;

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = new MockWebServer();
        receiver.start();
        // Built by hand rather than from receiver.url(): MockWebServer resolves the loopback address to
        // its canonical host name, which on some machines is the machine's own name — and the endpoint
        // validator would then quite correctly refuse it as a non-loopback http URL.
        receiverUrl = "http://localhost:" + receiver.getPort() + "/hook";
    }

    @AfterEach
    void stopReceiver() throws IOException {
        receiver.shutdown();
    }

    private EffectiveLimits generousLimits() {
        return new EffectiveLimits("TEST", 1000, 10000, 100000, 100, 10, 10,
                com.wasparks.api.enums.OveragePolicy.BLOCK, false,
                com.wasparks.api.enums.BillingModel.FIXED, null, null, false);
    }

    private WebhookEndpointService.Created createEndpoint(List<String> events) {
        // localhost http is permitted precisely so a developer (and this test) can receive deliveries.
        return endpointService.create(tenantId, null, tenantUserId, receiverUrl, events,
                generousLimits());
    }

    private ApiOutboxEvent writeEvent(String type, Map<String, Object> payload) {
        return outboxRepository.save(ApiOutboxEvent.builder()
                .id(Uuid7.generate())
                .tenantId(tenantId)
                .eventType(type)
                .aggregateType("message")
                .aggregateId(UUID.randomUUID())
                .payload(payload)
                .createdAt(Instant.now())
                .build());
    }

    private Map<String, Object> messagePayload(String apiKeyId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", UUID.randomUUID().toString());
        payload.put("wamid", "wamid.HBgLtest");
        payload.put("to", "+919876543210");
        payload.put("status", "SENT");
        payload.put("apiKeyId", apiKeyId);
        return payload;
    }

    /** What tenants-service writes for a DRYRUN send: an ordinary message event with a DRYRUN wamid. */
    private ApiOutboxEvent writeDryRunSent(UUID messageId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messageId", messageId.toString());
        payload.put("wamid", "DRYRUN-" + messageId);
        payload.put("to", "+919876543210");
        payload.put("status", "SENT");
        payload.put("type", "TEXT");
        payload.put("apiKeyId", UUID.randomUUID().toString());
        return outboxRepository.save(ApiOutboxEvent.builder()
                .id(Uuid7.generate()).tenantId(tenantId)
                .eventType("message.sent").aggregateType("message")
                .aggregateId(messageId)
                .payload(payload)
                .createdAt(Instant.now())
                .build());
    }

    private List<ApiOutboxEvent> eventsFor(UUID messageId, String type) {
        return outboxRepository.findAll().stream()
                .filter(e -> messageId.equals(e.getAggregateId()) && type.equals(e.getEventType()))
                .toList();
    }

    // ------------------------------------------------------------------ sandbox receipt (§0.10 amended)

    @Test
    @DisplayName("publishing a DRYRUN message.sent synthesises one delivered, post-dated")
    void dryRunSentEarnsADeliveryReceipt() {
        createEndpoint(List.of("*"));
        UUID messageId = Uuid7.generate();
        ApiOutboxEvent sent = writeDryRunSent(messageId);

        outboxPoller.publishBatch();

        List<ApiOutboxEvent> delivered = eventsFor(messageId, "message.delivered");
        assertEquals(1, delivered.size(), "exactly one synthetic receipt");
        assertEquals("DELIVERED", delivered.get(0).getPayload().get("status"));
        assertEquals(sent.getPayload().get("wamid"), delivered.get(0).getPayload().get("wamid"),
                "the receipt carries upstream's own wamid, not a re-derived one");
        assertTrue(delivered.get(0).getCreatedAt().isAfter(Instant.now()),
                "post-dated, so the next tick delivers it a couple of seconds later");
        assertNull(delivered.get(0).getPublishedAt(), "and it is not published in this same batch");
    }

    @Test
    @DisplayName("a live message.sent earns nothing — a real receipt is coming from Meta")
    void liveSentEarnsNoReceipt() {
        createEndpoint(List.of("*"));
        UUID messageId = Uuid7.generate();
        outboxRepository.save(ApiOutboxEvent.builder()
                .id(Uuid7.generate()).tenantId(tenantId)
                .eventType("message.sent").aggregateType("message")
                .aggregateId(messageId)
                .payload(messagePayload(UUID.randomUUID().toString()))   // wamid.HBgLtest
                .createdAt(Instant.now()).build());

        outboxPoller.publishBatch();

        assertTrue(eventsFor(messageId, "message.delivered").isEmpty());
    }

    @Test
    @DisplayName("nothing is synthesised for a message that already failed")
    void noReceiptAfterAFailure() {
        // The bug the amendment exists for: an accept-time synthesiser delivered "sent" and
        // "delivered" for a message the worker had already marked failed.
        createEndpoint(List.of("*"));
        UUID messageId = Uuid7.generate();
        Map<String, Object> failedPayload = new HashMap<>();
        failedPayload.put("messageId", messageId.toString());
        failedPayload.put("status", "FAILED");
        failedPayload.put("apiKeyId", UUID.randomUUID().toString());
        outboxRepository.save(ApiOutboxEvent.builder()
                .id(Uuid7.generate()).tenantId(tenantId)
                .eventType("message.failed").aggregateType("message")
                .aggregateId(messageId)
                .payload(failedPayload)
                .createdAt(Instant.now()).build());
        writeDryRunSent(messageId);

        outboxPoller.publishBatch();

        assertTrue(eventsFor(messageId, "message.delivered").isEmpty(),
                "a failed message never gets a delivery receipt");
    }

    @Test
    @DisplayName("a re-published DRYRUN sent does not produce a second receipt")
    void receiptIsNotDuplicated() {
        createEndpoint(List.of("*"));
        UUID messageId = Uuid7.generate();
        ApiOutboxEvent sent = writeDryRunSent(messageId);

        outboxPoller.publishBatch();
        // Force the sent event back through the poller, as a crash between the fan-out and the commit
        // would.
        sent.setPublishedAt(null);
        outboxRepository.save(sent);
        outboxPoller.publishBatch();

        assertEquals(1, eventsFor(messageId, "message.delivered").size());
    }

    // ------------------------------------------------------------------ fan-out

    @Test
    @DisplayName("an event fans out to a subscribing endpoint and is marked published")
    void fanOut() {
        WebhookEndpointService.Created endpoint = createEndpoint(List.of("message.*"));
        ApiOutboxEvent event = writeEvent("message.sent", messagePayload(UUID.randomUUID().toString()));

        assertEquals(1, outboxPoller.publishBatch());

        assertNotNull(outboxRepository.findById(event.getId()).orElseThrow().getPublishedAt());
        List<ApiWebhookDelivery> deliveries = deliveryRepository.findAll();
        assertEquals(1, deliveries.size());
        assertEquals(DeliveryStatus.PENDING, deliveries.get(0).getStatus());
        assertEquals(endpoint.endpoint().getId(), deliveries.get(0).getEndpointId());
    }

    @Test
    @DisplayName("an endpoint that does not subscribe gets nothing, and the event is still published")
    void nonMatchingEndpoint() {
        // The event must still be marked published, or the poller re-examines it on every tick forever.
        createEndpoint(List.of("template.*"));
        ApiOutboxEvent event = writeEvent("message.sent", messagePayload(UUID.randomUUID().toString()));

        outboxPoller.publishBatch();

        assertTrue(deliveryRepository.findAll().isEmpty());
        assertNotNull(outboxRepository.findById(event.getId()).orElseThrow().getPublishedAt());
    }

    @Test
    @DisplayName("a message event with no apiKeyId is not delivered")
    void uiSendsAreFilteredOut() {
        // Amendment 5: tenants-service emits message.* for UI sends too. Forwarding them would mean a
        // customer's integration receiving delivery reports for conversations its staff are having.
        createEndpoint(List.of("*"));
        writeEvent("message.sent", messagePayload(null));

        outboxPoller.publishBatch();

        assertTrue(deliveryRepository.findAll().isEmpty(),
                "a UI-originated send must not reach an API webhook");
    }

    @Test
    @DisplayName("a non-message event is delivered regardless of apiKeyId")
    void nonMessageEventsAlwaysDeliver() {
        createEndpoint(List.of("*"));
        outboxRepository.save(ApiOutboxEvent.builder()
                .id(Uuid7.generate()).tenantId(tenantId)
                .eventType("template.approved").aggregateType("template")
                .aggregateId(UUID.randomUUID())
                .payload(Map.of("name", "order_update"))
                .createdAt(Instant.now()).build());

        outboxPoller.publishBatch();

        assertEquals(1, deliveryRepository.findAll().size(),
                "a template approval is a tenant-wide fact, not a per-key one");
    }

    @Test
    @DisplayName("a post-dated event is invisible until its moment arrives")
    void postDatedEventsWait() {
        // This is the mechanism the sandbox synthesiser relies on.
        createEndpoint(List.of("*"));
        outboxRepository.save(ApiOutboxEvent.builder()
                .id(Uuid7.generate()).tenantId(tenantId)
                .eventType("message.sent").aggregateType("message")
                .aggregateId(UUID.randomUUID())
                .payload(messagePayload(UUID.randomUUID().toString()))
                .createdAt(Instant.now().plus(1, ChronoUnit.HOURS)).build());

        assertEquals(0, outboxPoller.publishBatch());
        assertTrue(deliveryRepository.findAll().isEmpty());
    }

    @Test
    @DisplayName("another tenant's event is not delivered here")
    void tenantIsolation() {
        createEndpoint(List.of("*"));
        outboxRepository.save(ApiOutboxEvent.builder()
                .id(Uuid7.generate()).tenantId(UUID.randomUUID())
                .eventType("message.sent").aggregateType("message")
                .aggregateId(UUID.randomUUID())
                .payload(messagePayload(UUID.randomUUID().toString()))
                .createdAt(Instant.now()).build());

        outboxPoller.publishBatch();
        assertTrue(deliveryRepository.findAll().isEmpty());
    }

    @Test
    @DisplayName("a PAUSED endpoint receives nothing")
    void pausedEndpointGetsNothing() {
        WebhookEndpointService.Created created = createEndpoint(List.of("*"));
        ApiWebhookEndpoint endpoint = created.endpoint();
        endpoint.setStatus(WebhookEndpointStatus.PAUSED);
        endpointRepository.save(endpoint);

        writeEvent("message.sent", messagePayload(UUID.randomUUID().toString()));
        outboxPoller.publishBatch();

        assertTrue(deliveryRepository.findAll().isEmpty());
    }

    // ------------------------------------------------------------------ delivery and signing

    @Test
    @DisplayName("a delivery arrives signed, and the signature verifies against the received bytes")
    void deliveryIsSigned() throws Exception {
        receiver.enqueue(new MockResponse().setResponseCode(200));
        WebhookEndpointService.Created created = createEndpoint(List.of("message.*"));
        ApiOutboxEvent event = writeEvent("message.sent",
                messagePayload(UUID.randomUUID().toString()));

        outboxPoller.publishBatch();
        dispatcher.dispatchBatch();

        RecordedRequest request = receiver.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request, "the endpoint should have been called");

        String rawBody = request.getBody().readUtf8();
        String signature = request.getHeader(WebhookSigner.HEADER_SIGNATURE);
        assertNotNull(signature);

        // Verified against the bytes that actually arrived — the round trip a customer performs.
        assertTrue(signer.verify(created.secret(), rawBody, signature, 300),
                "the customer must be able to verify the delivery with the secret we gave them");

        assertEquals("message.sent", request.getHeader(WebhookSigner.HEADER_EVENT));
        assertNotNull(request.getHeader(WebhookSigner.HEADER_DELIVERY));

        var body = objectMapper.readTree(rawBody);
        assertEquals("evt_" + event.getId(), body.get("id").asText());
        assertEquals("message.sent", body.get("type").asText());
        assertTrue(body.has("created_at"));
        assertEquals("+919876543210", body.at("/data/to").asText());

        ApiWebhookDelivery delivery = deliveryRepository.findAll().get(0);
        assertEquals(DeliveryStatus.SUCCESS, delivery.getStatus());
        assertEquals(200, delivery.getResponseCode());
        assertNotNull(delivery.getDeliveredAt());
        assertNull(delivery.getNextAttemptAt());
    }

    @Test
    @DisplayName("the wrong secret does not verify a genuine delivery")
    void wrongSecretFails() throws Exception {
        receiver.enqueue(new MockResponse().setResponseCode(200));
        createEndpoint(List.of("message.*"));
        writeEvent("message.sent", messagePayload(UUID.randomUUID().toString()));

        outboxPoller.publishBatch();
        dispatcher.dispatchBatch();

        RecordedRequest request = receiver.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(!signer.verify("whsec_wrong", request.getBody().readUtf8(),
                request.getHeader(WebhookSigner.HEADER_SIGNATURE), 300));
    }

    // ------------------------------------------------------------------ retries

    @Test
    @DisplayName("a failure schedules the next attempt ten seconds out")
    void firstRetryIsTenSeconds() throws Exception {
        receiver.enqueue(new MockResponse().setResponseCode(500));
        createEndpoint(List.of("message.*"));
        writeEvent("message.sent", messagePayload(UUID.randomUUID().toString()));

        outboxPoller.publishBatch();
        Instant before = Instant.now();
        dispatcher.dispatchBatch();

        ApiWebhookDelivery delivery = deliveryRepository.findAll().get(0);
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
        assertEquals(1, delivery.getAttempt());
        assertEquals(500, delivery.getResponseCode());
        assertNotNull(delivery.getNextAttemptAt());

        long gapSeconds = delivery.getNextAttemptAt().getEpochSecond() - before.getEpochSecond();
        assertTrue(gapSeconds >= 9 && gapSeconds <= 12,
                "the first retry should be ~10s out, was " + gapSeconds + "s");
    }

    @Test
    @DisplayName("the retry schedule widens and then exhausts")
    void retryScheduleWidensThenExhausts() throws Exception {
        // 10s, 1m, 5m, 30m, 2h — five retries after the first attempt, then EXHAUSTED.
        createEndpoint(List.of("message.*"));
        writeEvent("message.sent", messagePayload(UUID.randomUUID().toString()));
        outboxPoller.publishBatch();

        long[] expectedGapsSeconds = {10, 60, 300, 1800, 7200};
        for (int attempt = 0; attempt < expectedGapsSeconds.length; attempt++) {
            receiver.enqueue(new MockResponse().setResponseCode(503));
            ApiWebhookDelivery before = deliveryRepository.findAll().get(0);
            before.setNextAttemptAt(Instant.now().minusSeconds(1));   // make it due now
            deliveryRepository.save(before);

            Instant now = Instant.now();
            dispatcher.dispatchBatch();
            receiver.takeRequest(5, TimeUnit.SECONDS);

            ApiWebhookDelivery after = deliveryRepository.findAll().get(0);
            assertEquals(attempt + 1, after.getAttempt());
            assertEquals(DeliveryStatus.PENDING, after.getStatus());
            long gap = after.getNextAttemptAt().getEpochSecond() - now.getEpochSecond();
            assertTrue(Math.abs(gap - expectedGapsSeconds[attempt]) <= 2,
                    "attempt " + (attempt + 1) + " should schedule +" + expectedGapsSeconds[attempt]
                            + "s, was +" + gap + "s");
        }

        // The sixth attempt has no schedule left.
        receiver.enqueue(new MockResponse().setResponseCode(503));
        ApiWebhookDelivery last = deliveryRepository.findAll().get(0);
        last.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveryRepository.save(last);
        dispatcher.dispatchBatch();

        ApiWebhookDelivery exhausted = deliveryRepository.findAll().get(0);
        assertEquals(DeliveryStatus.EXHAUSTED, exhausted.getStatus());
        assertNull(exhausted.getNextAttemptAt());
    }

    @Test
    @DisplayName("a success anywhere in a run resets the endpoint's failure counter")
    void successResetsFailureCount() throws Exception {
        // So that a merely flaky endpoint is never auto-paused.
        WebhookEndpointService.Created created = createEndpoint(List.of("message.*"));
        writeEvent("message.sent", messagePayload(UUID.randomUUID().toString()));
        outboxPoller.publishBatch();

        receiver.enqueue(new MockResponse().setResponseCode(500));
        dispatcher.dispatchBatch();
        receiver.takeRequest(5, TimeUnit.SECONDS);
        assertEquals(1, endpointRepository.findById(created.endpoint().getId())
                .orElseThrow().getConsecutiveFailures());

        receiver.enqueue(new MockResponse().setResponseCode(200));
        ApiWebhookDelivery due = deliveryRepository.findAll().get(0);
        due.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveryRepository.save(due);
        dispatcher.dispatchBatch();
        receiver.takeRequest(5, TimeUnit.SECONDS);

        ApiWebhookEndpoint endpoint = endpointRepository.findById(created.endpoint().getId())
                .orElseThrow();
        assertEquals(0, endpoint.getConsecutiveFailures());
        assertNotNull(endpoint.getLastSuccessAt());
        assertEquals(WebhookEndpointStatus.ACTIVE, endpoint.getStatus());
    }

    @Test
    @DisplayName("the endpoint is paused once the failure run reaches the threshold")
    void autoPause() throws Exception {
        WebhookEndpointService.Created created = createEndpoint(List.of("message.*"));
        ApiWebhookEndpoint endpoint = created.endpoint();
        // Start one short of the threshold rather than driving 100 real failures.
        endpoint.setConsecutiveFailures(99);
        endpointRepository.save(endpoint);

        writeEvent("message.sent", messagePayload(UUID.randomUUID().toString()));
        outboxPoller.publishBatch();
        receiver.enqueue(new MockResponse().setResponseCode(500));
        dispatcher.dispatchBatch();
        receiver.takeRequest(5, TimeUnit.SECONDS);

        assertEquals(WebhookEndpointStatus.PAUSED,
                endpointRepository.findById(endpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a connection failure is recorded as an attempt, not swallowed")
    void connectionFailureIsAnAttempt() throws Exception {
        receiver.shutdown();   // nothing is listening any more
        createEndpoint(List.of("message.*"));
        writeEvent("message.sent", messagePayload(UUID.randomUUID().toString()));

        outboxPoller.publishBatch();
        dispatcher.dispatchBatch();

        ApiWebhookDelivery delivery = deliveryRepository.findAll().get(0);
        assertEquals(1, delivery.getAttempt());
        assertEquals(DeliveryStatus.PENDING, delivery.getStatus());
        assertNull(delivery.getResponseCode(), "there was no HTTP response to record");
        assertNotNull(delivery.getError());
    }

    // ------------------------------------------------------------------ ping and management

    @Test
    @DisplayName("the test button delivers a ping through the real pipeline")
    void pingTravelsTheRealPath() throws Exception {
        // Not a shortcut POST: a test that took one would prove the shortcut works and tell the tenant
        // nothing about whether real events will arrive.
        receiver.enqueue(new MockResponse().setResponseCode(200));
        WebhookEndpointService.Created created = createEndpoint(List.of("*"));

        endpointService.sendTest(tenantId, created.endpoint().getId());
        outboxPoller.publishBatch();
        dispatcher.dispatchBatch();

        RecordedRequest request = receiver.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("ping", request.getHeader(WebhookSigner.HEADER_EVENT));
        assertTrue(signer.verify(created.secret(), request.getBody().readUtf8(),
                request.getHeader(WebhookSigner.HEADER_SIGNATURE), 300));
    }

    @Test
    @DisplayName("the stored secret is encrypted, not the plaintext we returned")
    void secretIsEncryptedAtRest() {
        WebhookEndpointService.Created created = createEndpoint(List.of("*"));
        String stored = jdbcTemplate.queryForObject(
                "SELECT secret_encrypted FROM api_webhook_endpoints WHERE id = ?",
                String.class, created.endpoint().getId());

        assertNotNull(stored);
        assertTrue(!created.secret().equals(stored), "the secret must not be stored in the clear");
        assertTrue(created.secret().startsWith("whsec_"));
    }

    @Test
    @DisplayName("re-publishing does not create a second delivery for the same pair")
    void noDuplicateDeliveries() {
        createEndpoint(List.of("*"));
        ApiOutboxEvent event = writeEvent("message.sent",
                messagePayload(UUID.randomUUID().toString()));

        outboxPoller.publishBatch();
        // Force the event back to unpublished, as a crash mid-batch would leave it.
        ApiOutboxEvent stored = outboxRepository.findById(event.getId()).orElseThrow();
        stored.setPublishedAt(null);
        outboxRepository.save(stored);
        outboxPoller.publishBatch();

        assertEquals(1, deliveryRepository.findAll().size(),
                "a duplicate webhook is exactly what the delivery log exists to prevent");
    }
}
