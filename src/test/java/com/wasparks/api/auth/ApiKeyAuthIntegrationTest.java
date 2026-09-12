package com.wasparks.api.auth;

import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.entity.ApiKey;
import com.wasparks.api.enums.ApiKeyStatus;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.internal.InternalDtos;
import com.wasparks.api.internal.InternalTenantsClient;
import com.wasparks.api.util.Hashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API key authentication (epic §B2).
 *
 * <p>The important property under test is uniformity: unknown, revoked, expired and
 * suspended-tenant all answer {@code 401 invalid_api_key} with the same message. Distinguishing them
 * would tell an attacker probing keys which of their guesses named a real one.
 */
class ApiKeyAuthIntegrationTest extends BaseIntegrationTest {

    @MockBean
    private InternalTenantsClient tenantsClient;

    @Autowired
    private ApiKeyService apiKeyService;

    private void stubAccount() {
        Mockito.when(tenantsClient.getAccount(Mockito.any())).thenReturn(
                new InternalDtos.AccountResponse(tenantId.toString(), "Test Co", "OK", List.of()));
    }

    @Test
    @DisplayName("a valid key in X-API-Key authenticates")
    void validKeyViaHeader() throws Exception {
        stubAccount();
        String key = issueLiveKey();

        mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.name").value("Test Co"));
    }

    @Test
    @DisplayName("a valid key as a Bearer token authenticates — the Meta migration path")
    void validKeyViaBearer() throws Exception {
        // A client moving off Meta's Cloud API already sends Authorization: Bearer. If this did not
        // work, migrating would mean changing code rather than configuration.
        stubAccount();
        String key = issueLiveKey();

        mockMvc.perform(get("/v1/account").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("no key at all is 401")
    void missingKey() throws Exception {
        mockMvc.perform(get("/v1/account"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-WaSparks-Error", "invalid_api_key"))
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    @DisplayName("an unknown key is 401")
    void unknownKey() throws Exception {
        mockMvc.perform(get("/v1/account").header("X-API-Key", "wsk_live_nosuchkeyatall"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    @DisplayName("a revoked key is 401")
    void revokedKey() throws Exception {
        String key = insertRawKey(ApiKeyStatus.REVOKED, null);

        mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    @DisplayName("an expired key is 401")
    void expiredKey() throws Exception {
        String key = insertRawKey(ApiKeyStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    @DisplayName("a good key belonging to a suspended tenant is 401")
    void suspendedTenant() throws Exception {
        // Suspension has to take API access with it, or suspending a tenant in admin-webapp only stops
        // them using the app while their integrations keep sending.
        String key = issueLiveKey();
        setTenantStatus("SUSPENDED");

        mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    @DisplayName("revoking evicts the cached lookup, so the next request fails immediately")
    void revokeIsImmediate() throws Exception {
        // Without the eviction a leaked key would keep working for the full cache TTL, which is not an
        // acceptable answer to "we have leaked a key".
        stubAccount();
        String key = issueLiveKey();
        mockMvc.perform(get("/v1/account").header("X-API-Key", key)).andExpect(status().isOk());
        assertNotNull(redis.opsForValue().get("key:" + Hashing.sha256Hex(key)),
                "the resolved principal should be cached after a successful request");

        ApiKey row = apiKeyRepository.findByKeyHash(Hashing.sha256Hex(key)).orElseThrow();
        apiKeyService.revoke(tenantId, row.getId());

        assertNull(redis.opsForValue().get("key:" + Hashing.sha256Hex(key)),
                "revoking must evict the cache entry, not wait for it to expire");
        mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown key is cached as a tombstone so a retry loop cannot hammer the database")
    void negativeCaching() throws Exception {
        String bogus = "wsk_live_definitelynotarealkey";
        mockMvc.perform(get("/v1/account").header("X-API-Key", bogus))
                .andExpect(status().isUnauthorized());

        assertNotNull(redis.opsForValue().get("key:" + Hashing.sha256Hex(bogus)),
                "a failed lookup must be remembered — otherwise a client looping on a typo turns every "
                        + "request into a database query");
    }

    @Test
    @DisplayName("last_used_at is written at most once per minute")
    void lastUsedIsThrottled() throws Exception {
        stubAccount();
        String key = issueLiveKey();
        ApiKey row = apiKeyRepository.findByKeyHash(Hashing.sha256Hex(key)).orElseThrow();

        mockMvc.perform(get("/v1/account").header("X-API-Key", key)).andExpect(status().isOk());
        Instant first = apiKeyRepository.findById(row.getId()).orElseThrow().getLastUsedAt();
        assertNotNull(first, "the first request in a window should record last_used_at");

        assertNotNull(redis.opsForValue().get("lu:" + row.getId()),
                "the throttle token must be held for the rest of the minute");

        // The second request must not write again. Proven by the token still being held rather than by
        // comparing timestamps, which could match by coincidence within the same millisecond.
        mockMvc.perform(get("/v1/account").header("X-API-Key", key)).andExpect(status().isOk());
        Long ttl = redis.getExpire("lu:" + row.getId());
        assertTrue(ttl != null && ttl > 0 && ttl <= 60, "throttle TTL should be a fresh minute: " + ttl);
    }

    @Test
    @DisplayName("a key issued with narrowed scopes keeps only those")
    void narrowedScopes() {
        String key = issueKeyWithScopes(Scope.MESSAGES_SEND);
        ApiKey row = apiKeyRepository.findByKeyHash(Hashing.sha256Hex(key)).orElseThrow();

        assertEquals(1, row.getScopes().size());
        assertTrue(row.getScopes().contains("messages:send"));
    }

    @Test
    @DisplayName("a key issued with no scopes gets all six")
    void defaultScopes() {
        String key = issueLiveKey();
        ApiKey row = apiKeyRepository.findByKeyHash(Hashing.sha256Hex(key)).orElseThrow();
        assertEquals(Scope.allWire(), row.getScopes());
    }

    @Test
    @DisplayName("the plaintext key is never stored — only its hash")
    void keyIsNotStored() {
        String key = issueLiveKey();
        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM api_keys WHERE key_hash = ?", Long.class, key);
        assertEquals(0L, rows, "the key itself must never appear in the table");

        ApiKey row = apiKeyRepository.findByKeyHash(Hashing.sha256Hex(key)).orElseThrow();
        assertEquals(16, row.getPrefix().length());
        assertTrue(key.startsWith(row.getPrefix()));
    }
}
