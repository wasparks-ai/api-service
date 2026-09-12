package com.wasparks.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis wiring. Everything this service keeps in Redis is a string or a hash of strings — cached
 * principals as JSON, counters, idempotency records — so a single {@link StringRedisTemplate} is the
 * whole surface. No JDK-serialization template: values written by one build of this service must stay
 * readable by the next, and Java serialization does not offer that.
 *
 * <p>The two Lua scripts exist because the operations they replace are not atomic when issued
 * separately, and the gap is not theoretical — it is exactly the window a crash lands in.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Fixed-window counter: INCR, and set the TTL on the way in.
     *
     * <p>A plain {@code INCR} followed by a separate {@code EXPIRE} has a real failure mode — the process
     * (or the connection) dies between the two and the key is left with no expiry. That key then counts
     * forever, and the tenant it belongs to is rate-limited permanently with no way to recover short of
     * someone noticing and deleting it by hand. Doing both inside one script removes the window
     * entirely.
     *
     * <p>The TTL is only set when the counter is new ({@code == 1}); re-setting it on every request would
     * slide the window forward and turn a fixed window into a rolling one, which is not the limit the
     * plan describes or the {@code X-RateLimit-Reset} header promises.
     *
     * <p>Returns the post-increment count.
     */
    @Bean
    public RedisScript<Long> incrementWithTtlScript() {
        String lua = """
                local current = redis.call('INCR', KEYS[1])
                if current == 1 then
                  redis.call('EXPIRE', KEYS[1], ARGV[1])
                end
                return current
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }

    /**
     * Quota reservation: increment only while the result would stay within the limit.
     *
     * <p>Check-then-increment from the application lets two concurrent sends both read "one left" and
     * both take it. Under a BLOCK plan that is a customer being charged for a message past their quota;
     * under any plan it is a limit that does not actually hold. The script increments first and rolls
     * back with a DECR when it overshot, so the decision and the mutation cannot be separated.
     *
     * <p>{@code KEYS[1]} counter, {@code ARGV[1]} limit, {@code ARGV[2]} TTL seconds. Returns the new
     * count when the reservation succeeded, or {@code -1} when it was refused — the caller needs to tell
     * "you are at 5 of 5" from "refused", and a bare boolean cannot carry the count for the header.
     */
    @Bean
    public RedisScript<Long> reserveQuotaScript() {
        String lua = """
                local limit = tonumber(ARGV[1])
                local ttl = tonumber(ARGV[2])
                local current = redis.call('INCR', KEYS[1])
                if current == 1 then
                  redis.call('EXPIRE', KEYS[1], ttl)
                end
                if limit >= 0 and current > limit then
                  redis.call('DECR', KEYS[1])
                  return -1
                end
                return current
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }
}
