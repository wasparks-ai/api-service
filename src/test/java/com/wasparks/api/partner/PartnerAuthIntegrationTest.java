package com.wasparks.api.partner;

import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.auth.ApiKeyAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Partner key resolution (§B1) — the security boundary of the whole partner surface.
 *
 * <p>Every case here is about one question: which tenant does {@code X-Tenant-Id} actually resolve to,
 * and what happens when the answer is "none of yours". Getting that wrong is the difference between a
 * multi-tenant platform and a data breach, so the foreign-tenant case is asserted on the status code and
 * on the body — a 403 would be a leak even with an unhelpful message, because its very existence confirms
 * the tenant.
 */
class PartnerAuthIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("no X-Tenant-Id: a partner key acts on the partner's own tenant")
    void actsOnOwnTenantWithoutHeader() throws Exception {
        UUID partnerId = makePartner("leadboard");
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(get("/v1/customers").header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("X-Tenant-Id naming one of the partner's clients resolves")
    void resolvesOwnClient() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_1", null);
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(get("/v1/customers/" + clientId)
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .header(ApiKeyAuthFilter.HEADER_TENANT_ID, clientId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(clientId.toString()))
                .andExpect(jsonPath("$.externalRef").value("cust_1"));
    }

    @Test
    @DisplayName("a tenant that is not this partner's client is 404, never 403")
    void foreignTenantIs404() throws Exception {
        UUID partnerId = makePartner("leadboard");
        String key = issuePartnerKey(partnerId);

        // A real, live tenant — belonging to somebody else. This is the exact probe an attacker makes,
        // and the answer must be indistinguishable from a tenant id that was never issued.
        UUID strangerPartner = makePartnerFor(otherTenant("Rival Ltd"), "homelisting");
        UUID strangersClient = addClient(strangerPartner, "their_cust", null);

        mockMvc.perform(get("/v1/campaigns")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .header(ApiKeyAuthFilter.HEADER_TENANT_ID, strangersClient.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    @DisplayName("a tenant id that exists nowhere gets the identical 404")
    void unknownTenantIsTheSame404() throws Exception {
        UUID partnerId = makePartner("leadboard");
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(get("/v1/campaigns")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .header(ApiKeyAuthFilter.HEADER_TENANT_ID, UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    @DisplayName("a suspended client is 403 customer_suspended, not 404 — the partner suspended it")
    void suspendedClientIsForbidden() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_2", null, "SUSPENDED");
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(get("/v1/campaigns")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .header(ApiKeyAuthFilter.HEADER_TENANT_ID, clientId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("customer_suspended"));
    }

    @Test
    @DisplayName("a suspended client is still visible and patchable on the management plane")
    void suspendedClientStaysVisible() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_3", null, "SUSPENDED");
        String key = issuePartnerKey(partnerId);

        // The partner must be able to see what it suspended in order to lift it. A 404 here would leave
        // a customer permanently stuck from the partner's own point of view.
        mockMvc.perform(get("/v1/customers/" + clientId).header(ApiKeyAuthFilter.HEADER, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("a malformed X-Tenant-Id is a validation error, not a 500")
    void malformedHeaderIsRejected() throws Exception {
        UUID partnerId = makePartner("leadboard");
        String key = issuePartnerKey(partnerId);

        mockMvc.perform(get("/v1/campaigns")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .header(ApiKeyAuthFilter.HEADER_TENANT_ID, "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    @DisplayName("an ordinary tenant key ignores X-Tenant-Id rather than honouring or rejecting it")
    void tenantKeyIgnoresTheHeader() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_4", null);
        String tenantKey = issueLiveKey();

        // A plain key pointed at somebody else's tenant must not reach it — and must not be broken by a
        // header an SDK set globally. It simply acts as itself, and customers is partner-only.
        mockMvc.perform(get("/v1/customers")
                        .header(ApiKeyAuthFilter.HEADER, tenantKey)
                        .header(ApiKeyAuthFilter.HEADER_TENANT_ID, clientId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("partner_key_required"));
    }

    @Test
    @DisplayName("the membership cache is invalidated when a client is suspended")
    void suspensionInvalidatesTheCache() throws Exception {
        UUID partnerId = makePartner("leadboard");
        UUID clientId = addClient(partnerId, "cust_5", null);
        String key = issuePartnerKey(partnerId);

        // Warm the cache with a successful resolution.
        mockMvc.perform(get("/v1/campaigns")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .header(ApiKeyAuthFilter.HEADER_TENANT_ID, clientId.toString()))
                .andExpect(status().isServiceUnavailable());   // upstream is not running in this test
        assertThat(redis.hasKey("partner:" + partnerId + ":tenants")).isTrue();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/v1/customers/" + clientId)
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());

        // Without the eager invalidation this would still be a cache hit for the next 60 seconds, and a
        // suspended customer would keep sending for a minute after the partner stopped it.
        assertThat(redis.hasKey("partner:" + partnerId + ":tenants")).isFalse();

        mockMvc.perform(get("/v1/campaigns")
                        .header(ApiKeyAuthFilter.HEADER, key)
                        .header(ApiKeyAuthFilter.HEADER_TENANT_ID, clientId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("customer_suspended"));
    }

    // ------------------------------------------------------------------ fixtures

    private UUID otherTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, company_name, contact_email, status) "
                        + "VALUES (?, ?, ?, 'ACTIVE'::tenant_status)",
                id, name, name.replace(' ', '.').toLowerCase() + "@example.test");
        return id;
    }

    private UUID makePartnerFor(UUID ownerTenantId, String slug) {
        UUID partnerId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO api_partners (id, name, slug, status, owner_tenant_id, webhook_scope)
                        VALUES (?, ?, ?, 'ACTIVE', ?, 'PARTNER')
                        """,
                partnerId, slug, slug, ownerTenantId);
        return partnerId;
    }
}
