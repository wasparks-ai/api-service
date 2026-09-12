package com.wasparks.api.ratelimit;

import com.wasparks.api.auth.ApiPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Fixed one-minute window per API key (epic §B3, the Kapso model).
 *
 * <p>A fixed window, not a sliding one, and that is a promise to the caller rather than an
 * implementation shortcut: {@code X-RateLimit-Reset} names the exact second the allowance returns, so a
 * client that is being limited can sleep precisely until then instead of backing off blindly. A sliding
 * window cannot make that promise.
 *
 * <p>The counter is keyed by epoch minute, so the window rolls over for free and the old key expires
 * itself — no sweep, no cleanup. The 120-second TTL is twice the window so a key is still readable for
 * diagnosis just after it stops counting.
 *
 * <p>Limiting is <b>per key</b>, not per tenant. A tenant can isolate a noisy integration on its own key
 * without its other integrations being starved by it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RESET = "X-RateLimit-Reset";
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    private static final String PREFIX = "rl:";
    private static final long WINDOW_SECONDS = 60;
    private static final long TTL_SECONDS = 120;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> incrementWithTtlScript;

    /**
     * Count this request against the key's window.
     *
     * <p>If Redis is unreachable the request is <b>allowed</b>, with the headers reporting a full
     * allowance. Failing open is the deliberate choice: a rate limiter that takes the API down when its
     * counter store blinks has converted a soft dependency into a hard one, and the damage from a brief
     * unlimited window is bounded by the message quotas — which are checked separately and are the limit
     * that actually costs money.
     */
    public Decision check(ApiPrincipal principal) {
        int limit = principal.limits().requestsPerMinute();
        long now = Instant.now().getEpochSecond();
        long windowStart = now - (now % WINDOW_SECONDS);
        long resetAt = windowStart + WINDOW_SECONDS;

        long count;
        try {
            String key = PREFIX + principal.keyId() + ":" + (windowStart / WINDOW_SECONDS);
            Long result = redis.execute(incrementWithTtlScript, List.of(key),
                    String.valueOf(TTL_SECONDS));
            count = result == null ? 1L : result;
        } catch (Exception e) {
            log.warn("Rate limit counter unavailable, allowing request: {}", e.getMessage());
            return new Decision(true, limit, limit, resetAt, 0);
        }

        long remaining = Math.max(0, limit - count);
        boolean allowed = count <= limit;
        long retryAfter = Math.max(1, resetAt - now);
        return new Decision(allowed, limit, remaining, resetAt, allowed ? 0 : retryAfter);
    }

    /**
     * @param allowed    whether the request may proceed
     * @param limit      the plan's requests per minute
     * @param remaining  requests left in this window, floored at zero
     * @param resetAt    epoch second the window rolls over
     * @param retryAfter seconds to wait, zero when allowed
     */
    public record Decision(boolean allowed, long limit, long remaining, long resetAt, long retryAfter) {
    }
}
