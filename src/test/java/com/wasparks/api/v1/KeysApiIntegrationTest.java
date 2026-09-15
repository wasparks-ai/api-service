package com.wasparks.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.enums.ApiKeyStatus;
import com.wasparks.api.util.Hashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Key management on the JWT chain (epic §0.12, §B7).
 *
 * <p>The bootstrap property is the one to hold on to: a tenant with no key must be able to create their
 * first one with the session they already have. Everything else on this service refuses a JWT.
 */
class KeysApiIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("an owner creates a key and sees the plaintext exactly once")
    void createReturnsKeyOnce() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Orders integration\",\"mode\":\"LIVE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").exists())
                .andExpect(jsonPath("$.mode").value("LIVE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // Every scope, whatever the vocabulary currently is — it grew with the partner
                // platform (customers, campaigns, media), and pinning a number here would mean this
                // test failing every time a scope is added rather than when the default changes.
                .andExpect(jsonPath("$.scopes.length()")
                        .value(com.wasparks.api.enums.Scope.values().length))
                .andReturn();

        String plaintext = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("key").asText();
        assertTrue(plaintext.startsWith("wsk_live_"));
        assertEquals(9 + 32, plaintext.length(), "prefix plus 32 base62 characters");

        // Never again, on any subsequent read.
        mockMvc.perform(get("/v1/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].key").doesNotExist())
                .andExpect(jsonPath("$.data[0].prefix").value(plaintext.substring(0, 16)));

        assertTrue(apiKeyRepository.findByKeyHash(Hashing.sha256Hex(plaintext)).isPresent());
    }

    @Test
    @DisplayName("a TEST key is minted with the sandbox prefix")
    void testModeKey() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sandbox\",\"mode\":\"TEST\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("TEST"))
                .andReturn();

        assertTrue(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("key").asText().startsWith("wsk_test_"));
    }

    @Test
    @DisplayName("scopes can be narrowed at creation")
    void narrowedScopes() throws Exception {
        mockMvc.perform(post("/v1/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Send only\",\"scopes\":[\"messages:send\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopes.length()").value(1))
                .andExpect(jsonPath("$.scopes[0]").value("messages:send"));
    }

    @Test
    @DisplayName("an unknown scope is refused with the allowed list")
    void unknownScope() throws Exception {
        mockMvc.perform(post("/v1/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"scopes\":[\"messages:destroy\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.details.allowed").isArray());
    }

    @Test
    @DisplayName("the plan's max_keys is enforced, counting only active keys")
    void maxKeys() throws Exception {
        createPlan("TWO_KEYS", 1000, 1000, 10000, "BLOCK", 2);
        assignPlan("TWO_KEYS", null);
        String jwt = tenantJwt("TENANT_OWNER");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/v1/keys").header("Authorization", "Bearer " + jwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"key" + i + "\"}"))
                    .andExpect(status().isCreated());
        }

        MvcResult refused = mockMvc.perform(post("/v1/keys").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"one too many\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("plan_limit_reached"))
                .andExpect(jsonPath("$.error.details.limit").value(2))
                .andReturn();
        assertNotNull(refused.getResponse().getContentAsString());

        // Revoking frees a slot: a revoked key is history, not a consumed allowance.
        UUID revokable = apiKeyRepository.findByTenantIdAndUiSessionFalseOrderByCreatedAtDesc(tenantId).get(0).getId();
        mockMvc.perform(delete("/v1/keys/" + revokable).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/v1/keys").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"replacement\"}"))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ UI session key (§D.3)

    @Test
    @DisplayName("uiSession returns a 1h TEST key with exactly two scopes")
    void uiSessionKeyIsNarrowAndShortLived() throws Exception {
        // tenant-web reads webhooks and account with this and holds it in memory only, so the blast
        // radius of it leaking has to be near zero: no send scope, TEST mode, one hour.
        MvcResult result = mockMvc.perform(post("/v1/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uiSession\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").exists())
                .andExpect(jsonPath("$.mode").value("TEST"))
                .andExpect(jsonPath("$.name").value("UI session"))
                .andExpect(jsonPath("$.uiSession").value(true))
                .andExpect(jsonPath("$.scopes.length()").value(2))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Set<String> scopes = new HashSet<>();
        body.get("scopes").forEach(node -> scopes.add(node.asText()));
        assertEquals(Set.of("webhooks:manage", "account:read"), scopes);

        Instant expiresAt = Instant.parse(body.get("expiresAt").asText());
        long minutes = Duration.between(Instant.now(), expiresAt).toMinutes();
        assertTrue(minutes > 50 && minutes <= 60, "about an hour, was " + minutes + " minutes");

        // It authenticates like any other key — the point of making it a real row rather than a special
        // case — and the two scopes are the whole of what it can reach.
        String key = body.get("key").asText();
        mockMvc.perform(get("/v1/webhooks").header("X-API-Key", key))
                .andExpect(status().isOk());
        mockMvc.perform(post("/meta/whatsapp/v19.0/" + phoneNumberId + "/messages")
                        .header("X-API-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messaging_product\":\"whatsapp\",\"to\":\"+919876543210\","
                                + "\"type\":\"text\",\"text\":{\"body\":\"nope\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(0));
    }

    @Test
    @DisplayName("a UI-session key is hidden from the list and does not consume the plan allowance")
    void uiSessionKeyIsInvisibleAndFree() throws Exception {
        createPlan("ONE_KEY", 1000, 1000, 10000, "BLOCK", 1);
        assignPlan("ONE_KEY", null);
        String jwt = tenantJwt("TENANT_OWNER");

        // Three dashboard opens must not exhaust a one-key plan.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/v1/keys").header("Authorization", "Bearer " + jwt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"uiSession\":true}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/v1/keys").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"the tenant's own key\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/keys").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("the tenant's own key"));
    }

    @Test
    @DisplayName("name is required unless uiSession is set")
    void nameIsRequiredForAnOrdinaryKey() throws Exception {
        mockMvc.perform(post("/v1/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"LIVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"));
    }

    @Test
    @DisplayName("the daily purge deletes expired UI-session keys and nothing else")
    void purgeTakesOnlyExpiredSessionKeys() throws Exception {
        String jwt = tenantJwt("TENANT_OWNER");
        mockMvc.perform(post("/v1/keys").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uiSession\":true}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/keys").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a real key\"}"))
                .andExpect(status().isCreated());

        // Nothing to purge while it is still live — the job must not touch a session someone is using.
        assertEquals(0, apiKeyService.purgeExpiredUiSessionKeys());
        assertEquals(2, apiKeyRepository.findAll().size());

        jdbcTemplate.update("UPDATE api_keys SET expires_at = now() - interval '1 hour' "
                + "WHERE ui_session = true");
        assertEquals(1, apiKeyService.purgeExpiredUiSessionKeys());

        assertEquals(1, apiKeyRepository.findAll().size());
        assertFalse(apiKeyRepository.findAll().get(0).isUiSession(), "the tenant's own key survives");
    }

    @Test
    @DisplayName("the list reports the plan cap so the UI can show 'n of m'")
    void listCarriesTheCap() throws Exception {
        mockMvc.perform(get("/v1/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.maxKeys").value(3));
    }

    @Test
    @DisplayName("revoking is permanent and idempotent")
    void revoke() throws Exception {
        String jwt = tenantJwt("TENANT_OWNER");
        MvcResult created = mockMvc.perform(post("/v1/keys").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"temp\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText());

        mockMvc.perform(delete("/v1/keys/" + id).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/v1/keys/" + id).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());

        assertEquals(ApiKeyStatus.REVOKED, apiKeyRepository.findById(id).orElseThrow().getStatus());
        assertNotNull(apiKeyRepository.findById(id).orElseThrow().getRevokedAt());
    }

    @Test
    @DisplayName("a MEMBER token is refused")
    void memberIsRefused() throws Exception {
        // Key management is an owner/admin capability (epic §D); a member holding a valid session must
        // not be able to mint credentials.
        mockMvc.perform(get("/v1/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_MEMBER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("forbidden"));
    }

    @Test
    @DisplayName("no token is refused")
    void noToken() throws Exception {
        mockMvc.perform(get("/v1/keys")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an API key is not accepted on the keys endpoint")
    void apiKeyIsNotACredentialHere() throws Exception {
        // Deliberate: a key must not be able to mint another key, or a leaked key becomes permanent
        // access no matter how quickly it is revoked.
        String key = issueLiveKey();
        mockMvc.perform(get("/v1/keys").header("X-API-Key", key))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/keys").header("Authorization", "Bearer " + key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token signed with the wrong secret is refused")
    void forgedToken() throws Exception {
        String forged = io.jsonwebtoken.Jwts.builder()
                .subject(tenantUserId.toString())
                .claim("tenantId", tenantId.toString())
                .claim("role", "TENANT_OWNER")
                .issuer("whatsapp-tenants-service")
                .expiration(new java.util.Date(System.currentTimeMillis() + 60000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "a-completely-different-secret-that-is-also-32-chars"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/v1/keys").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an expiry in the past is refused rather than creating a dead key")
    void pastExpiry() throws Exception {
        mockMvc.perform(post("/v1/keys")
                        .header("Authorization", "Bearer " + tenantJwt("TENANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"stillborn\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }
}
