package com.wasparks.api.partner;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.auth.ApiKeyAuthFilter;
import com.wasparks.api.internal.AdminInternalClient;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.internal.UpstreamRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console's cross-client campaign views and the per-customer frequency guard (§B6).
 *
 * <p>Two of the three upstream endpoints exercised here — {@code GET /internal/v1/campaigns/across} and
 * {@code GET /internal/v1/tenants/{id}/settings} — are <b>not in internal.md</b>. The stubs below encode
 * what this service assumes of them, so if the real shapes differ these are the assertions that will say
 * so rather than a partner discovering it.
 */
class PartnerConsoleCampaignsIntegrationTest extends BaseIntegrationTest {

    @MockBean
    private InternalTenantsClient tenantsClient;
    @MockBean
    private AdminInternalClient adminClient;

    private UUID partnerId;

    @BeforeEach
    void makeMeAPartner() throws Exception {
        partnerId = makePartner("leadboard");
        Mockito.when(adminClient.findPartnerByOwnerTenant(Mockito.eq(tenantId), Mockito.any()))
                .thenReturn(Optional.of(objectMapper.readTree("""
                        {"id":"%s","name":"LeadBoard","slug":"leadboard","status":"ACTIVE",
                         "ownerTenantId":"%s"}""".formatted(partnerId, tenantId))));
    }

    private String jwt() {
        return "Bearer " + tenantJwt("TENANT_OWNER");
    }

    // ------------------------------------------------------------------ the merged list

    @Test
    @DisplayName("with no customerId the list spans the partner's own tenant and every ACTIVE client")
    void mergedList() throws Exception {
        UUID first = addClient(partnerId, "cust_1", null);
        UUID second = addClient(partnerId, "cust_2", null);

        Mockito.when(tenantsClient.campaignsAcross(Mockito.any(), Mockito.any(), Mockito.anyList(),
                        Mockito.any()))
                .thenReturn(objectMapper.readTree("""
                        {"content":[
                          {"id":"c1","name":"Ours","status":"COMPLETED","tenantId":"%s"},
                          {"id":"c2","name":"Theirs","status":"RUNNING","tenantId":"%s"}]}
                        """.formatted(tenantId, first)));

        mockMvc.perform(get("/v1/partner/campaigns").header("Authorization", jwt()))
                .andExpect(status().isOk())
                // The partner's own tenant has no customer id of its own, so it reports `self`.
                .andExpect(jsonPath("$.data[0].customerId").value("self"))
                .andExpect(jsonPath("$.data[0].customerName").value("LeadBoard"))
                .andExpect(jsonPath("$.data[1].customerId").value(first.toString()))
                .andExpect(jsonPath("$.data[1].customerName").value("Client cust_1"))
                // Upstream's own fields survive the decoration.
                .andExpect(jsonPath("$.data[1].status").value("RUNNING"))
                .andExpect(jsonPath("$.meta.customerCount").value(3));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> tenants = ArgumentCaptor.forClass(List.class);
        Mockito.verify(tenantsClient).campaignsAcross(Mockito.eq(tenantId), Mockito.any(),
                tenants.capture(), Mockito.any());
        assertThat(tenants.getValue()).containsExactlyInAnyOrder(tenantId, first, second);
    }

