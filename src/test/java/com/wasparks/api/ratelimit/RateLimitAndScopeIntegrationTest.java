package com.wasparks.api.ratelimit;

import com.wasparks.api.BaseIntegrationTest;
import com.wasparks.api.enums.Scope;
import com.wasparks.api.internal.InternalDtos;
import com.wasparks.api.internal.InternalTenantsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The fixed-window rate limiter and the scope gate (epic §B3, §B2).
 */
class RateLimitAndScopeIntegrationTest extends BaseIntegrationTest {

    @MockBean
    private InternalTenantsClient tenantsClient;

    private void stubAccount() {
        Mockito.when(tenantsClient.getAccount(Mockito.any())).thenReturn(
                new InternalDtos.AccountResponse(tenantId.toString(), "Test Co", "OK", List.of()));
    }

    @Test
    @DisplayName("every response carries the three rate-limit headers")
    void headersOnSuccess() throws Exception {
        stubAccount();
        String key = issueLiveKey();

        MvcResult result = mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andReturn();

        // FREE is the seeded default plan: 60 requests a minute.
        assertEquals("60", result.getResponse().getHeader("X-RateLimit-Limit"));
        assertEquals("59", result.getResponse().getHeader("X-RateLimit-Remaining"));
    }

    @Test
    @DisplayName("the headers are present on an error response too")
    void headersOnError() throws Exception {
        // The epic requires this explicitly, and it is the case that matters most: a client being
        // refused needs to know how much of its allowance remains before it retries.
        String key = issueKeyWithScopes(Scope.MESSAGES_SEND);

        mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"));
    }

    @Test
    @DisplayName("the request past the limit is 429 with Retry-After")
    void overLimit() throws Exception {
        stubAccount();
        createPlan("TINY", 3, 1000, 10000, "BLOCK", 5);
        assignPlan("TINY", null);
        String key = issueLiveKey();

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(3 - i)));
        }

        MvcResult refused = mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-WaSparks-Error", "rate_limited"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.error.code").value("rate_limited"))
                .andReturn();

        String retryAfter = refused.getResponse().getHeader("Retry-After");
        assertNotNull(retryAfter, "a 429 must say when to come back");
        int seconds = Integer.parseInt(retryAfter);
        assertTrue(seconds >= 1 && seconds <= 60, "Retry-After should point at the window edge: " + seconds);
    }

    @Test
    @DisplayName("the limit is per key, so a second key has its own allowance")
    void limitIsPerKey() throws Exception {
        // Per key, not per tenant: a tenant should be able to isolate a noisy integration on its own
        // key without its other integrations being starved by it.
        stubAccount();
        createPlan("TINY2", 2, 1000, 10000, "BLOCK", 5);
        assignPlan("TINY2", null);

        String first = issueLiveKey();
        String second = issueLiveKey();

        mockMvc.perform(get("/v1/account").header("X-API-Key", first)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/account").header("X-API-Key", first)).andExpect(status().isOk());
        mockMvc.perform(get("/v1/account").header("X-API-Key", first))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/v1/account").header("X-API-Key", second))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "1"));
    }

    @Test
    @DisplayName("a per-tenant override beats the plan's limit")
    void overridesApply() throws Exception {
        stubAccount();
        assignPlan("FREE", "{\"requestsPerMinute\": 7}");
        String key = issueLiveKey();

        mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(header().string("X-RateLimit-Limit", "7"));
    }

    @Test
    @DisplayName("an unparseable override falls back to the plan value")
    void unparseableOverrideIsIgnored() throws Exception {
        // A typo in a hand-edited JSON blob in admin-webapp must not hand a tenant zero requests.
        stubAccount();
        assignPlan("FREE", "{\"requestsPerMinute\": \"not-a-number\"}");
        String key = issueLiveKey();

        mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "60"));
    }

    @Test
    @DisplayName("a key without the scope is 403 insufficient_scope")
    void missingScope() throws Exception {
        String key = issueKeyWithScopes(Scope.MESSAGES_SEND);

        mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-WaSparks-Error", "insufficient_scope"))
                .andExpect(jsonPath("$.error.code").value("insufficient_scope"))
                .andExpect(jsonPath("$.error.details.requiredScope").value("account:read"));
    }

    @Test
    @DisplayName("a key with the scope passes")
    void withScope() throws Exception {
        stubAccount();
        String key = issueKeyWithScopes(Scope.ACCOUNT_READ);

        mockMvc.perform(get("/v1/account").header("X-API-Key", key))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the health endpoint needs no key and is not rate limited")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-RateLimit-Limit"));
    }
}
