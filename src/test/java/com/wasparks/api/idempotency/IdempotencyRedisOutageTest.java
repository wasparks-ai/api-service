package com.wasparks.api.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.enums.ApiKeyMode;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The Redis-failure ruling for the send path: the limiters fail open, idempotency does not.
 *
 * <p>A unit test rather than an integration one because the case under test is Redis being unreachable,
 * and the honest way to produce that is a store that throws — pausing the shared container would break
 * every other test in the suite for as long as it stayed down.
 */
class IdempotencyRedisOutageTest {

    private static final ApiPrincipal PRINCIPAL = new ApiPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), null, ApiKeyMode.LIVE,
            Set.of("messages:send"), null, null);

    private IdempotencyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(values);
        Mockito.when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        service = new IdempotencyService(redis, new ObjectMapper());
        ReflectionTestUtils.setField(service, "ttlHours", 24L);
    }

    @Test
    @DisplayName("an unreachable store refuses the send with 503 queue_unavailable and Retry-After: 5")
    void failsClosed() {
        // The alternative — accepting the send without replay protection — turns a client's retry after
        // a dropped connection into a second WhatsApp message in front of a real customer. Redis being
        // down also means the job could not have been enqueued, so nothing is lost by refusing now.
        ApiException thrown = assertThrows(ApiException.class, () ->
                service.beginOrReplay(PRINCIPAL, "order-4711",
                        "{\"to\":\"+919876543210\"}".getBytes(StandardCharsets.UTF_8),
                        reservation -> { }));

        assertEquals(ApiErrorCode.QUEUE_UNAVAILABLE, thrown.getCode());
        assertEquals(503, thrown.getCode().getStatus().value());
        assertEquals("5", thrown.getHeaders().get(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("no reservation reaches the handler when the store is unreachable")
    void reservesNothing() {
        // A reservation handed over would imply there is a record for the controller to complete, and
        // the controller would then try to store a response for a send that was never accepted.
        boolean[] reserved = {false};
        assertThrows(ApiException.class, () ->
                service.beginOrReplay(PRINCIPAL, "order-4711", new byte[0],
                        reservation -> reserved[0] = true));
        assertFalse(reserved[0]);
    }
}
