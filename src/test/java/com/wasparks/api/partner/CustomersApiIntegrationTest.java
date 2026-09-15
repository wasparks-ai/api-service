package com.wasparks.api.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.auth.ApiKeyAuthFilter;
import com.wasparks.api.internal.AdminInternalClient;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /v1/customers} (§B2): provisioning, idempotency, setup links and direct mapping.
 *
 * <p>Both upstreams are stubbed. What is under test is this service's half — that it requires an
 * {@code externalRef}, that it distinguishes a create from a replay, that it turns a one-time token into
 * a URL without ever storing it, and that the three direct-mapping failures reach the partner intact.
 */
class CustomersApiIntegrationTest extends BaseIntegrationTest {

    @MockBean
    private AdminInternalClient adminClient;
    @MockBean
    private InternalTenantsClient tenantsClient;

    // ------------------------------------------------------------------ provisioning

    @Test
    @DisplayName("a create without externalRef is refused rather than silently non-idempotent")
    void externalRefIsRequired() throws Exception {
        UUID partnerId = makePartner("leadboard");
        String key = issuePartnerKey(partnerId);

        // admin-service would accept this and create a new tenant on every retry. Refusing is the
        // difference between an idempotent endpoint and one that merely can be used idempotently.
        mockMvc.perform(post("/v1/customers")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Dental\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));

