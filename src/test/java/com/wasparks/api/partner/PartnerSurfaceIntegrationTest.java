package com.wasparks.api.partner;

import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.auth.ApiKeyAuthFilter;
import com.wasparks.api.entity.ApiOutboxEvent;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.internal.AdminInternalClient;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import com.wasparks.api.plans.EffectiveLimits;
import com.wasparks.api.plans.PlanResolver;
import com.wasparks.api.util.Uuid7;
import com.wasparks.api.webhook.OutboxPoller;
import com.wasparks.api.webhook.WebhookEndpointService;
import com.wasparks.api.webhook.WebhookEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rest of the partner surface: audience proxying, media redirects, partner fan-out and the console.
 *
 * <p>The fan-out cases are the ones with teeth. A partner endpoint that misses its clients' UI sends
 * leaves a hole in the partner's own product, and one that receives another partner's events is a breach
 * — so both directions are asserted rather than only the happy one.
 */
class PartnerSurfaceIntegrationTest extends BaseIntegrationTest {

    @MockBean
    private InternalTenantsClient tenantsClient;
    @MockBean
    private AdminInternalClient adminClient;

    @Autowired
    private OutboxPoller outboxPoller;
    @Autowired
    private WebhookEndpointService endpointService;
    @Autowired
    private PlanResolver planResolver;

    // ------------------------------------------------------------------ audiences

