package com.wasparks.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.plans.PlanResolver;
import com.wasparks.api.repository.ApiKeyRepository;
import com.wasparks.api.repository.TenantRefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Authentication must survive Redis being unreachable.
 *
 * <p>The key cache is an optimisation over {@code api_keys} in PostgreSQL, but {@code resolve} runs before
 * every other stage of the pipeline — so a cache timeout that propagates turns a Redis outage into a 500
 * on every endpoint, and the send path's own {@code 503 queue_unavailable} never gets to run in the one
 * outage it was written for. Found by the live-verify walk, which got a 500 where it expected the 503.
 */
class ApiKeyRedisOutageTest {

    private ApiKeyRepository apiKeyRepository;
    private ApiKeyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(values);
        Mockito.when(values.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        Mockito.doThrow(new RedisConnectionFailureException("connection refused"))
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));

        apiKeyRepository = Mockito.mock(ApiKeyRepository.class);
        service = new ApiKeyService(apiKeyRepository, Mockito.mock(TenantRefRepository.class),
                Mockito.mock(PlanResolver.class), redis, new ObjectMapper());
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", 300L);
        ReflectionTestUtils.setField(service, "lastUsedThrottleSeconds", 60L);
    }

    @Test
    @DisplayName("an unreachable cache falls through to the database, not out as a 500")
    void resolvesFromTheDatabase() {
        Mockito.when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());

        // The unknown key is the observable half: what matters is that the caller gets the ordinary
        // 401 rather than the Redis timeout, which proves the database lookup ran.
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.resolve("wsk_test_whatever"));
        assertEquals(ApiErrorCode.INVALID_API_KEY, thrown.getCode());
        Mockito.verify(apiKeyRepository).findByKeyHash(anyString());
    }

    @Test
    @DisplayName("the last_used_at touch is skipped rather than thrown when the throttle is gone")
    void touchIsBestEffort() {
        service.touchLastUsed(UUID.randomUUID());
        Mockito.verify(apiKeyRepository, Mockito.never())
                .touchLastUsedAt(any(), any());
    }
}
