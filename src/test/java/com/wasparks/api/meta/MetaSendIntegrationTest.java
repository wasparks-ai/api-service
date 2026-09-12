package com.wasparks.api.meta;

import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.entity.ApiOutboxEvent;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.internal.UpstreamUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Meta-compatible send endpoint (epic §B5) — accept shape, Meta-dialect errors, idempotency, quota
 * and the sandbox.
 *
 * <p>The upstream preflight is stubbed here. What is under test is this service's half of the contract:
 * that it rejects locally what it can, that it maps an upstream rejection to the right Meta code, and
 * that it never returns a 202 for something it was told no about.
 */
class MetaSendIntegrationTest extends BaseIntegrationTest {

    private static final String PATH = "/meta/whatsapp/v20.0/1234567890/messages";

    @MockBean
    private InternalTenantsClient tenantsClient;

    private String textBody(String to, String text) {
        return """
                {"messaging_product":"whatsapp","to":"%s","type":"text","text":{"body":"%s"}}
                """.formatted(to, text);
    }

    // ------------------------------------------------------------------ accept

    @Test
    @DisplayName("an accepted send returns Meta's 202 shape with our message id")
    void acceptShape() throws Exception {
        String key = issueLiveKey();

        MvcResult result = mockMvc.perform(post(PATH)
                        .header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messaging_product").value("whatsapp"))
                .andExpect(jsonPath("$.contacts[0].input").value("+919876543210"))
                // Meta reports wa_id without the plus; an integration keying its records on it must
                // keep working after the migration.
                .andExpect(jsonPath("$.contacts[0].wa_id").value("919876543210"))
                .andExpect(jsonPath("$.messages[0].message_status").value("accepted"))
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/messages/0/id").asText();
        assertTrue(id.startsWith("msg_"), "the documented deviation: our id, not a Meta wamid — " + id);

        Mockito.verify(tenantsClient).validateSend(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("the send is enqueued on the stream")
    void enqueues() throws Exception {
        String key = issueLiveKey();
        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isAccepted());

        Long length = redis.opsForStream().size("sends-test");
        assertEquals(1L, length, "an accepted send must be on the queue");
    }

    @Test
    @DisplayName("client_ref is accepted and travels with the job")
    void clientRef() throws Exception {
        String key = issueLiveKey();
        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messaging_product":"whatsapp","to":"+919876543210","type":"text",
                                 "text":{"body":"Hi"},"client_ref":"order-4711"}
                                """))
                .andExpect(status().isAccepted());

        var entries = redis.opsForStream().range("sends-test",
                org.springframework.data.domain.Range.unbounded());
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).getValue().get("job").toString().contains("order-4711"));
    }

    // ------------------------------------------------------------------ Meta-shaped local rejections

    @Test
    @DisplayName("a malformed recipient is a Meta-shaped 400 code 100")
    void badRecipient() throws Exception {
        String key = issueLiveKey();

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("not-a-number", "Hi")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(100))
                .andExpect(jsonPath("$.error.type").value("OAuthException"))
                .andExpect(jsonPath("$.error.fbtrace_id").exists())
                .andExpect(header().string("X-WaSparks-Error", "invalid_request"));

        Mockito.verifyNoInteractions(tenantsClient);
    }

    @Test
    @DisplayName("an unsupported type is rejected before any upstream call")
    void unsupportedType() throws Exception {
        String key = issueLiveKey();

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messaging_product":"whatsapp","to":"+919876543210",
                                 "type":"location","location":{"latitude":1,"longitude":2}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-WaSparks-Error", "unsupported_type"));
    }

    @Test
    @DisplayName("an unknown field is a 400 code 100, not silently dropped")
    void unknownField() throws Exception {
        // The strict-DTO promise. A typo that was ignored would produce a 202 for a message that is not
        // what the caller meant, and they would hear about it from their customer.
        String key = issueLiveKey();

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messaging_product":"whatsapp","to":"+919876543210","type":"text",
                                 "text":{"body":"Hi"},"tempate":{"name":"typo"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(100))
                .andExpect(header().string("X-WaSparks-Error", "malformed_body"));
    }

    @Test
    @DisplayName("messaging_product is required, exactly as Meta requires it")
    void messagingProductRequired() throws Exception {
        String key = issueLiveKey();

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"+919876543210\",\"type\":\"text\",\"text\":{\"body\":\"Hi\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(100));
    }

    @Test
    @DisplayName("media by id is refused with an explanation, not a missing-field error")
    void mediaByIdExplains() throws Exception {
        String key = issueLiveKey();

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messaging_product":"whatsapp","to":"+919876543210","type":"image",
                                 "image":{"id":"1234567890"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("Media by `id` is not supported")));
    }

    @Test
    @DisplayName("a phone_number_id belonging to another tenant is 404")
    void foreignNumber() throws Exception {
        String key = issueLiveKey();

        mockMvc.perform(post("/meta/whatsapp/v20.0/9999999999/messages")
                        .header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-WaSparks-Error", "account_not_found"));
    }

    @Test
    @DisplayName("a disconnected number is 409 account_disconnected")
    void disconnectedNumber() throws Exception {
        String key = issueLiveKey();
        setAccountStatus("DISCONNECTED");

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-WaSparks-Error", "account_disconnected"));
    }

    @Test
    @DisplayName("an unsupported graph version is rejected")
    void badVersion() throws Exception {
        String key = issueLiveKey();

        mockMvc.perform(post("/meta/whatsapp/v12.0/1234567890/messages")
                        .header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a key without messages:send is 403 before anything is consumed")
    void wrongScope() throws Exception {
        String key = issueKeyWithScopes(Scope.MESSAGES_READ);

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .header("Idempotency-Key", "scope-test-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isForbidden());

        // The ordering guarantee: scope runs before idempotency and quota, so a mis-scoped request
        // leaves no record and burns none of the tenant's daily allowance.
        assertEquals(0L, redis.opsForStream().size("sends-test"));
        assertTrue(redis.keys("idem:*").isEmpty(), "no idempotency record should have been reserved");
        assertTrue(redis.keys("q:*").isEmpty(), "no quota should have been reserved");
    }

    // ------------------------------------------------------------------ upstream preflight

    @Test
    @DisplayName("an upstream rejection becomes the mapped synchronous Meta error")
    void upstreamWindowClosed() throws Exception {
        // 131047 is the code a migrating client's existing error handling already branches on.
        String key = issueLiveKey();
        Mockito.doThrow(new UpstreamRejectedException("window_closed",
                        "No customer-service window is open for +919876543210", 400))
                .when(tenantsClient).validateSend(Mockito.any(), Mockito.any());

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(131047))
                .andExpect(header().string("X-WaSparks-Error", "window_closed"));

        assertEquals(0L, redis.opsForStream().size("sends-test"),
                "a rejected send must never reach the queue");
    }

    @Test
    @DisplayName("an unapproved template is 132001")
    void upstreamTemplateNotApproved() throws Exception {
        String key = issueLiveKey();
        Mockito.doThrow(new UpstreamRejectedException("template_not_approved",
                        "Template order_update is not approved for en_US", 400))
                .when(tenantsClient).validateSend(Mockito.any(), Mockito.any());

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messaging_product":"whatsapp","to":"+919876543210","type":"template",
                                 "template":{"name":"order_update","language":{"code":"en_US"},
                                 "components":[{"type":"body","parameters":[{"type":"text","text":"Sam"}]}]}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(132001));
    }

    @Test
    @DisplayName("an opted-out recipient is 403 with Meta code 131050")
    void upstreamSuppressed() throws Exception {
        String key = issueLiveKey();
        Mockito.doThrow(new UpstreamRejectedException("recipient_opted_out", "Opted out", 403))
                .when(tenantsClient).validateSend(Mockito.any(), Mockito.any());

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(131050))
                .andExpect(header().string("X-WaSparks-Error", "recipient_opted_out"));
    }

    @Test
    @DisplayName("an unreachable preflight refuses rather than accepting blind")
    void upstreamUnavailable() throws Exception {
        // Accepting a 202 we cannot evaluate would hand the client a promise and surface the failure
        // minutes later by webhook.
        String key = issueLiveKey();
        Mockito.doThrow(new UpstreamUnavailableException("connection refused"))
                .when(tenantsClient).validateSend(Mockito.any(), Mockito.any());

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-WaSparks-Error", "upstream_unavailable"));

        assertEquals(0L, redis.opsForStream().size("sends-test"));
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    @DisplayName("the same key and body replays the original response and sends nothing new")
    void idempotentReplay() throws Exception {
        String key = issueLiveKey();
        String body = textBody("+919876543210", "Hi");

        MvcResult first = mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .header("Idempotency-Key", "order-4711")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn();

        MvcResult second = mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .header("Idempotency-Key", "order-4711")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertEquals(first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString(),
                "a replay must return the original response byte for byte");
        assertEquals(1L, redis.opsForStream().size("sends-test"),
                "the replay must not enqueue a second message");
        Mockito.verify(tenantsClient, Mockito.times(1)).validateSend(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("the same key with a different body is 422 idempotency_conflict")
    void idempotencyConflict() throws Exception {
        String key = issueLiveKey();

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .header("Idempotency-Key", "order-4712")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isAccepted());

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .header("Idempotency-Key", "order-4712")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Something else")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("X-WaSparks-Error", "idempotency_conflict"));

        assertEquals(1L, redis.opsForStream().size("sends-test"));
    }

    @Test
    @DisplayName("a failed send releases its idempotency key so an honest retry works")
    void failureReleasesTheKey() throws Exception {
        // Without the release, a client retrying after an error — the normal thing to do — would be
        // refused for 24 hours on a key that never produced a message.
        String key = issueLiveKey();
        Mockito.doThrow(new UpstreamUnavailableException("down"))
                .when(tenantsClient).validateSend(Mockito.any(), Mockito.any());

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .header("Idempotency-Key", "order-4713")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isServiceUnavailable());

        Mockito.reset(tenantsClient);
        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .header("Idempotency-Key", "order-4713")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("an over-long idempotency key is refused")
    void idempotencyKeyTooLong() throws Exception {
        String key = issueLiveKey();

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .header("Idempotency-Key", "x".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ quota

    @Test
    @DisplayName("a BLOCK plan refuses past the daily quota with X-Quota-Scope")
    void quotaBlock() throws Exception {
        String key = issueLiveKey();
        createPlan("QUOTA_BLOCK", 1000, 2, 1000, "BLOCK", 5);
        assignPlan("QUOTA_BLOCK", null);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(PATH).header("X-API-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(textBody("+91987654321" + i, "Hi")))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543219", "Hi")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-WaSparks-Error", "quota_exceeded"))
                .andExpect(header().string("X-Quota-Scope", "day"));

        assertEquals(2L, redis.opsForStream().size("sends-test"));
    }

    @Test
    @DisplayName("a WARN plan accepts past the quota and flags the response")
    void quotaWarn() throws Exception {
        String key = issueLiveKey();
        createPlan("QUOTA_WARN", 1000, 1, 1000, "WARN", 5);
        assignPlan("QUOTA_WARN", null);

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("X-Quota-Warning"));

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543211", "Hi")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Quota-Warning", "exceeded"))
                .andExpect(header().string("X-Quota-Scope", "day"));

        boolean quotaEvent = outboxRepository.findAll().stream()
                .anyMatch(e -> "quota.exceeded".equals(e.getEventType()));
        assertTrue(quotaEvent, "a WARN overage should emit quota.exceeded once");
    }

    @Test
    @DisplayName("quota.exceeded is emitted at most once a day")
    void quotaWarnOnlyOnce() throws Exception {
        // A tenant past its allowance is past it for every remaining send of the day. Without the
        // guard, a busy integration would deliver thousands of identical webhooks.
        String key = issueLiveKey();
        createPlan("QUOTA_WARN2", 1000, 1, 1000, "WARN", 5);
        assignPlan("QUOTA_WARN2", null);

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post(PATH).header("X-API-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(textBody("+91987654321" + i, "Hi")))
                    .andExpect(status().isAccepted());
        }

        long events = outboxRepository.findAll().stream()
                .filter(e -> "quota.exceeded".equals(e.getEventType()))
                .count();
        assertEquals(1, events);
    }

    @Test
    @DisplayName("a rejected send does not consume quota")
    void rejectionReleasesQuota() throws Exception {
        String key = issueLiveKey();
        createPlan("QUOTA_TIGHT", 1000, 1, 1000, "BLOCK", 5);
        assignPlan("QUOTA_TIGHT", null);

        Mockito.doThrow(new UpstreamRejectedException("window_closed", "closed", 400))
                .when(tenantsClient).validateSend(Mockito.any(), Mockito.any());
        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isBadRequest());

        // The one remaining slot must still be there — nothing was sent.
        Mockito.reset(tenantsClient);
        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543211", "Hi")))
                .andExpect(status().isAccepted());
    }

    // ------------------------------------------------------------------ sandbox

    @Test
    @DisplayName("a TEST accept synthesises nothing — the receipt is derived from upstream's sent")
    void sandboxSynthesisesNothingAtAcceptTime() throws Exception {
        // §0.10 as amended. Writing sent + delivered here double-fired message.sent (tenants-service
        // emits its own for a DRYRUN send) and could not be recalled once the send then failed, which
        // is how a failed message ended up followed by "delivered" in the live walk.
        String key = issueTestKey();

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isAccepted());

        assertTrue(outboxRepository.findAll().isEmpty(),
                "accepting a sandbox send must write no outbox rows of its own");
    }

    @Test
    @DisplayName("a LIVE key synthesises nothing")
    void liveDoesNotSynthesise() throws Exception {
        String key = issueLiveKey();

        mockMvc.perform(post(PATH).header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textBody("+919876543210", "Hi")))
                .andExpect(status().isAccepted());

        assertTrue(outboxRepository.findAll().isEmpty(),
                "a real send's statuses come from tenants-service, not from us");
    }
}