    @Test
    @DisplayName("a suspended client is left out of the merged list")
    void suspendedClientsAreExcluded() throws Exception {
        UUID active = addClient(partnerId, "cust_1", null);
        addClient(partnerId, "cust_2", null, "SUSPENDED");

        Mockito.when(tenantsClient.campaignsAcross(Mockito.any(), Mockito.any(), Mockito.anyList(),
                        Mockito.any()))
                .thenReturn(objectMapper.readTree("{\"content\":[]}"));

        mockMvc.perform(get("/v1/partner/campaigns").header("Authorization", jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.customerCount").value(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> tenants = ArgumentCaptor.forClass(List.class);
        Mockito.verify(tenantsClient).campaignsAcross(Mockito.any(), Mockito.any(),
                tenants.capture(), Mockito.any());
        // Its campaigns are not running and cannot be started; showing them would be rows the partner
        // can do nothing about from this screen.
        assertThat(tenants.getValue()).containsExactlyInAnyOrder(tenantId, active);
    }

    @Test
    @DisplayName("customerId=self reads the partner's own tenant through the ordinary per-tenant list")
    void selfSelectsTheOwnTenant() throws Exception {
        Mockito.when(tenantsClient.listCampaigns(Mockito.eq(tenantId), Mockito.any(), Mockito.any()))
                .thenReturn(objectMapper.readTree("{\"content\":[{\"id\":\"c1\"}]}"));

        mockMvc.perform(get("/v1/partner/campaigns?customerId=self").header("Authorization", jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("c1"));

        Mockito.verify(tenantsClient, Mockito.never()).campaignsAcross(Mockito.any(), Mockito.any(),
                Mockito.anyList(), Mockito.any());
    }

    @Test
    @DisplayName("a named customer narrows to that customer")
    void namedCustomer() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        Mockito.when(tenantsClient.listCampaigns(Mockito.eq(clientId), Mockito.any(), Mockito.any()))
                .thenReturn(objectMapper.readTree("{\"content\":[{\"id\":\"c9\"}]}"));

        mockMvc.perform(get("/v1/partner/campaigns?customerId=" + clientId)
                        .header("Authorization", jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("c9"));
    }

    @Test
    @DisplayName("a customerId that is not this partner's is a 404")
    void foreignCustomerIs404() throws Exception {
        mockMvc.perform(get("/v1/partner/campaigns?customerId=" + UUID.randomUUID())
                        .header("Authorization", jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a malformed customerId is a validation error, not a 500")
    void malformedCustomerId() throws Exception {
        mockMvc.perform(get("/v1/partner/campaigns?customerId=nonsense")
                        .header("Authorization", jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    // ------------------------------------------------------------------ recipients

    @Test
    @DisplayName("recipients read through the named customer, paged by an opaque cursor")
    void recipientsWithCustomerId() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        Mockito.when(tenantsClient.campaignRecipients(Mockito.eq(clientId), Mockito.any(),
                        Mockito.eq("c1"), Mockito.any()))
                .thenReturn(objectMapper.readTree("""
                        {"content":[{"phone":"+9198","status":"SUPPRESSED"}]}"""));

        mockMvc.perform(get("/v1/partner/campaigns/c1/recipients"
                        + "?customerId=" + clientId + "&status=SUPPRESSED&limit=1")
                        .header("Authorization", jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUPPRESSED"))
                // A full page means there may be another.
                .andExpect(jsonPath("$.meta.next_cursor").exists());

        ArgumentCaptor<MultiValueMap<String, String>> query =
                ArgumentCaptor.forClass(MultiValueMap.class);
        Mockito.verify(tenantsClient).campaignRecipients(Mockito.eq(clientId), Mockito.any(),
                Mockito.eq("c1"), query.capture());
        assertThat(query.getValue().getFirst("status")).isEqualTo("SUPPRESSED");
        assertThat(query.getValue().getFirst("page")).isEqualTo("0");
    }

    @Test
    @DisplayName("a short page ends the walk — no next_cursor")
    void lastPageHasNoCursor() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        Mockito.when(tenantsClient.campaignRecipients(Mockito.eq(clientId), Mockito.any(),
                        Mockito.anyString(), Mockito.any()))
                .thenReturn(objectMapper.readTree("""
                        {"content":[{"phone":"+9198","status":"PENDING"}]}"""));

        mockMvc.perform(get("/v1/partner/campaigns/c1/recipients?customerId=" + clientId
                        + "&limit=25").header("Authorization", jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.next_cursor").doesNotExist());
    }

    @Test
    @DisplayName("the cursor round-trips to the next page")
    void cursorRoundTrips() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        Mockito.when(tenantsClient.campaignRecipients(Mockito.eq(clientId), Mockito.any(),
                        Mockito.anyString(), Mockito.any()))
                .thenReturn(objectMapper.readTree("""
                        {"content":[{"phone":"+9198"}]}"""));

        String cursor = objectMapper.readTree(mockMvc.perform(
                        get("/v1/partner/campaigns/c1/recipients?customerId=" + clientId + "&limit=1")
                                .header("Authorization", jwt()))
                .andReturn().getResponse().getContentAsString())
                .at("/meta/next_cursor").asText();

        mockMvc.perform(get("/v1/partner/campaigns/c1/recipients?customerId=" + clientId
                        + "&limit=1&cursor=" + cursor).header("Authorization", jwt()))
                .andExpect(status().isOk());

        ArgumentCaptor<MultiValueMap<String, String>> query =
                ArgumentCaptor.forClass(MultiValueMap.class);
        Mockito.verify(tenantsClient, Mockito.times(2)).campaignRecipients(Mockito.eq(clientId),
                Mockito.any(), Mockito.anyString(), query.capture());
        assertThat(query.getAllValues().get(1).getFirst("page")).isEqualTo("1");
    }

    @Test
    @DisplayName("a cursor we did not issue is refused rather than silently treated as page zero")
    void forgedCursor() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        mockMvc.perform(get("/v1/partner/campaigns/c1/recipients?customerId=" + clientId
                        + "&cursor=not-a-cursor").header("Authorization", jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    @DisplayName("without customerId the owner is found by asking each of the partner's tenants")
    void recipientsResolveTheOwner() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);

        // The partner's own tenant does not have it; the client does.
        Mockito.when(tenantsClient.getCampaign(Mockito.eq(tenantId), Mockito.any(),
                        Mockito.eq("c1")))
                .thenThrow(new UpstreamRejectedException("not_found", "No such campaign.", 404));
        Mockito.when(tenantsClient.getCampaign(Mockito.eq(clientId), Mockito.any(), Mockito.eq("c1")))
                .thenReturn(objectMapper.readTree("{\"id\":\"c1\"}"));
        Mockito.when(tenantsClient.campaignRecipients(Mockito.eq(clientId), Mockito.any(),
                        Mockito.eq("c1"), Mockito.any()))
                .thenReturn(objectMapper.readTree("{\"content\":[{\"phone\":\"+9198\"}]}"));

        mockMvc.perform(get("/v1/partner/campaigns/c1/recipients").header("Authorization", jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].phone").value("+9198"));
    }

    @Test
    @DisplayName("a campaign belonging to nobody the partner owns is a 404")
    void recipientsOfAForeignCampaign() throws Exception {
        addClient(partnerId, "cust_1", null);
        Mockito.when(tenantsClient.getCampaign(Mockito.any(), Mockito.any(), Mockito.anyString()))
                .thenThrow(new UpstreamRejectedException("not_found", "No such campaign.", 404));

        mockMvc.perform(get("/v1/partner/campaigns/c1/recipients").header("Authorization", jwt()))
                .andExpect(status().isNotFound());

        Mockito.verify(tenantsClient, Mockito.never()).campaignRecipients(Mockito.any(),
                Mockito.any(), Mockito.anyString(), Mockito.any());
    }

    @Test
    @DisplayName("the console is the only way in — an API key gets nothing here")
    void apiKeysAreRefused() throws Exception {
        String key = issueLiveKey();
        mockMvc.perform(get("/v1/partner/campaigns/c1/recipients")
                        .header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ the frequency guard

    @Test
    @DisplayName("every customer row carries minDaysBetweenMarketing")
    void customersCarryTheGuard() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        Mockito.when(tenantsClient.getTenantSettings(Mockito.eq(clientId), Mockito.any()))
                .thenReturn(objectMapper.readTree("""
                        {"tenantId":"%s","minDaysBetweenMarketing":7}""".formatted(clientId)));

        mockMvc.perform(get("/v1/partner/customers").header("Authorization", jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].minDaysBetweenMarketing").value(7));

        mockMvc.perform(get("/v1/partner/customers/" + clientId).header("Authorization", jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minDaysBetweenMarketing").value(7));
    }

    @Test
    @DisplayName("the partner-key surface carries it too")
    void partnerKeyCustomersCarryTheGuard() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);
        Mockito.when(tenantsClient.getTenantSettings(Mockito.eq(clientId), Mockito.any()))
                .thenReturn(objectMapper.readTree("{\"minDaysBetweenMarketing\":3}"));

        mockMvc.perform(get("/v1/customers/" + clientId).header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minDaysBetweenMarketing").value(3));
    }

    @Test
    @DisplayName("it is read once per customer per window, not once per row rendered")
    void theGuardIsCached() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        Mockito.when(tenantsClient.getTenantSettings(Mockito.eq(clientId), Mockito.any()))
                .thenReturn(objectMapper.readTree("{\"minDaysBetweenMarketing\":7}"));

        mockMvc.perform(get("/v1/partner/customers").header("Authorization", jwt()));
        mockMvc.perform(get("/v1/partner/customers").header("Authorization", jwt()));
        mockMvc.perform(get("/v1/partner/customers/" + clientId).header("Authorization", jwt()));

        // Three renders, one internal call. Without the cache a partner listing two hundred customers
        // would make two hundred HTTP calls inside one request.
        Mockito.verify(tenantsClient, Mockito.times(1))
                .getTenantSettings(Mockito.eq(clientId), Mockito.any());
    }

    @Test
    @DisplayName("setting the guard drops the cache, so the next read shows the new value")
    void patchInvalidatesTheCache() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        Mockito.when(tenantsClient.getTenantSettings(Mockito.eq(clientId), Mockito.any()))
                .thenReturn(objectMapper.readTree("{\"minDaysBetweenMarketing\":0}"))
                .thenReturn(objectMapper.readTree("{\"minDaysBetweenMarketing\":14}"));
        Mockito.when(tenantsClient.updateTenantSettings(Mockito.any(), Mockito.any()))
                .thenReturn(objectMapper.readTree("{\"minDaysBetweenMarketing\":14}"));

        mockMvc.perform(get("/v1/partner/customers/" + clientId).header("Authorization", jwt()))
                .andExpect(jsonPath("$.minDaysBetweenMarketing").value(0));

        mockMvc.perform(patch("/v1/partner/customers/" + clientId + "/settings")
                        .header("Authorization", jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minDaysBetweenMarketing\":14}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/partner/customers/" + clientId).header("Authorization", jwt()))
                .andExpect(jsonPath("$.minDaysBetweenMarketing").value(14));
    }

    @Test
    @DisplayName("an unreadable setting reports 0 rather than failing the customer list")
    void guardFailsSoft() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        Mockito.when(tenantsClient.getTenantSettings(Mockito.any(), Mockito.any()))
                .thenThrow(new UpstreamRejectedException("not_found", "No such endpoint.", 404));

        // A guard we cannot read about is a field on a screen. A customer list that 500s because one
        // internal call failed is an outage.
        mockMvc.perform(get("/v1/partner/customers").header("Authorization", jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].minDaysBetweenMarketing").value(0));
        assertThat(clientId).isNotNull();
    }

    @Test
    @DisplayName("the guard PATCH still validates its range locally")
    void guardRange() throws Exception {
        UUID clientId = addClient(partnerId, "cust_1", null);
        mockMvc.perform(patch("/v1/partner/customers/" + clientId + "/settings")
                        .header("Authorization", jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minDaysBetweenMarketing\":400}"))
                .andExpect(status().isBadRequest());

        Mockito.verify(tenantsClient, Mockito.never())
                .updateTenantSettings(Mockito.any(), Mockito.any());
    }

    /** Guards against the stub drifting from what the service actually calls. */
    @Test
    @DisplayName("the merged list asks upstream with the partner's own tenant as the acting one")
    void acrossActsAsThePartner() throws Exception {
        addClient(partnerId, "cust_1", null);
        Mockito.when(tenantsClient.campaignsAcross(Mockito.any(), Mockito.any(), Mockito.anyList(),
                        Mockito.any()))
                .thenReturn((JsonNode) objectMapper.readTree("[]"));

        mockMvc.perform(get("/v1/partner/campaigns?status=RUNNING").header("Authorization", jwt()))
                .andExpect(status().isOk());

        ArgumentCaptor<MultiValueMap<String, String>> query =
                ArgumentCaptor.forClass(MultiValueMap.class);
        Mockito.verify(tenantsClient).campaignsAcross(Mockito.eq(tenantId), Mockito.any(),
                Mockito.anyList(), query.capture());
        assertThat(query.getValue().getFirst("status")).isEqualTo("RUNNING");
    }
}
