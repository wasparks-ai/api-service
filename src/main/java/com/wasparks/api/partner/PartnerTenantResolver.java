package com.wasparks.api.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wasparks.api.auth.ApiPrincipal;
import com.wasparks.api.auth.PartnerPrincipal;
import com.wasparks.api.entity.ApiPartner;
import com.wasparks.api.entity.ApiPartnerTenant;
import com.wasparks.api.error.ApiErrorCode;
import com.wasparks.api.error.ApiException;
import com.wasparks.api.repository.ApiPartnerRepository;
import com.wasparks.api.plans.EffectiveLimits;
import com.wasparks.api.plans.PlanResolver;
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
 *
 * <h2>Client keys</h2>
 * A key a partner issued for one of its customers has no {@code partner_id} and sends no
 * {@code X-Tenant-Id} — it is an ordinary key on an ordinary tenant. It still runs in the partner's
 * context, because the customer draws on the partner's pooled allowance and sits under the cap the
 * partner set for it; only the credential differs. That is resolved here too, from a second cache at
 * {@code partner:client:{tenantId}} which holds the whole answer (partner, owner, cap, status, pool
 * limits) or the literal {@code none} for the overwhelming majority of tenants that are nobody's
 * customer.
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

    /** Cached at {@code partner:client:{tenantId}} when a tenant is nobody's customer. */
    private static final String NOT_A_CLIENT = "none";

    private final ApiPartnerRepository partnerRepository;
    private final ApiPartnerTenantRepository partnerTenantRepository;
    private final PlanResolver planResolver;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

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
            // Not a partner key — but it may still be a CLIENT key, issued by a partner for one of its
            // customers. Those run in the partner's context too (§B1), so the header is irrelevant and
            // the key's own tenant is the acting one.
            return clientContext(principal);
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
     * Put an ordinary key into its partner's context, if its tenant is a partner's customer.
     *
     * <p>A client key is a plain {@code wsk_live_} key with no {@code partner_id} — that is the whole
     * point of it, and it stays true of the row. What changes is what it spends: the customer draws on
     * its partner's pooled allowance and is subject to the cap the partner set for it, exactly as if the
     * partner had acted for it with {@code X-Tenant-Id}. Without this, a client key ran on the customer's
     * own plan, and since a partner-provisioned tenant has no assignment that meant the default plan's
     * limits — a customer quietly capped at 200 messages a day while its partner had 20,000 unspent.
     *
     * <p><b>An explicit plan assignment wins.</b> If an admin has deliberately put a client tenant on a
     * plan of its own, that is a decision about that tenant and it keeps its plan and its own counters.
     * Only a client with no assignment pools.
     *
     * <p>A key belonging to a tenant that is nobody's customer is returned untouched, which is almost
     * every key in the estate — so the negative answer is cached as aggressively as the positive one.
     */
    private ApiPrincipal clientContext(ApiPrincipal principal) {
        ClientContext context = clientContext(principal.tenantId());
        if (context == null) {
            return principal;
        }
        if (context.suspended()) {
            // The same rule the X-Tenant-Id path applies. A partner that suspends a customer expects it
            // to stop sending, and a credential the partner itself issued is not an exception.
            throw ApiException.of(ApiErrorCode.CUSTOMER_SUSPENDED,
                            "This customer is suspended. Re-activate it before sending on its behalf.")
                    .withDetail("customerId", principal.tenantId().toString());
        }
        return principal.actingAs(
                new PartnerPrincipal(context.partnerId(), context.ownerTenantId(),
                        principal.tenantId(), context.cap()),
                context.limits());
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

    // ------------------------------------------------------------------ the client-key context

    /**
     * Everything a client key needs to run in its partner's context, or null when its tenant is nobody's
     * customer.
     *
     * <p>Cached whole, including the resolved pool limits, at {@code partner:client:{tenantId}} — and the
     * <b>negative</b> answer is cached too, as the literal {@code none}, because almost every key in the
     * estate belongs to an ordinary tenant and that lookup would otherwise be a database round trip on
     * every request they make.
     *
     * <p>Caching the limits as well is what keeps this cheap: a client key's acting tenant never varies
     * per request (unlike a partner key's), so the whole answer is stable and one Redis GET replaces a
     * link lookup, a partner lookup, an assignment check and a plan resolve. It is invalidated eagerly
     * whenever the cap or status moves; only an out-of-band plan change lags, by the same 60 seconds
     * everything else here does.
     */
    private ClientContext clientContext(UUID tenantId) {
        String cacheKey = clientCacheKey(tenantId);
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (NOT_A_CLIENT.equals(cached)) {
                return null;
            }
            if (cached != null) {
                return objectMapper.readValue(cached, ClientContext.class);
            }
        } catch (Exception e) {
            // Unreadable (an older shape) or unreachable. Fall through to the database, as everywhere.
            log.debug("Client context cache unusable for {}: {}", tenantId, e.getMessage());
        }

        ClientContext resolved = loadClientContext(tenantId);
        try {
            redis.opsForValue().set(cacheKey,
                    resolved == null ? NOT_A_CLIENT : objectMapper.writeValueAsString(resolved),
                    Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.debug("Could not cache the client context for {}: {}", tenantId, e.getMessage());
        }
        return resolved;
    }

    private ClientContext loadClientContext(UUID tenantId) {
        List<UUID> partnerIds = partnerTenantRepository.findPartnerIdsByTenantId(tenantId);
        if (partnerIds.isEmpty()) {
            return null;
        }
        ApiPartnerTenant link = partnerTenantRepository
                .findByPartnerIdAndTenantId(partnerIds.get(0), tenantId).orElse(null);
        if (link == null) {
            return null;
        }
        ApiPartner partner = partnerRepository.findById(link.getPartnerId()).orElse(null);
        if (partner == null || partner.getOwnerTenantId() == null) {
            // A client whose partner row has gone. Nothing to pool against, so it behaves as the
            // ordinary tenant it otherwise is rather than failing every request.
            log.warn("Tenant {} is linked to partner {} with no usable partner row", tenantId,
                    link.getPartnerId());
            return null;
        }
        // An explicit assignment is a decision about this tenant; it keeps its own plan and counters.
        if (planResolver.resolveExplicit(tenantId).isPresent()) {
            return null;
        }
        return new ClientContext(link.getPartnerId(), partner.getOwnerTenantId(),
                link.getMessagesPerDayCap(), !link.isActive(),
                planResolver.resolve(partner.getOwnerTenantId()));
    }

    /**
     * Drop one client's cached context. Called wherever the forward hash is invalidated, because the cap
     * and the status live in both and must not disagree.
     */
    public void invalidateClient(UUID tenantId) {
        try {
            redis.delete(clientCacheKey(tenantId));
        } catch (Exception e) {
            log.warn("Could not invalidate the client context cache for {}: {}", tenantId,
                    e.getMessage());
        }
    }

    /** The partner context a client key runs in. A record, so it round-trips through Redis as JSON. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record ClientContext(UUID partnerId, UUID ownerTenantId, Integer cap, boolean suspended,
                         EffectiveLimits limits) {
    }

    static String clientCacheKey(UUID tenantId) {
        return CACHE_PREFIX + "client:" + tenantId;
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