    @Test
    @DisplayName("audience create proxies through with the acting tenant")
    void audienceCreateProxies() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);

        Mockito.when(tenantsClient.createAudience(Mockito.any(), Mockito.any()))
                .thenReturn(objectMapper.readTree(
                        "{\"id\":\"aud_1\",\"name\":\"Sector 45\",\"memberCount\":2}"));

        mockMvc.perform(post("/v1/audiences")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .header(ApiKeyAuthFilter.HEADER_TENANT_ID, clientId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Sector 45","members":[{"phone":"9198"},{"phone":"9199"}]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberCount").value(2));

        // The acting tenant on the principal is what the internal client scopes by, so this is the
        // assertion that X-Tenant-Id actually reached the upstream call.
        Mockito.verify(tenantsClient).createAudience(
                Mockito.argThat(principal -> principal.tenantId().equals(clientId)), Mockito.any());
    }

    @Test
    @DisplayName("audience_in_use passes through as a 409 with its own code")
    void audienceInUse() throws Exception {
        String key = issueLiveKey();
        Mockito.doThrow(new UpstreamRejectedException("audience_in_use",
                        "A scheduled campaign still uses this audience.", 409))
                .when(tenantsClient).deleteAudience(Mockito.any(), Mockito.anyString());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/v1/audiences/aud_1").header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("audience_in_use"));
    }

    @Test
    @DisplayName("removing members without a phones array is refused locally")
    void removeMembersNeedsPhones() throws Exception {
        String key = issueLiveKey();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/v1/audiences/aud_1/members")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));

        Mockito.verify(tenantsClient, Mockito.never())
                .removeAudienceMembers(Mockito.any(), Mockito.anyString(), Mockito.any());
    }

    // ------------------------------------------------------------------ media

    @Test
    @DisplayName("media redirects 302 to the signed URL and forbids caching it")
    void mediaRedirects() throws Exception {
        String key = issueLiveKey();
        UUID messageId = Uuid7.generate();
        Mockito.when(tenantsClient.mediaUrl(Mockito.any(), Mockito.eq(messageId.toString())))
                .thenReturn(objectMapper.readTree("""
                        {"messageId":"%s","downloadUrl":"https://storage.googleapis.com/x?sig=abc",
                         "expiresInSeconds":3600}""".formatted(messageId)));

        mockMvc.perform(get("/v1/media/msg_" + messageId).header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://storage.googleapis.com/x?sig=abc"))
                // The target's signature expires within the hour, so nothing may cache this redirect.
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("a message with no media, or another tenant's, is the same 404")
    void mediaNotFound() throws Exception {
        String key = issueLiveKey();
        UUID messageId = Uuid7.generate();
        Mockito.when(tenantsClient.mediaUrl(Mockito.any(), Mockito.anyString()))
                .thenThrow(new UpstreamRejectedException("not_found", "No such message.", 404));

        mockMvc.perform(get("/v1/media/" + messageId).header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("media_not_found"));
    }

    @Test
    @DisplayName("media needs the media:read scope")
    void mediaNeedsItsScope() throws Exception {
        String key = issueKeyWithScopes(Scope.MESSAGES_READ);
        mockMvc.perform(get("/v1/media/msg_" + Uuid7.generate()).header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("insufficient_scope"));
    }

    // ------------------------------------------------------------------ partner fan-out

    @Test
    @DisplayName("a partner endpoint receives a client's events, including UI sends")
    void partnerEndpointReceivesClientEvents() {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);

        // Filed under the partner's OWN tenant with partner_id set — that is what a partner endpoint is.
        var endpoint = endpointService.create(tenantId, partnerId, tenantUserId,
                "http://localhost:9/hook", List.of("*"), generousLimits()).endpoint();
        assertThat(endpoint.isIncludeUiSends())
                .as("partner endpoints are forced to include UI sends (§0.7)")
                .isTrue();

        // A message a human typed in the inbox: no apiKeyId. A P1 tenant endpoint would not see it.
        writeEvent(clientId, WebhookEvents.MESSAGE_SENT, new HashMap<>(Map.of("wamid", "wamid.UI")));
        outboxPoller.publishBatch();

        assertThat(deliveryRepository.findAll())
                .as("the partner is the system of record for its client's conversations")
                .anyMatch(delivery -> delivery.getEndpointId().equals(endpoint.getId()));
    }

    @Test
    @DisplayName("a plain tenant endpoint still does not receive UI sends")
    void tenantEndpointStillFiltersUiSends() {
        var endpoint = endpointService.create(tenantId, null, tenantUserId,
                "http://localhost:9/hook", List.of("*"), generousLimits()).endpoint();

        writeEvent(tenantId, WebhookEvents.MESSAGE_SENT, new HashMap<>(Map.of("wamid", "wamid.UI")));
        outboxPoller.publishBatch();

        assertThat(deliveryRepository.findAll())
                .as("the P1 filter is unchanged for an ordinary tenant")
                .noneMatch(delivery -> delivery.getEndpointId().equals(endpoint.getId()));
    }

    @Test
    @DisplayName("message.received reaches a tenant endpoint although it carries no apiKeyId")
    void inboundIsNeverFiltered() {
        var endpoint = endpointService.create(tenantId, null, tenantUserId,
                "http://localhost:9/hook", List.of("message.*"), generousLimits()).endpoint();

        // Nobody on our side sent an inbound message, so it has no apiKeyId by nature. Applying the
        // outbound filter here would silence every customer reply — the most valuable event on the API.
        writeEvent(tenantId, WebhookEvents.MESSAGE_RECEIVED,
                new HashMap<>(Map.of("wamid", "wamid.IN")));
        outboxPoller.publishBatch();

        assertThat(deliveryRepository.findAll())
                .anyMatch(delivery -> delivery.getEndpointId().equals(endpoint.getId()));
    }

    @Test
    @DisplayName("the fan-out stamps partnerId on the payload and never overwrites upstream's")
    void stampsPartnerId() {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        endpointService.create(tenantId, partnerId, tenantUserId, "http://localhost:9/hook",
                List.of("*"), generousLimits());

        ApiOutboxEvent event = writeEvent(clientId, WebhookEvents.CUSTOMER_CONNECTED,
                new HashMap<>(Map.of("phoneNumberId", "1234")));
        outboxPoller.publishBatch();

        assertThat(outboxRepository.findById(event.getId()).orElseThrow().getPayload())
                .containsEntry("partnerId", partnerId.toString());
    }

    @Test
    @DisplayName("one partner's endpoint never receives another partner's client events")
    void partnersAreIsolated() {
        UUID mine = makePartner("leadboard");
        endpointService.create(tenantId, mine, tenantUserId, "http://localhost:9/hook",
                List.of("*"), generousLimits());

        UUID otherOwner = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, company_name, contact_email, status) "
                        + "VALUES (?, 'Rival', 'rival@example.test', 'ACTIVE'::tenant_status)",
                otherOwner);
        UUID theirs = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO api_partners (id, name, slug, status, owner_tenant_id, webhook_scope)
                        VALUES (?, 'HomeListing', 'homelisting', 'ACTIVE', ?, 'PARTNER')
                        """, theirs, otherOwner);
        UUID theirClient = addClient(theirs, "their_cust", null);

        writeEvent(theirClient, WebhookEvents.MESSAGE_RECEIVED,
                new HashMap<>(Map.of("wamid", "wamid.THEIRS")));
        outboxPoller.publishBatch();

        assertThat(deliveryRepository.findAll())
                .as("a partner must never see a competitor's customers")
                .isEmpty();
    }

    // ------------------------------------------------------------------ the console

    @Test
    @DisplayName("a tenant that is not a partner gets 404 not_a_partner, never a 403")
    void consoleIs404ForNonPartners() throws Exception {
        Mockito.when(adminClient.findPartnerByOwnerTenant(Mockito.eq(tenantId), Mockito.any()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/partner")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_a_partner"));
    }

    @Test
    @DisplayName("the console resolves a partner and reports its plan and pool")
    void consoleResolvesAPartner() throws Exception {
        UUID partnerId = makePartner("leadboard");
        assignPlanTo(tenantId, "PARTNER_STARTER");
        Mockito.when(adminClient.findPartnerByOwnerTenant(Mockito.eq(tenantId), Mockito.any()))
                .thenReturn(Optional.of(objectMapper.readTree("""
                        {"id":"%s","name":"LeadBoard","slug":"leadboard","status":"ACTIVE",
                         "ownerTenantId":"%s","clientCount":0}""".formatted(partnerId, tenantId))));

        mockMvc.perform(get("/v1/partner")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partner.slug").value("leadboard"))
                .andExpect(jsonPath("$.plan.code").value("PARTNER_STARTER"))
                .andExpect(jsonPath("$.plan.billingModel").value("FIXED"))
                .andExpect(jsonPath("$.plan.partnerPlan").value(true))
                // FIXED plans report a meter; a METERED one would report a count and a price instead.
                .andExpect(jsonPath("$.pool.remainingToday").value(20000));
    }

    @Test
    @DisplayName("a METERED plan reports a running estimate rather than a meaningless meter")
    void consoleMeteredPlan() throws Exception {
        UUID partnerId = makePartner("leadboard");
        assignPlanTo(tenantId, "PARTNER_METERED");
        Mockito.when(adminClient.findPartnerByOwnerTenant(Mockito.eq(tenantId), Mockito.any()))
                .thenReturn(Optional.of(objectMapper.readTree("""
                        {"id":"%s","name":"LeadBoard","slug":"leadboard","status":"ACTIVE"}"""
                        .formatted(partnerId))));

        mockMvc.perform(get("/v1/partner")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.billingModel").value("METERED"))
                .andExpect(jsonPath("$.plan.messagesPerDay").doesNotExist())
                .andExpect(jsonPath("$.pool.remainingToday").doesNotExist())
                .andExpect(jsonPath("$.pool.currency").value("INR"));
    }

    @Test
    @DisplayName("the console is closed to a plain API key — it is a session surface")
    void consoleRejectsApiKeys() throws Exception {
        String key = issueLiveKey();
        mockMvc.perform(get("/v1/partner").header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a partner key created from the console carries the partner prefix and id")
    void consoleMintsPartnerKeys() throws Exception {
        UUID partnerId = makePartner("leadboard");
        Mockito.when(adminClient.findPartnerByOwnerTenant(Mockito.eq(tenantId), Mockito.any()))
                .thenReturn(Optional.of(objectMapper.readTree(
                        "{\"id\":\"%s\",\"slug\":\"leadboard\"}".formatted(partnerId))));

        var result = mockMvc.perform(post("/v1/partner/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"server key\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.partner").value(true))
                .andReturn();

        String issued = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("key").asText();
        assertThat(issued).startsWith("wsk_partner_live_");
    }

    // ------------------------------------------------------------------ fixtures

    private EffectiveLimits generousLimits() {
        return new EffectiveLimits("TEST", 1000, 10000, 100000, 100, 10, 10,
                com.wasparks.api.enums.OveragePolicy.BLOCK, false,
                com.wasparks.api.enums.BillingModel.FIXED, null, null, false);
    }

    private ApiOutboxEvent writeEvent(UUID eventTenantId, String type, Map<String, Object> payload) {
        return outboxRepository.save(ApiOutboxEvent.builder()
                .id(Uuid7.generate())
                .tenantId(eventTenantId)
                .eventType(type)
                .aggregateType(WebhookEvents.AGGREGATE_MESSAGE)
                .aggregateId(Uuid7.generate())
                .payload(payload)
                .createdAt(Instant.now().minusSeconds(1))
                .build());
    }
}