        Mockito.verifyNoInteractions(adminClient);
    }

    @Test
    @DisplayName("a create is 201 and a replay of the same externalRef is 200")
    void createThenReplay() throws Exception {
        UUID partnerId = makePartner("leadboard");
        String key = issuePartnerKey(partnerId);
        UUID clientId = addClient(partnerId, "cust_8842", 500);

        Mockito.when(adminClient.createClientTenant(Mockito.eq(partnerId), Mockito.any(),
                        Mockito.any(), Mockito.anyMap()))
                .thenReturn(new AdminInternalClient.Created(tenantNode(clientId), true))
                .thenReturn(new AdminInternalClient.Created(tenantNode(clientId), false));

        String body = """
                {"name":"Acme Dental","externalRef":"cust_8842","messagesPerDayCap":500}""";

        mockMvc.perform(post("/v1/customers").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(clientId.toString()))
                .andExpect(jsonPath("$.cap").value(500));

        mockMvc.perform(post("/v1/customers").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(clientId.toString()));
    }

    @Test
    @DisplayName("the externalRef is forwarded to admin-service as the idempotency key")
    void forwardsExternalRef() throws Exception {
        UUID partnerId = makePartner("leadboard");
        String key = issuePartnerKey(partnerId);
        UUID clientId = addClient(partnerId, "cust_1", null);

        Mockito.when(adminClient.createClientTenant(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyMap()))
                .thenReturn(new AdminInternalClient.Created(tenantNode(clientId), true));

        mockMvc.perform(post("/v1/customers").header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"externalRef\":\"  cust_1  \"}"))
                .andExpect(status().isCreated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(adminClient).createClientTenant(Mockito.any(), Mockito.any(), Mockito.any(),
                body.capture());
        assertThat(body.getValue().get("externalRef")).isEqualTo("cust_1");
        assertThat(body.getValue().get("createdVia")).isEqualTo("API");
    }

    // ------------------------------------------------------------------ caps and status

    @Test
    @DisplayName("clearCap and messagesPerDayCap together are a 400")
    void capAndClearAreExclusive() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", 500);
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(patch("/v1/customers/" + clientId).header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messagesPerDayCap\":100,\"clearCap\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("clearCap removes the cap")
    void clearCap() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", 500);
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(patch("/v1/customers/" + clientId).header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearCap\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cap").doesNotExist());
    }

    @Test
    @DisplayName("an empty PATCH is refused rather than silently doing nothing")
    void emptyPatchIsRefused() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(patch("/v1/customers/" + clientId).header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ setup links

    @Test
    @DisplayName("the setup link URL is built from the token, which is never stored or returned again")
    void setupLinkUrl() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);
        UUID linkId = UUID.randomUUID();

        Mockito.when(tenantsClient.createSetupLink(Mockito.any(), Mockito.any()))
                .thenReturn(objectMapper.readTree("""
                        {"id":"%s","token":"aB3dEf9hIjKlMnOpQrStUvWxYz012345",
                         "status":"PENDING","expiresAt":"2026-09-23T09:00:00Z",
                         "createdAt":"2026-09-16T09:00:00Z"}
                        """.formatted(linkId)));

        MvcResult result = mockMvc.perform(post("/v1/customers/" + clientId + "/setup-links")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successUrl":"https://leadboard.example/done",
                                 "failureUrl":"https://leadboard.example/failed"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url")
                        .value("http://localhost:5173/setup/aB3dEf9hIjKlMnOpQrStUvWxYz012345"))
                .andReturn();

        // The token exists in this one response and nowhere else. A later read must not carry it, or
        // read access to the API would be enough to connect somebody else's number.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"token\"");

        // The customer's tenant is sent in the BODY here — the one call where it is — while the header
        // stays the acting tenant.
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        Mockito.verify(tenantsClient).createSetupLink(Mockito.any(), sent.capture());
        assertThat(sent.getValue().get("tenantId").asText()).isEqualTo(clientId.toString());
        assertThat(sent.getValue().get("partnerId").asText()).isEqualTo(partnerId.toString());
    }

    @Test
    @DisplayName("a non-https redirect target is refused — this is an open redirect otherwise")
    void redirectMustBeHttps() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(post("/v1/customers/" + clientId + "/setup-links")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"successUrl":"javascript:alert(1)",
                                 "failureUrl":"https://leadboard.example/failed"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));

        Mockito.verify(tenantsClient, Mockito.never()).createSetupLink(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("a link belonging to another partner is a 404, not another partner's status")
    void foreignLinkIs404() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(get("/v1/customers/" + clientId + "/setup-links/" + UUID.randomUUID())
                        .header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ direct mapping

    @Test
    @DisplayName("waba_not_shared passes through with its docsUrl intact")
    void wabaNotSharedPassesThrough() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);

        // Upstream renders its details as TOP-LEVEL keys beside code and message (amendment 6), so the
        // client has to lift them out rather than look for a nested object.
        Mockito.when(tenantsClient.mapAccount(Mockito.any(), Mockito.any()))
                .thenThrow(new UpstreamRejectedException("waba_not_shared",
                        "This WABA has not been shared with WaSparks.", 422,
                        Map.of("docsUrl", "https://developers.wasparks.com/docs/share-waba")));

        mockMvc.perform(post("/v1/customers/" + clientId + "/phone-numbers")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phoneNumberId":"1234","wabaId":"5678","accessToken":"EAAG..."}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("waba_not_shared"))
                .andExpect(jsonPath("$.error.details.docsUrl")
                        .value("https://developers.wasparks.com/docs/share-waba"));
    }

    @Test
    @DisplayName("number_not_in_waba and token_invalid keep their own codes and statuses")
    void otherMappingFailures() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);
        String body = """
                {"phoneNumberId":"1234","wabaId":"5678","accessToken":"EAAG..."}""";

        Mockito.when(tenantsClient.mapAccount(Mockito.any(), Mockito.any()))
                .thenThrow(new UpstreamRejectedException("number_not_in_waba",
                        "That number is not in that WABA.", 422));
        mockMvc.perform(post("/v1/customers/" + clientId + "/phone-numbers")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("number_not_in_waba"));

        Mockito.reset(tenantsClient);
        Mockito.when(tenantsClient.mapAccount(Mockito.any(), Mockito.any()))
                .thenThrow(new UpstreamRejectedException("token_invalid",
                        "Meta rejected that token.", 401));
        mockMvc.perform(post("/v1/customers/" + clientId + "/phone-numbers")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("token_invalid"));
    }

    @Test
    @DisplayName("a mapping body missing a field never reaches Meta")
    void mappingBodyIsValidatedLocally() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(post("/v1/customers/" + clientId + "/phone-numbers")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumberId\":\"1234\"}"))
                .andExpect(status().isBadRequest());

        Mockito.verify(tenantsClient, Mockito.never()).mapAccount(Mockito.any(), Mockito.any());
    }

    // ------------------------------------------------------------------ client keys

    @Test
    @DisplayName("a client key is scoped to the customer and carries no partner id")
    void clientKeyIsOrdinary() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);

        MvcResult result = mockMvc.perform(post("/v1/customers/" + clientId + "/keys")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme's own key\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(clientId.toString()))
                .andReturn();

        String issued = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("key").asText();
        assertThat(issued).startsWith("wsk_live_");

        UUID keyTenant = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM api_keys WHERE key_hash = ?", UUID.class,
                com.wasparks.api.util.Hashing.sha256Hex(issued));
        assertThat(keyTenant).isEqualTo(clientId);

        Integer partnerColumn = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM api_keys WHERE tenant_id = ? AND partner_id IS NOT NULL",
                Integer.class, clientId);
        assertThat(partnerColumn).isZero();
    }

    // ------------------------------------------------------------------ fixtures

    private JsonNode tenantNode(UUID tenantId) {
        try {
            return objectMapper.readTree("""
                    {"partnerId":"%s","tenantId":"%s","externalRef":"cust_8842",
                     "displayName":"Acme Dental","status":"ACTIVE","appAccess":false,
                     "messagesPerDayCap":500,"createdVia":"API","linkedAt":"2026-09-16T09:00:00Z"}
                    """.formatted(UUID.randomUUID(), tenantId));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
