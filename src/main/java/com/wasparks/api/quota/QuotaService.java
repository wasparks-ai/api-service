package com.wasparks.api.quota;

import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.enums.OveragePolicy;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Message quotas, per tenant, per day and per month (epic §B3).
 *
 * <p>Quotas are counted <b>per tenant</b> while rate limits are counted per key, and the difference is
 * not arbitrary: a request is a load concern and belongs to the integration making it, whereas a message
 * is a billing concern and belongs to the customer. A tenant issuing five keys gets five times the
 * request headroom and exactly the same number of messages.
 *
 * <p>Reservation is a single Lua script (see {@code RedisConfig}), because check-then-increment lets two
 * concurrent sends both take the last slot. The day and month counters are reserved together and, if the
 * month refuses after the day succeeded, the day is given back — a message that was never accepted must
 * not consume anything.
 *
 * <h2>BLOCK vs WARN</h2>
 * {@code BLOCK} refuses the send with {@code 429 quota_exceeded} and {@code X-Quota-Scope}. {@code WARN}
 * accepts it, marks the response with {@code X-Quota-Warning: exceeded}, and lets a
 * {@code quota.exceeded} webhook fire once a day. WARN still counts: a customer on an overage plan needs
 * the number at the end of the month more than a customer who is being blocked does.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaService {

    public static final String HEADER_QUOTA_SCOPE = "X-Quota-Scope";
    public static final String HEADER_QUOTA_WARNING = "X-Quota-Warning";

    private static final String PREFIX = "q:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /** Two days, so a counter is still inspectable just after its day rolls over. */
    private static final long DAY_TTL_SECONDS = 2 * 24 * 3600L;
    /** 40 days covers the longest month plus slack. */
    private static final long MONTH_TTL_SECONDS = 40 * 24 * 3600L;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> reserveQuotaScript;

    /**
     * Reserve one message against the tenant's day and month quotas.
     *
     * @return a {@link Decision} describing what the caller must do; never null
     */
    public Decision reserve(ApiPrincipal principal) {
        UUID tenantId = principal.tenantId();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String dayKey = dayKey(tenantId, today);
        String monthKey = monthKey(tenantId, today);

        boolean warn = principal.limits().overagePolicy() == OveragePolicy.WARN;
        int dayLimit = principal.limits().messagesPerDay();
        int monthLimit = principal.limits().messagesPerMonth();

        // Under WARN the counters must still advance past the limit, so the script is given an
        // unbounded limit (-1) and the overage is judged from the returned count instead.
        long dayCount = reserve(dayKey, warn ? -1 : dayLimit, DAY_TTL_SECONDS);
        if (dayCount < 0) {
            return Decision.blocked("day", dayLimit);
        }

        long monthCount = reserve(monthKey, warn ? -1 : monthLimit, MONTH_TTL_SECONDS);
        if (monthCount < 0) {
            // The month refused after the day was taken — hand the day back, or a blocked send would
            // silently eat a slot from the daily allowance.
            decrement(dayKey);
            return Decision.blocked("month", monthLimit);
        }

        if (warn) {
            if (dayCount > dayLimit) {
                return Decision.warned("day", dayLimit);
            }
            if (monthCount > monthLimit) {
                return Decision.warned("month", monthLimit);
            }
        }
        return Decision.pass();
    }

    /**
     * Give a reserved message back.
     *
     * <p>Called by the send worker when the upstream send is refused synchronously (epic §B3): the
     * message was accepted optimistically, tenants-service then said no, and nothing was sent — so the
     * customer must not be charged for it. Not called on a Meta-side failure, because that message did
     * leave the building.
     */
    public void release(UUID tenantId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        decrement(dayKey(tenantId, today));
        decrement(monthKey(tenantId, today));
    }

    /** Current usage, for {@code GET /v1/account}. */
    public Usage peek(UUID tenantId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return new Usage(read(dayKey(tenantId, today)), read(monthKey(tenantId, today)));
    }

    private long reserve(String key, int limit, long ttlSeconds) {
        try {
            Long result = redis.execute(reserveQuotaScript, List.of(key),
                    String.valueOf(limit), String.valueOf(ttlSeconds));
            return result == null ? 1L : result;
        } catch (Exception e) {
            // Fail open, as with rate limiting: a quota counter that is unreachable must not stop a
            // customer sending. The nightly reconciliation from `messages` is the backstop.
            log.warn("Quota counter unavailable, allowing send: {}", e.getMessage());
            return 1L;
        }
    }

    private void decrement(String key) {
        try {
            redis.opsForValue().decrement(key);
        } catch (Exception e) {
            log.warn("Could not release quota on {}: {}", key, e.getMessage());
        }
    }

    private long read(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    static String dayKey(UUID tenantId, LocalDate day) {
        return PREFIX + tenantId + ":d:" + day.format(DAY);
    }

    static String monthKey(UUID tenantId, LocalDate day) {
        return PREFIX + tenantId + ":m:" + day.format(MONTH);
    }

    /** What the caller must do about the quota. */
    public record Decision(boolean allowed, boolean warning, String scope, int limit) {

        static Decision pass() {
            return new Decision(true, false, null, 0);
        }

        static Decision warned(String scope, int limit) {
            return new Decision(true, true, scope, limit);
        }

        static Decision blocked(String scope, int limit) {
            return new Decision(false, false, scope, limit);
        }

        /** The rejection to throw when {@link #allowed()} is false. */
        public ApiException toException() {
            return ApiException.of(ApiErrorCode.QUOTA_EXCEEDED,
                            "The " + scope + " message quota of " + limit + " has been used up.")
                    .withHeader(HEADER_QUOTA_SCOPE, scope)
                    .withDetail("scope", scope)
                    .withDetail("limit", limit);
        }
    }

    /** Messages counted so far today and this month. */
    public record Usage(long today, long month) {
    }
}
