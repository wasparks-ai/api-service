package com.wasparks.api.quota;

import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.PartnerPrincipal;
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
import java.util.ArrayList;
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
 * <h2>Partner pooling (api-partner epic §0.9, §B1)</h2>
 * A partner key is checked against <b>two</b> quotas, and must satisfy both: the partner's own
 * {@code messages_per_day_cap} for the client it is acting for, and the partner plan's pool, which every
 * client of that partner shares. The effective allowance is therefore {@code min(client cap, pool
 * remaining)} without either being computed — taking them in order and rolling back gives the same
 * answer and cannot drift from the counters it is derived from.
 *
 * <p>The client cap is checked <b>first</b> because it is the most specific: a partner that capped one
 * customer at 500 wants to hear that that customer is at its ceiling, not that the pool is fine.
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

    /** Reserve one message — the send path. */
    public Decision reserve(ApiPrincipal principal) {
        return reserve(principal, 1);
    }

    /**
     * Reserve {@code amount} messages against every quota that applies to this principal.
     *
     * <p>{@code amount} is 1 on a send and the campaign's sendable recipient count at campaign create
     * (§B3). Reserving a campaign in one move is what makes the limit mean anything: a thousand
     * reservations of one would let a campaign take the last of the day's allowance halfway through and
     * leave the tenant with a half-funded campaign and no quota for anything else.
     *
     * @return a {@link Decision} describing what the caller must do; never null
     */
    public Decision reserve(ApiPrincipal principal, int amount) {
        if (amount <= 0) {
            // A campaign whose recipients were all suppressed reserves nothing and is not refused — it
            // simply sends to nobody, which the counts already say plainly.
            return Decision.pass();
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        boolean warn = principal.limits().overagePolicy() == OveragePolicy.WARN;
        List<Reservation> taken = new ArrayList<>(3);

        // The client's own ceiling first, because it is the cheapest to refuse and the most specific: a
        // partner that set a cap of 500 on one customer wants that customer stopped, not the pool.
        Integer cap = principal.isPartner() ? principal.partner().clientDailyCap() : null;
        if (cap != null) {
            Decision client = take(taken, dayKey(principal.tenantId(), today), cap,
                    DAY_TTL_SECONDS, amount, "client");
            if (client != null) {
                return client;
            }
        }

        // Under WARN the counters must still advance past the limit, so the script is given an unbounded
        // limit (-1) and the overage is judged from the returned count instead. A client cap is never
        // WARN: it is the partner's own decision about its own customer, not a billing policy.
        UUID poolOwner = principal.isPartner() ? principal.partner().partnerId() : principal.tenantId();
        boolean pooled = principal.isPartner();

        int dayLimit = principal.limits().messagesPerDay();
        Decision day = take(taken, pooled ? partnerDayKey(poolOwner, today) : dayKey(poolOwner, today),
                warn ? -1 : dayLimit, DAY_TTL_SECONDS, amount, "day");
        if (day != null) {
            return day;
        }

        int monthLimit = principal.limits().messagesPerMonth();
        Decision month = take(taken,
                pooled ? partnerMonthKey(poolOwner, today) : monthKey(poolOwner, today),
                warn ? -1 : monthLimit, MONTH_TTL_SECONDS, amount, "month");
        if (month != null) {
            return month;
        }

        if (warn) {
            for (Reservation reservation : taken) {
                int limit = "day".equals(reservation.scope()) ? dayLimit : monthLimit;
                if (!"client".equals(reservation.scope()) && reservation.count() > limit) {
                    return Decision.warned(reservation.scope(), limit);
                }
            }
        }
        return Decision.pass();
    }

    /**
     * Take one counter, rolling back everything already taken if it refuses.
     *
     * <p>Returns the blocking {@link Decision} on refusal and {@code null} on success, which reads
     * oddly in isolation and reads well at the call site: three sequential reservations that must all
     * succeed, each able to abort the whole set. Without the rollback, a send refused by the month would
     * have silently eaten a slot from the day — and, for a partner, a client cap slot from a customer
     * that never received anything.
     */
    private Decision take(List<Reservation> taken, String key, int limit, long ttlSeconds, int amount,
                          String scope) {
        long count = reserve(key, limit, ttlSeconds, amount);
        if (count < 0) {
            rollback(taken, amount);
            return Decision.blocked(scope, limit);
        }
        taken.add(new Reservation(key, scope, count));
        return null;
    }

    private void rollback(List<Reservation> taken, int amount) {
        for (Reservation reservation : taken) {
            decrement(reservation.key(), amount);
        }
        taken.clear();
    }

    /**
     * Give reserved messages back.
     *
     * <p>Called by the send worker when the upstream send is refused synchronously (epic §B3) and by the
     * campaign path when the create is rejected after the reservation: the messages were accepted
     * optimistically, upstream then said no, and nothing was sent — so the customer must not be charged.
     * Not called on a Meta-side failure, because that message did leave the building.
     */
    public void release(ApiPrincipal principal, int amount) {
        release(principal.tenantId(), principal.partner(), amount);
    }

    /**
     * The same release, addressed by ids rather than by a principal.
     *
     * <p>{@code SendWorker} needs this: it runs off a Redis stream with no request and no security
     * context, so the partner context travels on the {@link com.wasparks.api.queue.SendJob} and is handed
     * back here. Releasing the wrong counters would be silent — a partner slowly losing pool allowance
     * to sends that never happened — so the worker carries the context rather than guessing at it.
     */
    public void release(UUID tenantId, PartnerPrincipal partner, int amount) {
        if (amount <= 0) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (partner != null) {
            decrement(partnerDayKey(partner.partnerId(), today), amount);
            decrement(partnerMonthKey(partner.partnerId(), today), amount);
            if (partner.clientDailyCap() != null) {
                decrement(dayKey(tenantId, today), amount);
            }
            return;
        }
        decrement(dayKey(tenantId, today), amount);
        decrement(monthKey(tenantId, today), amount);
    }

    /** Current usage for a tenant, for {@code GET /v1/account} and the console's per-client bars. */
    public Usage peek(UUID tenantId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return new Usage(read(dayKey(tenantId, today)), read(monthKey(tenantId, today)));
    }

    /** Current pool usage for a partner, for the console's pool meters (§B6). */
    public Usage peekPool(UUID partnerId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return new Usage(read(partnerDayKey(partnerId, today)),
                read(partnerMonthKey(partnerId, today)));
    }

    private long reserve(String key, int limit, long ttlSeconds, int amount) {
        try {
            Long result = redis.execute(reserveQuotaScript, List.of(key),
                    String.valueOf(limit), String.valueOf(ttlSeconds), String.valueOf(amount));
            return result == null ? amount : result;
        } catch (Exception e) {
            // Fail open, as with rate limiting: a quota counter that is unreachable must not stop a
            // customer sending. The nightly reconciliation from `messages` is the backstop.
            log.warn("Quota counter unavailable, allowing send: {}", e.getMessage());
            return amount;
        }
    }

    private void decrement(String key, int amount) {
        try {
            redis.opsForValue().decrement(key, amount);
        } catch (Exception e) {
            log.warn("Could not release quota on {}: {}", key, e.getMessage());
        }
    }

    /** One counter successfully taken, kept so it can be handed back if a later one refuses. */
    private record Reservation(String key, String scope, long count) {
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

    /**
     * The partner pool counters, {@code q:p:{partnerId}:d|m:…} (§B1) — a namespace beside the P1 tenant
     * keys, not instead of them. A partner's allowance is one number shared by every client, so it cannot
     * live under any client's tenant id; and the P1 keys keep their exact meaning, which is what lets a
     * client key issued for one customer go on behaving exactly as a P1 key.
     */
    static String partnerDayKey(UUID partnerId, LocalDate day) {
        return PREFIX + "p:" + partnerId + ":d:" + day.format(DAY);
    }

    static String partnerMonthKey(UUID partnerId, LocalDate day) {
        return PREFIX + "p:" + partnerId + ":m:" + day.format(MONTH);
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
