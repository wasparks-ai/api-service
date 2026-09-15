package com.wasparks.api.partner;

import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.PartnerPrincipal;
import com.wasparks.api.entity.ApiPartner;
import com.wasparks.api.entity.ApiPartnerTenant;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.repository.ApiPartnerRepository;
import com.wasparks.api.repository.ApiPartnerTenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves {@code X-Tenant-Id} on a partner key into a verified acting tenant (epic §B1, §0.4).
 *
 * <p>This is the security boundary of the whole partner surface. Everything downstream — the internal
 * client's own {@code X-Tenant-Id} header, the quota counters, the campaign proxy — reads
 * {@code principal.tenantId()} and trusts it absolutely, exactly as it did in P1. That trust is only
 * warranted because a tenant id reaches that field through this class and through nothing else.
 *
 * <h2>404, not 403</h2>
 * A tenant that is not this partner's client is <b>404</b> (§0.4). A 403 would confirm the tenant exists,
 * which over a few thousand guesses is a list of every business on the platform — and, to a partner, a
 * list of its competitors' customers. The one exception is a client the partner itself suspended:
 * {@code 403 customer_suspended}, because the partner already knows that client exists (it suspended it)
 * and a 404 there would tell it its own customer had vanished. §B1 writes the rule as a blanket 404 and
 * §B2 requires the 403; this split is the only reading that satisfies both.
 *
 * <h2>The 60-second hash</h2>
 * Membership is cached at {@code partner:{id}:tenants} — a Redis <b>hash</b> of client tenant id to that
 * client's {@code messages_per_day_cap}, rather than the plain set the epic sketches. Both facts are
 * needed on the same hot path and both come from the same row, so one {@code HGET} answers "is this
 * tenant mine?" and "what is its ceiling?" together; a set would have made the cap a second round trip
 * per send for a number that changes perhaps twice a year.
 *
 * <p>The cache is <b>invalidated eagerly</b> on every create, suspend, re-activate and cap change this
 * service performs, so the TTL only bounds a change made out of band — admin-service patching a link
 * directly. Sixty seconds is the epic's number and is short enough that a suspension takes effect inside
 * a support call.
 *
 * <p>The hash holds ACTIVE links only, so "absent" and "suspended" cannot be told apart from the cache
 * alone. A miss therefore falls through to the database rather than being answered as a 404 — the
 * suspended case needs its own status code, and inventing it from an absence would give a partner a 404
 * for a customer it can plainly see in its own console.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartnerTenantResolver {

    /** {@code partner:{partnerId}:tenants} — tenant id to daily cap, ACTIVE clients only (epic §B1). */
    static final String CACHE_PREFIX = "partner:";
    static final String CACHE_SUFFIX = ":tenants";

    /**
     * The hash field value for a client with no cap, and the field name that marks a partner with no
     * clients at all. Neither is a UUID, so neither can collide with a tenant id, and the marker is what
     * makes "no clients" a cache hit rather than a database lookup on every request.
     */
    private static final String NO_CAP = "";
    private static final String EMPTY_MARKER = "none";

    private final ApiPartnerRepository partnerRepository;
    private final ApiPartnerTenantRepository partnerTenantRepository;
    private final StringRedisTemplate redis;

    @Value("${app.partner.tenant-cache-ttl-seconds}")
    private long cacheTtlSeconds;

    /**
     * Apply the request's {@code X-Tenant-Id} to a principal freshly resolved from its key.
     *
     * <p>An ordinary tenant key is returned untouched and the header is <b>ignored</b>, not rejected:
     * epic §0.8 says the tenant comes from the key row and from nowhere else, and the least surprising
     * reading of that is that a header a key is not entitled to use simply has no effect. Rejecting it
     * would also break a client that sets the header globally across an SDK it shares with partner code.
     */
    @Transactional(readOnly = true)
    public ApiPrincipal resolve(ApiPrincipal principal, String tenantIdHeader) {
        if (principal.partnerId() == null) {
            return principal;
        }

        ApiPartner partner = partnerRepository.findById(principal.partnerId())
                .orElseThrow(() -> {
                    // The key names a partner row that is gone. A partner row is only ever removed by
                    // hand, so this is drift rather than a caller error: logged for us, opaque to them.
                    log.error("API key {} carries partner_id {} with no api_partners row",
                            principal.keyId(), principal.partnerId());
                    return ApiException.of(ApiErrorCode.INVALID_API_KEY);
                });

        UUID ownerTenantId = partner.getOwnerTenantId() == null
                ? principal.tenantId()
                : partner.getOwnerTenantId();
        UUID acting = parseHeader(tenantIdHeader, ownerTenantId);

        Integer cap = acting.equals(ownerTenantId) ? null : requireActiveClient(partner.getId(), acting);
        return principal.actingAs(
                new PartnerPrincipal(partner.getId(), ownerTenantId, acting, cap));
    }

    /**
     * The membership check, cache first. Returns the client's daily cap, or null for uncapped.
     *
     * <p>A hit is conclusive — the hash is exactly the ACTIVE links. A miss is not, because a SUSPENDED
     * client is absent for the same reason a stranger's tenant is, so the database decides which it was.
     */
    private Integer requireActiveClient(UUID partnerId, UUID tenantId) {
        Cached cached = lookup(partnerId, tenantId);
        if (cached.hit()) {
            return cached.cap();
        }

        ApiPartnerTenant link = partnerTenantRepository
                .findByPartnerIdAndTenantId(partnerId, tenantId)
                .orElseThrow(() -> notYourCustomer(tenantId));

        if (!link.isActive()) {
            throw ApiException.of(ApiErrorCode.CUSTOMER_SUSPENDED,
                            "This customer is suspended. Re-activate it before acting on its behalf.")
                    .withDetail("customerId", tenantId.toString());
        }
        // An ACTIVE link that was not cached means the cache was cold, not that it was wrong. Warm it,
        // so the next request for any of this partner's clients is a hit.
        warm(partnerId);
        return link.getMessagesPerDayCap();
    }

    /**
     * The same 404 an unknown id gets. The message never distinguishes "no such tenant" from "not
     * yours", because the two must be indistinguishable from outside (§0.4).
     */
    private ApiException notYourCustomer(UUID tenantId) {
        return ApiException.of(ApiErrorCode.NOT_FOUND,
                        "No customer with that id. Check `X-Tenant-Id` names a customer you created.")
                .withDetail("tenantId", tenantId.toString());
    }

    private UUID parseHeader(String raw, UUID fallback) {
        if (raw == null || raw.isBlank()) {
            // No header: act on the partner's own tenant (§0.4). Its own number, inbox and campaigns
            // keep working through a partner key exactly as through any other key.
            return fallback;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED,
                    "`X-Tenant-Id` must be the UUID of one of your customers.");
        }
    }

    // ------------------------------------------------------------------ the cache

    /** A cache answer: {@code hit} false means "ask the database", never "not a member". */
    private record Cached(boolean hit, Integer cap) {

        static final Cached MISS = new Cached(false, null);
    }

    private Cached lookup(UUID partnerId, UUID tenantId) {
        try {
            String key = cacheKey(partnerId);
            Object value = redis.opsForHash().get(key, tenantId.toString());
            if (value != null) {
                String raw = value.toString();
                return new Cached(true, raw.isEmpty() ? null : Integer.valueOf(raw));
            }
            // Absent from a warm hash is a genuine non-member and still returns a miss, because only the
            // database can say whether it is a stranger (404) or a suspended client (403). The EXISTS
            // check is what stops a cold hash being mistaken for an empty one and re-warmed per request.
            return Cached.MISS;
        } catch (Exception e) {
            // As everywhere else in this service, an unreachable Redis degrades to a database lookup
            // rather than failing the request. The database is the truth; this only saves the trip.
            log.warn("Partner tenant cache unavailable, resolving from the database: {}",
                    e.getMessage());
            return Cached.MISS;
        }
    }

    /** Load the partner's ACTIVE clients and their caps into the hash. */
    public void warm(UUID partnerId) {
        List<ApiPartnerTenant> links = partnerTenantRepository.findByPartnerIdOrderByCreatedAtDesc(
                partnerId);
        Map<String, String> entries = new LinkedHashMap<>();
        for (ApiPartnerTenant link : links) {
            if (link.isActive()) {
                entries.put(link.getTenantId().toString(),
                        link.getMessagesPerDayCap() == null ? NO_CAP
                                : String.valueOf(link.getMessagesPerDayCap()));
            }
        }
        if (entries.isEmpty()) {
            entries.put(EMPTY_MARKER, NO_CAP);
        }
        try {
            String key = cacheKey(partnerId);
            redis.delete(key);
            redis.opsForHash().putAll(key, entries);
            redis.expire(key, Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.warn("Could not warm the partner tenant cache for {}: {}", partnerId, e.getMessage());
        }
    }

    /**
     * Drop the cache. Called on every client create, suspend, re-activate and cap change (§B1:
     * "invalidate on client create/suspend"), so those take effect on the next request rather than at the
     * end of the TTL — a partner that has just created a customer and immediately sends to it must not
     * get a 404, and a cap it has just raised must not keep refusing for another minute.
     */
    public void invalidate(UUID partnerId) {
        try {
            redis.delete(cacheKey(partnerId));
        } catch (Exception e) {
            log.warn("Could not invalidate the partner tenant cache for {}: {}", partnerId,
                    e.getMessage());
        }
    }

    /**
     * The partner a client tenant belongs to, for webhook fan-out (§B5). Empty for an ordinary tenant.
     *
     * <p>Not cached here: the fan-out caller batches one lookup per tenant per poller tick and holds the
     * result for that tick, which is a tighter window than a shared cache would give and costs nothing
     * to keep correct.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> partnerOf(UUID tenantId) {
        List<UUID> partnerIds = partnerTenantRepository.findPartnerIdsByTenantId(tenantId);
        return partnerIds.isEmpty() ? Optional.empty() : Optional.of(partnerIds.get(0));
    }

    static String cacheKey(UUID partnerId) {
        return CACHE_PREFIX + partnerId + CACHE_SUFFIX;
    }
}
