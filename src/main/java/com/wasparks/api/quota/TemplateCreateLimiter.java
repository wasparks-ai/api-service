package com.wasparks.api.quota;

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
 * The plan's {@code template_creates_per_day} cap (epic §A).
 *
 * <p>Separate from {@link QuotaService} because it guards something different. A message quota is about
 * what the customer is paying for; this one is about the customer's standing with Meta. Meta throttles
 * and penalises businesses that create templates in bulk, and a runaway script doing so would damage the
 * tenant's own WABA in a way they cannot undo. Refusing locally is the friendlier failure.
 *
 * <p>Uses the same reservation script as the message quota, so the count-and-decide is atomic and two
 * concurrent creates cannot both take the last slot.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateCreateLimiter {

    private static final String PREFIX = "tc:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long TTL_SECONDS = 2 * 24 * 3600L;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> reserveQuotaScript;

    public void requireAllowance(UUID tenantId, int perDay) {
        String key = PREFIX + tenantId + ":" + LocalDate.now(ZoneOffset.UTC).format(DAY);
        long count;
        try {
            Long result = redis.execute(reserveQuotaScript, List.of(key),
                    String.valueOf(perDay), String.valueOf(TTL_SECONDS));
            count = result == null ? 1L : result;
        } catch (Exception e) {
            // Fail open, consistent with the other limiters: an unreachable counter must not stop a
            // customer working.
            log.warn("Template-create counter unavailable, allowing: {}", e.getMessage());
            return;
        }

        if (count < 0) {
            throw ApiException.of(ApiErrorCode.QUOTA_EXCEEDED,
                            "This plan allows " + perDay + " template creates per day.")
                    .withHeader(QuotaService.HEADER_QUOTA_SCOPE, "template_creates_day")
                    .withDetail("limit", perDay)
                    .withDetail("scope", "template_creates_day");
        }
    }
}
